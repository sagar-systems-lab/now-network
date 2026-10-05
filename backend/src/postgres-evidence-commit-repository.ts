import postgres from "npm:postgres@3.4.7";
import type {
  CommitEvidenceResult,
  EvidenceCommitContext,
  EvidenceCommitRecord,
  EvidenceCommitRepository,
} from "./evidence-commit-repository.ts";

type DateLike = Date | string;

type ContextRow = {
  reserved_evidence_id: string;
  challenge_id: string;
  refresh_id: string;
  acceptance_id: string;
  actor_id: string;
  wallet_address: string;
  nonce_hash: Uint8Array;
  challenge_status: EvidenceCommitContext["challengeStatus"];
  challenge_issued_at: DateLike;
  challenge_expires_at: DateLike;
  upload_object_key: string | null;
  upload_mime: string | null;
  claim_status: EvidenceCommitContext["claimStatus"];
  claim_deadline: DateLike | null;
  claim_revision: number | string;
  refresh_status: EvidenceCommitContext["refreshStatus"];
  required_witnesses: number | string;
  refresh_expires_at: DateLike;
  evidence_deadline: DateLike;
  refresh_revision: number | string;
  state_id: string;
  state_version: number | string;
  state_type: EvidenceCommitContext["stateType"];
  answer_schema: unknown;
  intent_core_hash: Uint8Array;
  execution_hash: Uint8Array | null;
  chain_locked_reward: number | string | null;
  chain_refresh_address: string | null;
  proof_policy_snapshot: EvidenceCommitContext["proofPolicySnapshot"];
};

type CommittedRow = {
  evidence_id: string;
  refresh_id: string;
  acceptance_id: string;
  challenge_id: string;
  actor_id: string;
  status: string;
  media_object_key: string;
  media_sha256: Uint8Array;
  media_size_bytes: number | string;
  media_mime: string;
  committed_at: DateLike;
  revision: number | string;
  commit_request_hash: Uint8Array;
  claim_revision: number | string;
  refresh_revision: number | string;
  refresh_status: EvidenceCommitContext["refreshStatus"];
};

type IdempotencyRow = {
  actor_id: string | null;
  operation_type: string;
  request_hash: Uint8Array;
  operation_id: string | null;
};

const SELECT_CONTEXT = String.raw`
  select
    ec.reserved_evidence_id,
    ec.challenge_id,
    ec.refresh_id,
    ec.acceptance_id,
    ec.actor_id,
    ec.wallet_address,
    ec.nonce_hash,
    ec.status as challenge_status,
    ec.issued_at as challenge_issued_at,
    ec.expires_at as challenge_expires_at,
    ec.upload_object_key,
    ec.upload_mime,
    ra.status as claim_status,
    ra.claim_deadline,
    ra.revision as claim_revision,
    rr.status as refresh_status,
    rr.required_witnesses,
    rr.refresh_expires_at,
    rr.evidence_deadline,
    rr.revision as refresh_revision,
    rr.state_id,
    rr.state_version,
    sd.state_type,
    sd.answer_schema,
    rr.intent_core_hash,
    rr.execution_hash,
    rr.chain_locked_reward,
    rr.chain_refresh_address,
    rr.proof_policy_snapshot
  from app.evidence_challenges ec
  join app.refresh_acceptances ra
    on ra.acceptance_id = ec.acceptance_id
   and ra.refresh_id = ec.refresh_id
  join app.refresh_requests rr
    on rr.refresh_id = ec.refresh_id
  join app.state_definitions sd
    on sd.state_id = rr.state_id
   and sd.version = rr.state_version
`;

const SELECT_COMMITTED = String.raw`
  select
    ep.evidence_id,
    ep.refresh_id,
    ep.acceptance_id,
    ep.challenge_id,
    ep.actor_id,
    ep.status,
    ep.media_object_key,
    ep.media_sha256,
    ep.media_size_bytes,
    ep.media_mime,
    ep.committed_at,
    ep.revision,
    ep.commit_request_hash,
    ra.revision as claim_revision,
    rr.revision as refresh_revision,
    rr.status as refresh_status
  from app.evidence_packets ep
  join app.refresh_acceptances ra
    on ra.acceptance_id = ep.acceptance_id
  join app.refresh_requests rr
    on rr.refresh_id = ep.refresh_id
`;

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

