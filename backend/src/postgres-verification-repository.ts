import postgres from "npm:postgres@3.4.7";
import type { VerificationCandidate } from "./verification-worker.ts";
import type {
  ExistingVerification,
  PersistVerificationResult,
  VerificationContext,
  VerificationEvidence,
  VerificationRepository,
} from "./verification-repository.ts";
import type { VerificationResult } from "../../packages/contracts/src/core.ts";
import type { RefreshStatus, VerificationStatus } from "../../packages/contracts/src/lifecycle.ts";

type DateLike = Date | string;

type RefreshRow = {
  refresh_id: string;
  requester_actor_id: string;
  status: RefreshStatus;
  revision: number | string;
  refresh_expires_at: DateLike;
  evidence_deadline: DateLike;
  state_id: string;
  state_version: number | string;
  state_type: VerificationContext["stateType"];
  answer_schema: unknown;
  intent_core_hash: Uint8Array;
  execution_hash: Uint8Array | null;
  proof_policy_snapshot: VerificationContext["proofPolicySnapshot"];
};

type EvidenceRow = {
  evidence_id: string;
  actor_id: string;
  answer_type: VerificationEvidence["answerType"];
  answer_value: unknown;
  intent_core_hash: Uint8Array;
  execution_hash: Uint8Array;
  media_sha256: Uint8Array | null;
  media_size_bytes: number | string | null;
  media_mime: string | null;
  video_metadata: VerificationEvidence["video"];
  location_sample_count: number | string;
  has_mock_location: boolean;
  server_observation_earliest: DateLike;
  server_observation_latest: DateLike;
  committed_at: DateLike;
  status: VerificationEvidence["status"];
};

type VerificationRow = {
  verification_result_id: string;
  evidence_set_revision: number | string;
  policy_version: number | string;
  status: VerificationStatus;
  reason_codes: string[];
  evidence_ids: string[];
  final_answer: unknown | null;
  canonical_digest: Uint8Array;
  completed_at: DateLike | null;
};

function date(value: DateLike): Date {
  return value instanceof Date ? value : new Date(value);
}

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function arraysEqual(
  left: readonly string[],
  right: readonly string[],
): boolean {
  return left.length === right.length &&
    left.every((value, index) => value === right[index]);
}

function terminalResult(status: VerificationStatus): VerificationResult {
  switch (status) {
    case "VERIFIED":
      return "VERIFIED";
    case "CONFLICT":
      return "CONFLICT";
    case "WAITING_FOR_MORE_EVIDENCE":
      return "REQUIRES_ADDITIONAL_VERIFICATION";
    case "REJECTED":
      return "REJECTED";
    case "EXPIRED":
      return "EXPIRED";
    default:
      throw new Error(`verification status ${status} is not terminal`);
  }
}

function finalRefreshStatus(result: VerificationResult): RefreshStatus {
  switch (result) {
    case "VERIFIED":
      return "VERIFIED";
    case "CONFLICT":
      return "ADDITIONAL_VERIFICATION";
    case "REQUIRES_ADDITIONAL_VERIFICATION":
      return "ADDITIONAL_VERIFICATION";
    case "REJECTED":
      return "CLAIMED";
    case "EXPIRED":
      return "EXPIRED";
  }
}

function evidenceFromRow(row: EvidenceRow): VerificationEvidence {
  return {
    evidenceId: row.evidence_id,
    actorId: row.actor_id,
    answerType: row.answer_type,
    answerValue: row.answer_value,
    intentCoreHash: new Uint8Array(row.intent_core_hash),
    executionHash: new Uint8Array(row.execution_hash),
    mediaSha256: row.media_sha256 === null ? null : new Uint8Array(row.media_sha256),
    mediaSizeBytes: row.media_size_bytes === null ? null : Number(row.media_size_bytes),
    mediaMime: row.media_mime,
    video: row.video_metadata,
    locationSampleCount: Number(row.location_sample_count),
    hasMockLocation: row.has_mock_location,
    serverObservationEarliest: date(row.server_observation_earliest),
    serverObservationLatest: date(row.server_observation_latest),
    committedAt: date(row.committed_at),
    status: row.status,
  };
}

function existingFromRow(row: VerificationRow): ExistingVerification {
  return {
    verificationResultId: row.verification_result_id,
    evidenceSetRevision: Number(row.evidence_set_revision),
    policyVersion: Number(row.policy_version),
    status: row.status,
    reasonCodes: row.reason_codes,
    evidenceIds: row.evidence_ids,
    finalAnswer: row.final_answer,
    canonicalDigest: new Uint8Array(row.canonical_digest),
    completedAt: row.completed_at === null ? null : date(row.completed_at),
  };
}

