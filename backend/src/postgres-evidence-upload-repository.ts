import postgres from "npm:postgres@3.4.7";
import type {
  EvidenceUploadAuthorization,
  EvidenceUploadContext,
  EvidenceUploadRepository,
  ReserveEvidenceUploadResult,
} from "./evidence-upload-repository.ts";

type DateLike = Date | string;

type UploadRow = {
  challenge_id: string;
  refresh_id: string;
  acceptance_id: string;
  actor_id: string;
  nonce_hash: Uint8Array;
  challenge_status: EvidenceUploadContext["challengeStatus"];
  challenge_expires_at: DateLike;
  claim_status: string;
  refresh_status: string;
  reserved_evidence_id: string | null;
  upload_object_key: string | null;
  upload_mime: string | null;
  video_required: boolean;
  upload_authorization: EvidenceUploadAuthorization | null;
};

const SELECT_CONTEXT = String.raw`
  select
    ec.challenge_id,
    ec.refresh_id,
    ec.acceptance_id,
    ec.actor_id,
    ec.nonce_hash,
    ec.status as challenge_status,
    ec.expires_at as challenge_expires_at,
    ra.status as claim_status,
    rr.status as refresh_status,
    ec.reserved_evidence_id,
    ec.upload_object_key,
    ec.upload_mime,
    ec.upload_authorization,
    coalesce((rr.proof_policy_snapshot->'capture'->>'video_required')::boolean,false) as video_required
  from app.evidence_challenges ec
  join app.refresh_acceptances ra
    on ra.acceptance_id = ec.acceptance_id
   and ra.refresh_id = ec.refresh_id
  join app.refresh_requests rr
    on rr.refresh_id = ec.refresh_id
`;

function date(value: DateLike): Date {
  return value instanceof Date ? value : new Date(value);
}

function fromRow(row: UploadRow): EvidenceUploadContext {
  return {
    challengeId: row.challenge_id,
    refreshId: row.refresh_id,
    acceptanceId: row.acceptance_id,
    actorId: row.actor_id,
    nonceHash: new Uint8Array(row.nonce_hash),
    challengeStatus: row.challenge_status,
    challengeExpiresAt: date(row.challenge_expires_at),
    claimStatus: row.claim_status,
    refreshStatus: row.refresh_status,
    evidenceId: row.reserved_evidence_id,
    objectKey: row.upload_object_key,
    mediaMime: row.upload_mime,
    videoRequired: row.video_required,
    authorization: row.upload_authorization,
  };
}

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

export class PostgresEvidenceUploadRepository implements EvidenceUploadRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async getContext(challengeId: string): Promise<EvidenceUploadContext | null> {
    const rows = await this.sql.unsafe(
      `${SELECT_CONTEXT} where ec.challenge_id = $1::uuid limit 1`,
      [challengeId],
    );
    return rows[0] ? fromRow(rows[0] as unknown as UploadRow) : null;
  }

  async reserveUpload(
    input: Parameters<EvidenceUploadRepository["reserveUpload"]>[0],
  ): Promise<ReserveEvidenceUploadResult> {
    return await this.sql.begin(async (tx) => {
      await tx`
        select pg_advisory_xact_lock(
          hashtextextended(${input.challengeId}, 32)
        )
      `;

      const rows = await tx.unsafe(
        `${SELECT_CONTEXT} where ec.challenge_id = $1::uuid for update of ec, ra, rr`,
        [input.challengeId],
      );
      if (!rows[0]) return { kind: "not_found" } as const;

      const context = fromRow(rows[0] as unknown as UploadRow);
      if (context.actorId !== input.actorId) {
        return { kind: "actor_mismatch" } as const;
      }
      if (context.challengeStatus !== "ISSUED") {
        return { kind: "challenge_invalid" } as const;
      }
      if (context.challengeExpiresAt.getTime() <= input.observedAt.getTime()) {
        await tx`
          update app.evidence_challenges
          set status = 'EXPIRED'
          where challenge_id = ${input.challengeId}::uuid
            and status = 'ISSUED'
        `;
        return { kind: "challenge_expired" } as const;
      }
      if (!bytesEqual(context.nonceHash, input.nonceHash)) {
        return { kind: "nonce_mismatch" } as const;
      }
      if (
        context.claimStatus !== "CAPTURE_ACTIVE" ||
        (
          context.refreshStatus !== "CAPTURE_IN_PROGRESS" &&
          context.refreshStatus !== "CLAIMED"
        )
      ) {
        return { kind: "claim_not_active" } as const;
      }

      if (context.objectKey !== null) {
        if (
          context.evidenceId === null ||
          context.evidenceId !== input.evidenceId ||
          context.mediaMime !== input.mediaMime
        ) {
          return { kind: "upload_conflict" } as const;
        }
        return {
          kind: "ready",
          evidenceId: context.evidenceId,
          objectKey: context.objectKey,
          mediaMime: context.mediaMime,
          challengeExpiresAt: context.challengeExpiresAt,
          replayed: true,
          authorization: context.authorization,
        } as const;
      }

      const updated = await tx`
        update app.evidence_challenges
        set
          reserved_evidence_id = ${input.evidenceId}::uuid,
          upload_object_key = ${input.objectKey},
          upload_mime = ${input.mediaMime},
          upload_issued_at = ${input.observedAt}
        where challenge_id = ${input.challengeId}::uuid
          and actor_id = ${input.actorId}::uuid
          and status = 'ISSUED'
          and reserved_evidence_id is null
          and upload_object_key is null
        returning reserved_evidence_id, upload_object_key, upload_mime
      `;
      if (!updated[0]) return { kind: "upload_conflict" } as const;

      await tx`
        insert into app.domain_events(
          event_id,
          entity_type,
          entity_id,
          event_type,
          actor_id,
          operation_id,
          payload,
          occurred_at
        ) values (
          ${crypto.randomUUID()}::uuid,
          'challenge',
          ${input.challengeId}::uuid,
          'EVIDENCE_UPLOAD_AUTHORIZED',
          ${input.actorId}::uuid,
          ${input.challengeId}::uuid,
          ${
        JSON.stringify({
          object_key: input.objectKey,
          media_mime: input.mediaMime,
        })
      }::text::jsonb,
          ${input.observedAt}
        )
      `;

      return {
        kind: "ready",
        evidenceId: input.evidenceId,
        objectKey: input.objectKey,
        mediaMime: input.mediaMime,
        challengeExpiresAt: context.challengeExpiresAt,
        replayed: false,
      } as const;
    });
  }

  async saveAuthorization(
    input: Parameters<EvidenceUploadRepository["saveAuthorization"]>[0],
  ): Promise<EvidenceUploadAuthorization | null> {
    // Save before replying: an upload can complete before the caller asks again.
    // Keep the winning authorization when concurrent requests sign the same path.
    const rows = await this.sql`
      update app.evidence_challenges
      set upload_authorization = case
        when (upload_authorization->>'expiresAt')::timestamptz > ${input.observedAt}
          then upload_authorization
        else ${JSON.stringify(input.authorization)}::text::jsonb
      end
      where challenge_id = ${input.challengeId}::uuid
        and actor_id = ${input.actorId}::uuid
        and reserved_evidence_id = ${input.evidenceId}::uuid
        and status = 'ISSUED'
        and expires_at > ${input.observedAt}
      returning upload_authorization
    `;
    return rows[0]?.upload_authorization as EvidenceUploadAuthorization | undefined ?? null;
  }

  async close(): Promise<void> {
    await this.sql.end({ timeout: 1 });
  }
}
