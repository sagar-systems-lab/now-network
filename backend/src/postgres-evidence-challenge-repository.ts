import postgres from "npm:postgres@3.4.7";
import type {
  EvidenceChallengeContext,
  EvidenceChallengeRecord,
  EvidenceChallengeRepository,
  IssueEvidenceChallengeResult,
} from "./evidence-challenge-repository.ts";
import { policyVersion } from "../../packages/policy/src/template.ts";

type DateLike = Date | string;

type ContextRow = {
  acceptance_id: string;
  refresh_id: string;
  actor_id: string;
  wallet_address: string;
  claim_status: EvidenceChallengeContext["claimStatus"];
  claim_deadline: DateLike | null;
  claim_revision: number | string;
  refresh_status: EvidenceChallengeContext["refreshStatus"];
  refresh_revision: number | string;
  refresh_expires_at: DateLike;
  evidence_deadline: DateLike;
  proof_policy_snapshot: EvidenceChallengeContext["proofPolicySnapshot"];
};

type ChallengeRow = {
  challenge_id: string;
  refresh_id: string;
  acceptance_id: string;
  actor_id: string;
  wallet_address: string;
  nonce_hash: Uint8Array;
  status: EvidenceChallengeRecord["status"];
  issued_at: DateLike;
  expires_at: DateLike;
  consumed_at: DateLike | null;
  revoked_at: DateLike | null;
  policy_version: number | string;
};

const SELECT_CONTEXT = String.raw`
  select
    ra.acceptance_id,
    ra.refresh_id,
    ra.actor_id,
    ra.wallet_address,
    ra.status as claim_status,
    ra.claim_deadline,
    ra.revision as claim_revision,
    rr.status as refresh_status,
    rr.revision as refresh_revision,
    rr.refresh_expires_at,
    rr.evidence_deadline,
    rr.proof_policy_snapshot
  from app.refresh_acceptances ra
  join app.refresh_requests rr on rr.refresh_id = ra.refresh_id
`;

function date(value: DateLike): Date {
  return value instanceof Date ? value : new Date(value);
}

function contextFromRow(row: ContextRow): EvidenceChallengeContext {
  return {
    acceptanceId: row.acceptance_id,
    refreshId: row.refresh_id,
    actorId: row.actor_id,
    walletAddress: row.wallet_address,
    claimStatus: row.claim_status,
    claimDeadline: row.claim_deadline === null ? null : date(row.claim_deadline),
    claimRevision: Number(row.claim_revision),
    refreshStatus: row.refresh_status,
    refreshRevision: Number(row.refresh_revision),
    refreshExpiresAt: date(row.refresh_expires_at),
    evidenceDeadline: date(row.evidence_deadline),
    proofPolicySnapshot: row.proof_policy_snapshot,
  };
}

function challengeFromRow(row: ChallengeRow): EvidenceChallengeRecord {
  return {
    challengeId: row.challenge_id,
    refreshId: row.refresh_id,
    acceptanceId: row.acceptance_id,
    actorId: row.actor_id,
    walletAddress: row.wallet_address,
    nonceHash: new Uint8Array(row.nonce_hash),
    status: row.status,
    issuedAt: date(row.issued_at),
    expiresAt: date(row.expires_at),
    consumedAt: row.consumed_at === null ? null : date(row.consumed_at),
    revokedAt: row.revoked_at === null ? null : date(row.revoked_at),
    policyVersion: Number(row.policy_version),
  };
}

function deadline(context: EvidenceChallengeContext): Date | null {
  if (context.claimDeadline === null) return null;
  return new Date(
    Math.min(
      context.claimDeadline.getTime(),
      context.evidenceDeadline.getTime(),
      context.refreshExpiresAt.getTime(),
    ),
  );
}

function challengeable(context: EvidenceChallengeContext): boolean {
  return (
    (context.claimStatus === "CLAIMED" || context.claimStatus === "CAPTURE_ACTIVE") &&
    (
      context.refreshStatus === "CLAIMED" ||
      context.refreshStatus === "CAPTURE_IN_PROGRESS"
    )
  );
}