function contextFromRow(row: ContextRow): EvidenceCommitContext {
  return {
    evidenceId: row.reserved_evidence_id,
    challengeId: row.challenge_id,
    refreshId: row.refresh_id,
    acceptanceId: row.acceptance_id,
    actorId: row.actor_id,
    walletAddress: row.wallet_address,
    nonceHash: new Uint8Array(row.nonce_hash),
    challengeStatus: row.challenge_status,
    challengeIssuedAt: date(row.challenge_issued_at),
    challengeExpiresAt: date(row.challenge_expires_at),
    reservedObjectKey: row.upload_object_key,
    reservedMediaMime: row.upload_mime,
    claimStatus: row.claim_status,
    claimDeadline: row.claim_deadline === null ? null : date(row.claim_deadline),
    claimRevision: Number(row.claim_revision),
    refreshStatus: row.refresh_status,
    requiredWitnesses: Number(row.required_witnesses),
    refreshExpiresAt: date(row.refresh_expires_at),
    evidenceDeadline: date(row.evidence_deadline),
    refreshRevision: Number(row.refresh_revision),
    stateId: row.state_id,
    stateVersion: Number(row.state_version),
    stateType: row.state_type,
    answerSchema: row.answer_schema,
    intentCoreHash: new Uint8Array(row.intent_core_hash),
    executionHash: row.execution_hash === null ? null : new Uint8Array(row.execution_hash),
    chainLockedRewardAtomic: row.chain_locked_reward === null
      ? null
      : BigInt(row.chain_locked_reward),
    chainRefreshAddress: row.chain_refresh_address,
    proofPolicySnapshot: row.proof_policy_snapshot,
  };
}

function committedFromRow(row: CommittedRow): EvidenceCommitRecord {
  return {
    evidenceId: row.evidence_id,
    refreshId: row.refresh_id,
    acceptanceId: row.acceptance_id,
    challengeId: row.challenge_id,
    actorId: row.actor_id,
    status: "COMMITTED",
    mediaObjectKey: row.media_object_key,
    mediaSha256: new Uint8Array(row.media_sha256),
    mediaSizeBytes: Number(row.media_size_bytes),
    mediaMime: row.media_mime,
    committedAt: date(row.committed_at),
    revision: Number(row.revision),
    claimRevision: Number(row.claim_revision),
    refreshRevision: Number(row.refresh_revision),
    refreshStatus: row.refresh_status,
  };
}

function idempotencyMatches(
  row: IdempotencyRow,
  actorId: string,
  requestHash: Uint8Array,
  evidenceId: string,
): boolean {
  return row.actor_id === actorId &&
    row.operation_type === "EVIDENCE_COMMIT_V1" &&
    row.operation_id === evidenceId &&
    bytesEqual(new Uint8Array(row.request_hash), requestHash);
}