const EVIDENCE_SQL = String.raw`
  select
    ep.evidence_id,
    ep.actor_id,
    ep.answer_type,
    ep.answer_value,
    ep.intent_core_hash,
    ep.execution_hash,
    ep.media_sha256,
    ep.media_size_bytes,
    ep.media_mime,
    ep.video_metadata,
    (
      select count(*)::integer
      from app.evidence_location_samples els
      where els.evidence_id = ep.evidence_id
    ) as location_sample_count,
    exists (
      select 1 from app.evidence_location_samples els
      where els.evidence_id = ep.evidence_id and els.mock_signal is true
    ) as has_mock_location,
    ep.server_observation_earliest,
    ep.server_observation_latest,
    ep.committed_at,
    ep.status
  from app.evidence_packets ep
  where ep.refresh_id = $1::uuid
    and ep.status in ('COMMITTED', 'VERIFYING', 'VERIFIED', 'CONFLICT')
  order by ep.committed_at asc, ep.evidence_id asc
`;

export class PostgresVerificationRepository implements VerificationRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async listPending(limit: number): Promise<VerificationCandidate[]> {
    const rows = await this.sql`
      select rr.refresh_id, a.actor_id, a.status, a.revision
      from app.refresh_requests rr
      join app.actors a on a.actor_id = rr.requester_actor_id
      where a.status = 'ACTIVE' and (
        (rr.status = 'EVIDENCE_SUBMITTED' and rr.refresh_expires_at > now())
        or (rr.status in ('VERIFIED', 'SETTLEMENT_PENDING', 'SETTLEMENT_VERIFYING', 'COMPLETED')
          and exists (
            select 1 from app.verification_results vr
            where vr.refresh_id = rr.refresh_id and vr.status = 'VERIFIED'
              and not exists (
                select 1 from app.state_history sh
                where sh.verification_result_id = vr.verification_result_id
              )
          ))
      )
      order by rr.updated_at, rr.refresh_id
      limit ${Math.max(1, Math.min(32, Math.trunc(limit)))}
    `;
    return rows.map((row) => ({
      refreshId: row.refresh_id as string,
      actor: {
        actorId: row.actor_id as string,
        status: "ACTIVE" as const,
        revision: Number(row.revision),
      },
    }));
  }

  async getContext(refreshId: string): Promise<VerificationContext | null> {
    const refreshRows = await this.sql`
      select
        rr.refresh_id,
        rr.requester_actor_id,
        rr.status,
        rr.revision,
        rr.refresh_expires_at,
        rr.evidence_deadline,
        rr.state_id,
        rr.state_version,
        sd.state_type,
        sd.answer_schema,
        rr.intent_core_hash,
        rr.execution_hash,
        rr.proof_policy_snapshot
      from app.refresh_requests rr
      join app.state_definitions sd
        on sd.state_id = rr.state_id
       and sd.version = rr.state_version
      where rr.refresh_id = ${refreshId}::uuid
      limit 1
    `;
    if (!refreshRows[0]) return null;
    const row = refreshRows[0] as unknown as RefreshRow;

    const evidenceRows = await this.sql.unsafe(EVIDENCE_SQL, [refreshId]);
    const evidence = evidenceRows.map((item) => evidenceFromRow(item as unknown as EvidenceRow));

    const verificationRows = await this.sql`
      select
        verification_result_id,
        evidence_set_revision,
        policy_version,
        status,
        reason_codes,
        evidence_ids,
        final_answer,
        canonical_digest,
        completed_at
      from app.verification_results
      where refresh_id = ${refreshId}::uuid
      order by evidence_set_revision desc, policy_version desc, created_at desc
      limit 1
    `;

    return {
      refreshId: row.refresh_id,
      requesterActorId: row.requester_actor_id,
      refreshStatus: row.status,
      refreshRevision: Number(row.revision),
      refreshExpiresAt: date(row.refresh_expires_at),
      evidenceDeadline: date(row.evidence_deadline),
      stateId: row.state_id,
      stateVersion: Number(row.state_version),
      stateType: row.state_type,
      answerSchema: row.answer_schema,
      intentCoreHash: new Uint8Array(row.intent_core_hash),
      executionHash: row.execution_hash === null ? null : new Uint8Array(row.execution_hash),
      proofPolicySnapshot: row.proof_policy_snapshot,
      evidence,
      existing: verificationRows[0]
        ? existingFromRow(verificationRows[0] as unknown as VerificationRow)
        : null,
    };
  }

  async persist(
    input: Parameters<VerificationRepository["persist"]>[0],
  ): Promise<PersistVerificationResult> {
    return await this.sql.begin(async (tx) => {
      await tx`
        select pg_advisory_xact_lock(
          hashtextextended(${input.refreshId}, 51)
        )
      `;

      const refreshRows = await tx`
        select
          rr.refresh_id,
          rr.requester_actor_id,
          rr.status,
          rr.revision,
          rr.refresh_expires_at,
          rr.evidence_deadline,
          rr.state_id,
          rr.state_version,
          sd.state_type,
          sd.answer_schema,
          rr.intent_core_hash,
          rr.execution_hash,
          rr.proof_policy_snapshot
        from app.refresh_requests rr
        join app.state_definitions sd
          on sd.state_id = rr.state_id
         and sd.version = rr.state_version
        where rr.refresh_id = ${input.refreshId}::uuid
        for update of rr
      `;
      if (!refreshRows[0]) return { kind: "not_found" } as const;
      const refresh = refreshRows[0] as unknown as RefreshRow;

      const evidenceRows = await tx.unsafe(EVIDENCE_SQL, [input.refreshId]);
      const evidence = evidenceRows.map((item) => evidenceFromRow(item as unknown as EvidenceRow));
      const currentEvidenceIds = evidence.map((item) => item.evidenceId);

      const actorAuthorized = refresh.requester_actor_id === input.actorId ||
        evidence.some((item) => item.actorId === input.actorId);
      if (!actorAuthorized) return { kind: "actor_mismatch" } as const;

      const existingRows = await tx`
        select
          verification_result_id,
          evidence_set_revision,
          policy_version,
          status,
          reason_codes,
          evidence_ids,
          final_answer,
          canonical_digest,
          completed_at
        from app.verification_results
        where refresh_id = ${input.refreshId}::uuid
          and evidence_set_revision = ${input.evidenceSetRevision}
          and policy_version = ${input.policyVersion}
        limit 1
      `;
      if (existingRows[0]) {
        const existing = existingFromRow(
          existingRows[0] as unknown as VerificationRow,
        );
        if (
          existing.completedAt === null ||
          !["VERIFIED", "CONFLICT", "WAITING_FOR_MORE_EVIDENCE", "REJECTED"].includes(
            existing.status,
          )
        ) {
          return { kind: "not_eligible" } as const;
        }
        return {
          kind: "replayed",
          verificationResultId: existing.verificationResultId,
          refreshId: input.refreshId,
          evidenceSetRevision: existing.evidenceSetRevision,
          policyVersion: existing.policyVersion,
          result: terminalResult(existing.status),
          status: existing.status,
          reasonCodes: existing.reasonCodes,
          evidenceIds: existing.evidenceIds,
          finalAnswer: existing.finalAnswer,
          canonicalDigest: existing.canonicalDigest,
          refreshStatus: refresh.status,
          refreshRevision: Number(refresh.revision),
          completedAt: existing.completedAt,
        } as const;
      }

      if (date(refresh.refresh_expires_at).getTime() <= input.observedAt.getTime()) {
        return { kind: "expired" } as const;
      }
      if (refresh.status !== "EVIDENCE_SUBMITTED") {
        return { kind: "not_eligible" } as const;
      }
      if (
        currentEvidenceIds.length !== input.evidenceSetRevision ||
        !arraysEqual(currentEvidenceIds, input.evidenceIds)
      ) {
        return { kind: "evidence_set_changed" } as const;
      }

      const policyRows = await tx`
        select 1
        from app.refresh_requests
        where refresh_id = ${input.refreshId}::uuid
          and proof_policy_snapshot = ${JSON.stringify(input.policySnapshot)}::text::jsonb
      `;
      if (!policyRows[0]) return { kind: "policy_changed" } as const;

      if (
        refresh.execution_hash === null ||
        !bytesEqual(new Uint8Array(refresh.execution_hash), input.executionHash)
      ) {
        return { kind: "execution_conflict" } as const;
      }

      const started = await tx`
        update app.refresh_requests
        set
          status = 'VERIFYING',
          updated_at = ${input.observedAt},
          revision = revision + 1
        where refresh_id = ${input.refreshId}::uuid
          and status = 'EVIDENCE_SUBMITTED'
        returning revision
      `;
      if (!started[0]) {
        throw new Error("refresh verification start lost its locked state");
      }

      await tx`
        insert into app.verification_results(
          verification_result_id,
          refresh_id,
          evidence_set_revision,
          policy_version,
          status,
          reason_codes,
          evidence_ids,
          final_answer,
          verification_trace,
          location_summary,
          freshness_summary,
          media_integrity_summary,
          replay_summary,
          conflict_summary,
          execution_hash,
          canonical_digest,
          verifier_build,
          started_at,
          completed_at
        ) values (
          ${input.verificationResultId}::uuid,
          ${input.refreshId}::uuid,
          ${input.evidenceSetRevision},
          ${input.policyVersion},
          ${input.outcome.status},
          ${input.outcome.reasonCodes},
          ${input.evidenceIds}::uuid[],
          ${JSON.stringify(input.outcome.finalAnswer)}::text::jsonb,
          ${JSON.stringify(input.outcome.verificationTrace)}::text::jsonb,
          ${JSON.stringify(input.outcome.locationSummary)}::text::jsonb,
          ${JSON.stringify(input.outcome.freshnessSummary)}::text::jsonb,
          ${JSON.stringify(input.outcome.mediaIntegritySummary)}::text::jsonb,
          ${JSON.stringify(input.outcome.replaySummary)}::text::jsonb,
          ${JSON.stringify(input.outcome.conflictSummary)}::text::jsonb,
          ${input.executionHash},
          ${input.canonicalDigest},
          ${input.verifierBuild},
          ${input.observedAt},
          ${input.observedAt}
        )
      `;

      if (input.outcome.result === "VERIFIED") {
        await tx`
          update app.evidence_packets
          set
            status = 'VERIFIED',
            updated_at = ${input.observedAt},
            revision = revision + 1
          where refresh_id = ${input.refreshId}::uuid
            and evidence_id = any(${input.outcome.matchingEvidenceIds}::uuid[])
            and status in ('COMMITTED', 'CONFLICT')
        `;

        await tx`
          update app.evidence_packets
          set
            status = 'CONFLICT',
            updated_at = ${input.observedAt},
            revision = revision + 1
          where refresh_id = ${input.refreshId}::uuid
            and evidence_id = any(${input.evidenceIds}::uuid[])
            and not (evidence_id = any(${input.outcome.matchingEvidenceIds}::uuid[]))
            and status in ('COMMITTED', 'VERIFIED')
        `;
      } else if (input.outcome.result === "CONFLICT") {
        await tx`
          update app.evidence_packets
          set
            status = 'CONFLICT',
            updated_at = ${input.observedAt},
            revision = revision + 1
          where refresh_id = ${input.refreshId}::uuid
            and evidence_id = any(${input.evidenceIds}::uuid[])
            and status = 'COMMITTED'
        `;
      }

      const finalStatus = finalRefreshStatus(input.outcome.result);
      const finalized = await tx`
        update app.refresh_requests
        set
          status = ${finalStatus},
          updated_at = ${input.observedAt},
          revision = revision + 1
        where refresh_id = ${input.refreshId}::uuid
          and status = 'VERIFYING'
        returning revision
      `;
      if (!finalized[0]) {
        throw new Error("refresh verification finalization lost its locked state");
      }
      const refreshRevision = Number(finalized[0].revision);

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          actor_id,
          operation_id,
          entity_revision,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'verification',
          ${input.verificationResultId}::uuid,
          'VERIFICATION_COMPLETED',
          ${input.actorId}::uuid,
          ${input.verificationResultId}::uuid,
          1,
          ${
        JSON.stringify({
          result: input.outcome.result,
          status: input.outcome.status,
          evidence_set_revision: input.evidenceSetRevision,
          policy_version: input.policyVersion,
          evidence_ids: input.evidenceIds,
        })
      }::text::jsonb,
          ${input.observedAt}
        ), (
          ${crypto.randomUUID()}::uuid,
          'refresh',
          ${input.refreshId}::uuid,
          'REFRESH_VERIFICATION_COMPLETED',
          ${input.actorId}::uuid,
          ${input.verificationResultId}::uuid,
          ${refreshRevision},
          ${
        JSON.stringify({
          status: finalStatus,
          verification_result_id: input.verificationResultId,
          result: input.outcome.result,
        })
      }::text::jsonb,
          ${input.observedAt}
        )
      `;

      return {
        kind: "recorded",
        verificationResultId: input.verificationResultId,
        refreshId: input.refreshId,
        evidenceSetRevision: input.evidenceSetRevision,
        policyVersion: input.policyVersion,
        result: input.outcome.result,
        status: input.outcome.status,
        reasonCodes: [...input.outcome.reasonCodes],
        evidenceIds: [...input.evidenceIds],
        finalAnswer: input.outcome.finalAnswer,
        canonicalDigest: input.canonicalDigest,
        refreshStatus: finalStatus,
        refreshRevision,
        completedAt: input.observedAt,
      } as const;
    });
  }
}