export class PostgresEvidenceChallengeRepository implements EvidenceChallengeRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async getContext(acceptanceId: string): Promise<EvidenceChallengeContext | null> {
    const rows = await this.sql.unsafe(
      `${SELECT_CONTEXT} where ra.acceptance_id = $1::uuid limit 1`,
      [acceptanceId],
    );
    return rows[0] ? contextFromRow(rows[0] as unknown as ContextRow) : null;
  }

  async issueChallenge(
    input: Parameters<EvidenceChallengeRepository["issueChallenge"]>[0],
  ): Promise<IssueEvidenceChallengeResult> {
    return await this.sql.begin(async (tx) => {
      await tx`
        select pg_advisory_xact_lock(
          hashtextextended(${input.acceptanceId}, 31)
        )
      `;

      const rows = await tx.unsafe(
        `${SELECT_CONTEXT} where ra.acceptance_id = $1::uuid for update of ra, rr`,
        [input.acceptanceId],
      );
      if (!rows[0]) return { kind: "not_found" } as const;

      const context = contextFromRow(rows[0] as unknown as ContextRow);
      if (context.actorId !== input.actorId) {
        return { kind: "actor_mismatch" } as const;
      }
      if (!challengeable(context)) {
        return { kind: "not_available" } as const;
      }

      const latest = deadline(context);
      if (
        latest === null ||
        latest.getTime() <= input.issuedAt.getTime() ||
        input.expiresAt.getTime() <= input.issuedAt.getTime() ||
        input.expiresAt.getTime() > latest.getTime()
      ) {
        return { kind: "expired" } as const;
      }

      const expectedPolicyVersion = policyVersion(
        context.proofPolicySnapshot.template_key,
      );
      if (expectedPolicyVersion !== input.policyVersion) {
        return { kind: "policy_mismatch" } as const;
      }
      if (input.nonceHash.length !== 32) {
        throw new Error("evidence challenge nonce hash must be 32 bytes");
      }

      await tx`
        update app.evidence_challenges
        set status = 'EXPIRED'
        where acceptance_id = ${input.acceptanceId}::uuid
          and status = 'ISSUED'
          and expires_at <= ${input.issuedAt}
      `;

      await tx`
        update app.evidence_challenges
        set status = 'REVOKED', revoked_at = ${input.issuedAt}
        where acceptance_id = ${input.acceptanceId}::uuid
          and status = 'ISSUED'
      `;

      let claimRevision = context.claimRevision;
      if (context.claimStatus === "CLAIMED") {
        const updated = await tx`
          update app.refresh_acceptances
          set status = 'CAPTURE_ACTIVE', revision = revision + 1
          where acceptance_id = ${input.acceptanceId}::uuid
            and actor_id = ${input.actorId}::uuid
            and status = 'CLAIMED'
          returning revision
        `;
        if (!updated[0]) return { kind: "not_available" } as const;
        claimRevision = Number(updated[0].revision);
      }

      let refreshRevision = context.refreshRevision;
      if (context.refreshStatus === "CLAIMED") {
        const updated = await tx`
          update app.refresh_requests
          set
            status = 'CAPTURE_IN_PROGRESS',
            updated_at = ${input.issuedAt},
            revision = revision + 1
          where refresh_id = ${context.refreshId}::uuid
            and status = 'CLAIMED'
          returning revision
        `;
        if (!updated[0]) return { kind: "not_available" } as const;
        refreshRevision = Number(updated[0].revision);
      }

      const inserted = await tx`
        insert into app.evidence_challenges(
          challenge_id,
          refresh_id,
          acceptance_id,
          actor_id,
          wallet_address,
          nonce_hash,
          status,
          issued_at,
          expires_at,
          policy_version
        ) values (
          ${input.challengeId}::uuid,
          ${context.refreshId}::uuid,
          ${input.acceptanceId}::uuid,
          ${input.actorId}::uuid,
          ${context.walletAddress},
          ${input.nonceHash},
          'ISSUED',
          ${input.issuedAt},
          ${input.expiresAt},
          ${input.policyVersion}
        )
        returning
          challenge_id,
          refresh_id,
          acceptance_id,
          actor_id,
          wallet_address,
          nonce_hash,
          status,
          issued_at,
          expires_at,
          consumed_at,
          revoked_at,
          policy_version
      `;

      const eventPayload = JSON.stringify({
        challenge_id: input.challengeId,
        refresh_id: context.refreshId,
        expires_at: input.expiresAt.toISOString(),
        policy_version: input.policyVersion,
        claim_status: "CAPTURE_ACTIVE",
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
          'claim',
          ${input.acceptanceId}::uuid,
          'EVIDENCE_CHALLENGE_ISSUED',
          ${input.actorId}::uuid,
          ${input.challengeId}::uuid,
          ${claimRevision},
          ${eventPayload}::text::jsonb,
          ${input.issuedAt}
        ), (
          ${crypto.randomUUID()}::uuid,
          'refresh',
          ${context.refreshId}::uuid,
          'REFRESH_CAPTURE_STARTED',
          ${input.actorId}::uuid,
          ${input.challengeId}::uuid,
          ${refreshRevision},
          ${JSON.stringify({ status: "CAPTURE_IN_PROGRESS" })}::text::jsonb,
          ${input.issuedAt}
        )
      `;

      return {
        kind: "issued",
        challenge: challengeFromRow(inserted[0] as unknown as ChallengeRow),
        claimStatus: "CAPTURE_ACTIVE",
        claimRevision,
        refreshStatus: "CAPTURE_IN_PROGRESS",
        refreshRevision,
      } as const;
    });
  }
}