export class PostgresEvidenceCommitRepository implements EvidenceCommitRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async getContext(evidenceId: string): Promise<EvidenceCommitContext | null> {
    const rows = await this.sql.unsafe(
      `${SELECT_CONTEXT} where ec.reserved_evidence_id = $1::uuid limit 1`,
      [evidenceId],
    );
    return rows[0] ? contextFromRow(rows[0] as unknown as ContextRow) : null;
  }

  async commitEvidence(
    input: Parameters<EvidenceCommitRepository["commitEvidence"]>[0],
  ): Promise<CommitEvidenceResult> {
    return await this.sql.begin(async (tx) => {
      const storedKey = `${input.actorId}:evidence:commit:v1:${input.idempotencyKey}`;
      await tx`
        select pg_advisory_xact_lock(
          hashtextextended(${storedKey}, 41)
        )
      `;

      const idempotencyRows = await tx`
        select actor_id, operation_type, request_hash, operation_id
        from app.idempotency_records
        where idempotency_key = ${storedKey}
        for update
      `;
      if (idempotencyRows[0]) {
        const row = idempotencyRows[0] as IdempotencyRow;
        if (
          !idempotencyMatches(
            row,
            input.actorId,
            input.requestHash,
            input.evidenceId,
          )
        ) {
          return { kind: "idempotency_conflict" } as const;
        }
        const replayRows = await tx.unsafe(
          `${SELECT_COMMITTED} where ep.evidence_id = $1::uuid limit 1`,
          [input.evidenceId],
        );
        if (!replayRows[0]) {
          throw new Error("evidence idempotency record references missing evidence");
        }
        return {
          kind: "replayed",
          ...committedFromRow(replayRows[0] as unknown as CommittedRow),
        } as const;
      }

      const existingRows = await tx.unsafe(
        `${SELECT_COMMITTED} where ep.evidence_id = $1::uuid limit 1`,
        [input.evidenceId],
      );
      if (existingRows[0]) {
        const row = existingRows[0] as unknown as CommittedRow;
        if (row.actor_id !== input.actorId) {
          return { kind: "actor_mismatch" } as const;
        }
        if (!bytesEqual(new Uint8Array(row.commit_request_hash), input.requestHash)) {
          return { kind: "idempotency_conflict" } as const;
        }

        await tx`
          insert into app.idempotency_records(
            idempotency_key,
            actor_id,
            operation_type,
            request_hash,
            status,
            response_code,
            response_body,
            operation_id,
            expires_at
          ) values (
            ${storedKey},
            ${input.actorId}::uuid,
            'EVIDENCE_COMMIT_V1',
            ${input.requestHash},
            'COMPLETED',
            200,
            ${JSON.stringify({ evidence_id: input.evidenceId })}::text::jsonb,
            ${input.evidenceId}::uuid,
            ${input.idempotencyExpiresAt}
          )
        `;

        return {
          kind: "replayed",
          ...committedFromRow(row),
        } as const;
      }

      const contextRows = await tx.unsafe(
        `${SELECT_CONTEXT} where ec.reserved_evidence_id = $1::uuid for update of ec, ra, rr`,
        [input.evidenceId],
      );
      if (!contextRows[0]) return { kind: "not_found" } as const;
      const context = contextFromRow(contextRows[0] as unknown as ContextRow);

      if (context.actorId !== input.actorId) {
        return { kind: "actor_mismatch" } as const;
      }
      if (
        context.challengeStatus !== "ISSUED" ||
        !bytesEqual(context.nonceHash, input.nonceHash)
      ) {
        return { kind: "challenge_invalid" } as const;
      }
      if (
        context.challengeExpiresAt.getTime() <= input.observedAt.getTime() ||
        context.claimDeadline === null ||
        context.claimDeadline.getTime() <= input.observedAt.getTime() ||
        context.evidenceDeadline.getTime() <= input.observedAt.getTime() ||
        context.refreshExpiresAt.getTime() <= input.observedAt.getTime()
      ) {
        return { kind: "expired" } as const;
      }
      if (
        context.claimStatus !== "CAPTURE_ACTIVE" ||
        context.refreshStatus !== "CAPTURE_IN_PROGRESS"
      ) {
        return { kind: "claim_not_active" } as const;
      }
      if (
        context.reservedObjectKey !== input.mediaObjectKey ||
        context.reservedMediaMime !== input.mediaMime
      ) {
        return { kind: "reservation_mismatch" } as const;
      }
      if (
        input.executionHash.length !== 32 ||
        (
          context.executionHash !== null &&
          !bytesEqual(context.executionHash, input.executionHash)
        )
      ) {
        return { kind: "execution_conflict" } as const;
      }

      const mediaKey = [...input.mediaSha256]
        .map((byte) => byte.toString(16).padStart(2, "0"))
        .join("");
      await tx`
        select pg_advisory_xact_lock(
          hashtextextended(${mediaKey}, 42)
        )
      `;
      const replayRows = await tx`
        select evidence_id
        from app.evidence_packets
        where media_sha256 = ${input.mediaSha256}
          and evidence_id <> ${input.evidenceId}::uuid
        limit 1
      `;
      if (replayRows[0]) return { kind: "exact_replay" } as const;
      if (input.video) {
        await tx`select pg_advisory_xact_lock(hashtextextended(${input.video.sha256}, 43))`;
        const reused = await tx`select evidence_id from app.evidence_packets
          where video_metadata->>'sha256'=${input.video.sha256} and evidence_id<>${input.evidenceId}::uuid limit 1`;
        if (reused[0]) return { kind: "exact_replay" } as const;
      }

      await tx`
        update app.refresh_requests
        set execution_hash = coalesce(execution_hash, ${input.executionHash})
        where refresh_id = ${context.refreshId}::uuid
      `;

      const inserted = await tx`
        insert into app.evidence_packets(
          evidence_id,
          refresh_id,
          acceptance_id,
          challenge_id,
          actor_id,
          wallet_address,
          state_id,
          state_version,
          intent_core_hash,
          execution_hash,
          answer_type,
          answer_value,
          capture_started_monotonic_ms,
          capture_completed_monotonic_ms,
          server_observation_earliest,
          server_observation_latest,
          media_object_key,
          media_sha256,
          media_size_bytes,
          media_mime,
          video_metadata,
          status,
          committed_at,
          updated_at,
          revision,
          commit_request_hash
        ) values (
          ${input.evidenceId}::uuid,
          ${context.refreshId}::uuid,
          ${context.acceptanceId}::uuid,
          ${context.challengeId}::uuid,
          ${input.actorId}::uuid,
          ${context.walletAddress},
          ${context.stateId}::uuid,
          ${context.stateVersion},
          ${context.intentCoreHash},
          ${input.executionHash},
          ${input.answerType},
          ${JSON.stringify(input.answerValue)}::text::jsonb,
          ${input.captureStartedMonotonicMs},
          ${input.captureCompletedMonotonicMs},
          ${context.challengeIssuedAt},
          ${input.observedAt},
          ${input.mediaObjectKey},
          ${input.mediaSha256},
          ${input.mediaSizeBytes},
          ${input.mediaMime},
          ${input.video ? JSON.stringify(input.video) : null}::text::jsonb,
          'COMMITTED',
          ${input.observedAt},
          ${input.observedAt},
          1,
          ${input.requestHash}
        )
        returning revision
      `;
      const evidenceRevision = Number(inserted[0].revision);

      for (const sample of input.locationSamples) {
        await tx`
          insert into app.evidence_location_samples(
            sample_id,
            evidence_id,
            sample_order,
            point,
            accuracy_m,
            provider,
            mock_signal,
            captured_offset_ms
          ) values (
            ${crypto.randomUUID()}::uuid,
            ${input.evidenceId}::uuid,
            ${sample.sampleOrder},
            extensions.st_setsrid(
              extensions.st_makepoint(${sample.lng}, ${sample.lat}),
              4326
            )::extensions.geography,
            ${sample.accuracyM},
            ${sample.provider},
            ${sample.mockSignal},
            ${sample.capturedOffsetMs}
          )
        `;
      }

      const challengeUpdated = await tx`
        update app.evidence_challenges
        set status = 'CONSUMED', consumed_at = ${input.observedAt}
        where challenge_id = ${context.challengeId}::uuid
          and status = 'ISSUED'
          and reserved_evidence_id = ${input.evidenceId}::uuid
        returning challenge_id
      `;
      if (!challengeUpdated[0]) {
        throw new Error("challenge state changed while evidence commit was locked");
      }

      const claimUpdated = await tx`
        update app.refresh_acceptances
        set status = 'EVIDENCE_COMMITTED', revision = revision + 1
        where acceptance_id = ${context.acceptanceId}::uuid
          and actor_id = ${input.actorId}::uuid
          and status = 'CAPTURE_ACTIVE'
        returning revision
      `;
      if (!claimUpdated[0]) {
        throw new Error("claim state changed while evidence commit was locked");
      }
      const claimRevision = Number(claimUpdated[0].revision);

      const committedRows = await tx`
        select count(*)::integer as committed_count
        from app.evidence_packets
        where refresh_id = ${context.refreshId}::uuid
          and status in ('COMMITTED', 'VERIFYING', 'VERIFIED', 'CONFLICT')
      `;
      const committedCount = Number(committedRows[0].committed_count);
      const refreshStatus = committedCount >= context.requiredWitnesses
        ? "EVIDENCE_SUBMITTED"
        : "CAPTURE_IN_PROGRESS";

      const refreshUpdated = await tx`
        update app.refresh_requests
        set
          status = ${refreshStatus},
          updated_at = ${input.observedAt},
          revision = revision + 1
        where refresh_id = ${context.refreshId}::uuid
          and status = 'CAPTURE_IN_PROGRESS'
        returning revision
      `;
      if (!refreshUpdated[0]) {
        throw new Error("refresh state changed while evidence commit was locked");
      }
      const refreshRevision = Number(refreshUpdated[0].revision);

      await tx`
        insert into app.idempotency_records(
          idempotency_key,
          actor_id,
          operation_type,
          request_hash,
          status,
          response_code,
          response_body,
          operation_id,
          expires_at
        ) values (
          ${storedKey},
          ${input.actorId}::uuid,
          'EVIDENCE_COMMIT_V1',
          ${input.requestHash},
          'COMPLETED',
          201,
          ${JSON.stringify({ evidence_id: input.evidenceId })}::text::jsonb,
          ${input.evidenceId}::uuid,
          ${input.idempotencyExpiresAt}
        )
      `;

      const eventPayload = JSON.stringify({
        evidence_id: input.evidenceId,
        refresh_id: context.refreshId,
        acceptance_id: context.acceptanceId,
        media_object_key: input.mediaObjectKey,
        media_sha256: mediaKey,
      });
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
          'evidence',
          ${input.evidenceId}::uuid,
          'EVIDENCE_COMMITTED',
          ${input.actorId}::uuid,
          ${input.evidenceId}::uuid,
          ${evidenceRevision},
          ${eventPayload}::text::jsonb,
          ${input.observedAt}
        ), (
          ${crypto.randomUUID()}::uuid,
          'claim',
          ${context.acceptanceId}::uuid,
          'CLAIM_EVIDENCE_COMMITTED',
          ${input.actorId}::uuid,
          ${input.evidenceId}::uuid,
          ${claimRevision},
          ${JSON.stringify({ status: "EVIDENCE_COMMITTED" })}::text::jsonb,
          ${input.observedAt}
        ), (
          ${crypto.randomUUID()}::uuid,
          'refresh',
          ${context.refreshId}::uuid,
          'REFRESH_EVIDENCE_COMMITTED',
          ${input.actorId}::uuid,
          ${input.evidenceId}::uuid,
          ${refreshRevision},
          ${
        JSON.stringify({
          status: refreshStatus,
          committed_evidence: committedCount,
          required_witnesses: context.requiredWitnesses,
        })
      }::text::jsonb,
          ${input.observedAt}
        )
      `;

      return {
        kind: "committed",
        evidenceId: input.evidenceId,
        refreshId: context.refreshId,
        acceptanceId: context.acceptanceId,
        challengeId: context.challengeId,
        actorId: input.actorId,
        status: "COMMITTED",
        mediaObjectKey: input.mediaObjectKey,
        mediaSha256: input.mediaSha256,
        mediaSizeBytes: input.mediaSizeBytes,
        mediaMime: input.mediaMime,
        committedAt: input.observedAt,
        revision: evidenceRevision,
        claimRevision,
        refreshRevision,
        refreshStatus,
      } as const;
    });
  }
}
