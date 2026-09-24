import postgres from "npm:postgres@3.4.7";
import type {
  ActorRecord,
  ChallengeConsumptionResult,
  IdentityRepository,
  WalletBindingChallengeRecord,
  WalletBindingRecord,
} from "./identity-repository.ts";

const PROTECTED_ACTOR_STATE_SQL = `
  exists(select 1 from app.actor_auth_principals p where p.actor_id = $1 and p.auth_user_id <> $2)
  or exists(select 1 from app.wallet_bindings w where w.actor_id = $1)
  or exists(select 1 from app.refresh_requests r where r.requester_actor_id = $1)
  or exists(select 1 from app.refresh_contributions c where c.actor_id = $1)
  or exists(select 1 from app.refresh_acceptances a where a.actor_id = $1)
  or exists(select 1 from app.evidence_challenges c where c.actor_id = $1)
  or exists(select 1 from app.evidence_packets e where e.actor_id = $1)
  or exists(select 1 from app.refund_operations r where r.actor_id = $1)
`;

type ActorRow = {
  actor_id: string;
  status: ActorRecord["status"];
  revision: number | string;
};

type BindingRow = {
  wallet_binding_id: string;
  actor_id: string;
  wallet_address: string;
  cluster: string;
  status: WalletBindingRecord["status"];
  revision: number | string;
  actor_status?: ActorRecord["status"];
  actor_revision?: number | string;
};

type ChallengeRow = {
  challenge_id: string;
  actor_id: string;
  auth_user_id: string;
  wallet_address: string;
  cluster: string;
  purpose: "wallet_binding";
  domain: "NOW Network";
  message: string;
  message_sha256: Uint8Array;
  nonce_hash: Uint8Array;
  status: WalletBindingChallengeRecord["status"];
  issued_at: Date | string;
  expires_at: Date | string;
  consumed_at: Date | string | null;
};

function actorFromRow(row: ActorRow): ActorRecord {
  return {
    actorId: row.actor_id,
    status: row.status,
    revision: Number(row.revision),
  };
}

function bindingFromRow(row: BindingRow): WalletBindingRecord {
  return {
    walletBindingId: row.wallet_binding_id,
    actorId: row.actor_id,
    walletAddress: row.wallet_address,
    cluster: row.cluster,
    status: row.status,
    revision: Number(row.revision),
  };
}

function challengeFromRow(row: ChallengeRow): WalletBindingChallengeRecord {
  return {
    challengeId: row.challenge_id,
    actorId: row.actor_id,
    authUserId: row.auth_user_id,
    walletAddress: row.wallet_address,
    cluster: row.cluster,
    purpose: row.purpose,
    domain: row.domain,
    message: row.message,
    messageSha256: new Uint8Array(row.message_sha256),
    nonceHash: new Uint8Array(row.nonce_hash),
    status: row.status,
    issuedAt: new Date(row.issued_at),
    expiresAt: new Date(row.expires_at),
    consumedAt: row.consumed_at === null ? null : new Date(row.consumed_at),
  };
}

export class PostgresIdentityRepository implements IdentityRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async resolveActor(authUserId: string, principalType: string): Promise<ActorRecord> {
    return await this.sql.begin(async (tx) => {
      await tx`select pg_advisory_xact_lock(hashtextextended(${authUserId}, 0))`;

      const existing = await tx`
        select a.actor_id, a.status, a.revision
        from app.actor_auth_principals p
        join app.actors a on a.actor_id = p.actor_id
        where p.auth_user_id = ${authUserId}::uuid
        for update of p, a
      `;
      if (existing[0]) {
        await tx`
          update app.actor_auth_principals
          set last_seen_at = now(), principal_type = ${principalType}
          where auth_user_id = ${authUserId}::uuid
        `;
        await tx`
          update app.actors
          set last_seen_at = now(), updated_at = now()
          where actor_id = ${existing[0].actor_id}::uuid
        `;
        return actorFromRow(existing[0] as ActorRow);
      }

      const actorId = crypto.randomUUID();
      const created = await tx`
        insert into app.actors(actor_id, status, last_seen_at)
        values (${actorId}::uuid, 'ACTIVE', now())
        returning actor_id, status, revision
      `;
      await tx`
        insert into app.actor_auth_principals(
          auth_user_id,
          actor_id,
          principal_type,
          last_seen_at
        ) values (
          ${authUserId}::uuid,
          ${actorId}::uuid,
          ${principalType},
          now()
        )
      `;
      return actorFromRow(created[0] as ActorRow);
    });
  }

  async listWalletBindings(actorId: string): Promise<WalletBindingRecord[]> {
    const rows = await this.sql`
      select wallet_binding_id, actor_id, wallet_address, cluster, status, revision
      from app.wallet_bindings
      where actor_id = ${actorId}::uuid
      order by verified_at desc, created_at desc
    `;
    return rows.map((row) => bindingFromRow(row as BindingRow));
  }

  async issueWalletBindingChallenge(challenge: WalletBindingChallengeRecord): Promise<void> {
    await this.sql.begin(async (tx) => {
      const principal = await tx`
        select actor_id
        from app.actor_auth_principals
        where auth_user_id = ${challenge.authUserId}::uuid
        for update
      `;
      if (!principal[0] || principal[0].actor_id !== challenge.actorId) {
        throw new Error("wallet challenge principal mapping changed");
      }

      await tx`
        update app.wallet_binding_challenges
        set status = 'REVOKED', revoked_at = now()
        where auth_user_id = ${challenge.authUserId}::uuid
          and status = 'ISSUED'
      `;

      await tx`
        insert into app.wallet_binding_challenges(
          challenge_id,
          actor_id,
          auth_user_id,
          wallet_address,
          cluster,
          purpose,
          domain,
          message,
          message_sha256,
          nonce_hash,
          status,
          issued_at,
          expires_at
        ) values (
          ${challenge.challengeId}::uuid,
          ${challenge.actorId}::uuid,
          ${challenge.authUserId}::uuid,
          ${challenge.walletAddress},
          ${challenge.cluster},
          ${challenge.purpose},
          ${challenge.domain},
          ${challenge.message},
          ${challenge.messageSha256},
          ${challenge.nonceHash},
          'ISSUED',
          ${challenge.issuedAt},
          ${challenge.expiresAt}
        )
      `;
    });
  }

  async getWalletBindingChallenge(
    challengeId: string,
  ): Promise<WalletBindingChallengeRecord | null> {
    const rows = await this.sql`
      select
        challenge_id,
        actor_id,
        auth_user_id,
        wallet_address,
        cluster,
        purpose,
        domain,
        message,
        message_sha256,
        nonce_hash,
        status,
        issued_at,
        expires_at,
        consumed_at
      from app.wallet_binding_challenges
      where challenge_id = ${challengeId}::uuid
      limit 1
    `;
    return rows[0] ? challengeFromRow(rows[0] as ChallengeRow) : null;
  }

  async consumeWalletBindingChallenge(
    challengeId: string,
    authUserId: string,
    now: Date,
  ): Promise<ChallengeConsumptionResult> {
    return await this.sql.begin(async (tx) => {
      const challengeRows = await tx`
        select
          challenge_id,
          actor_id,
          auth_user_id,
          wallet_address,
          cluster,
          purpose,
          domain,
          message,
          message_sha256,
          nonce_hash,
          status,
          issued_at,
          expires_at,
          consumed_at
        from app.wallet_binding_challenges
        where challenge_id = ${challengeId}::uuid
        for update
      `;
      if (!challengeRows[0]) return { kind: "not_found" } as const;
      const challenge = challengeFromRow(challengeRows[0] as ChallengeRow);

      if (challenge.authUserId !== authUserId) return { kind: "actor_mismatch" } as const;
      if (challenge.status === "CONSUMED") return { kind: "consumed" } as const;
      if (challenge.status === "REVOKED") return { kind: "revoked" } as const;
      if (challenge.status === "EXPIRED" || challenge.expiresAt.getTime() <= now.getTime()) {
        await tx`
          update app.wallet_binding_challenges
          set status = 'EXPIRED'
          where challenge_id = ${challengeId}::uuid and status = 'ISSUED'
        `;
        return { kind: "expired" } as const;
      }

      const principalRows = await tx`
        select p.actor_id, a.status as actor_status
        from app.actor_auth_principals p
        join app.actors a on a.actor_id = p.actor_id
        where p.auth_user_id = ${authUserId}::uuid
        for update of p, a
      `;
      if (!principalRows[0] || principalRows[0].actor_id !== challenge.actorId) {
        return { kind: "actor_mismatch" } as const;
      }
      if (principalRows[0].actor_status === "DISABLED") {
        return { kind: "actor_disabled" } as const;
      }
      if (principalRows[0].actor_status === "RESTRICTED") {
        return { kind: "actor_restricted" } as const;
      }

      await tx`
        select pg_advisory_xact_lock(
          hashtextextended(${challenge.cluster + ":" + challenge.walletAddress}, 1)
        )
      `;

      const bindingRows = await tx`
        select
          w.wallet_binding_id,
          w.actor_id,
          w.wallet_address,
          w.cluster,
          w.status,
          w.revision,
          a.status as actor_status,
          a.revision as actor_revision
        from app.wallet_bindings w
        join app.actors a on a.actor_id = w.actor_id
        where w.cluster = ${challenge.cluster}
          and w.wallet_address = ${challenge.walletAddress}
        for update of w, a
      `;

      let targetActorId = challenge.actorId;
      let recovered = false;
      let binding: WalletBindingRecord;

      if (!bindingRows[0]) {
        const bindingId = crypto.randomUUID();
        const inserted = await tx`
          insert into app.wallet_bindings(
            wallet_binding_id,
            actor_id,
            wallet_address,
            cluster,
            status,
            verified_at,
            last_used_at
          ) values (
            ${bindingId}::uuid,
            ${challenge.actorId}::uuid,
            ${challenge.walletAddress},
            ${challenge.cluster},
            'ACTIVE',
            ${now},
            ${now}
          )
          returning wallet_binding_id, actor_id, wallet_address, cluster, status, revision
        `;
        binding = bindingFromRow(inserted[0] as BindingRow);
      } else {
        const row = bindingRows[0] as BindingRow;
        if (row.status !== "ACTIVE" || row.actor_status !== "ACTIVE") {
          return { kind: "binding_conflict" } as const;
        }

        targetActorId = row.actor_id;
        if (targetActorId !== challenge.actorId) {
          const activity = await tx.unsafe(
            `select (${PROTECTED_ACTOR_STATE_SQL}) as protected_state`,
            [challenge.actorId, authUserId],
          );
          if (activity[0]?.protected_state === true) {
            return { kind: "binding_conflict" } as const;
          }

          await tx`
            update app.actor_auth_principals
            set actor_id = ${targetActorId}::uuid, last_seen_at = ${now}
            where auth_user_id = ${authUserId}::uuid
              and actor_id = ${challenge.actorId}::uuid
          `;
          recovered = true;
        }

        const touched = await tx`
          update app.wallet_bindings
          set verified_at = ${now}, last_used_at = ${now}, revision = revision + 1
          where wallet_binding_id = ${row.wallet_binding_id}::uuid
          returning wallet_binding_id, actor_id, wallet_address, cluster, status, revision
        `;
        binding = bindingFromRow(touched[0] as BindingRow);
      }

      const actorRows = await tx`
        update app.actors
        set last_seen_at = ${now}, updated_at = ${now}
        where actor_id = ${targetActorId}::uuid
        returning actor_id, status, revision
      `;

      await tx`
        update app.wallet_binding_challenges
        set status = 'CONSUMED', consumed_at = ${now}
        where challenge_id = ${challengeId}::uuid
          and status = 'ISSUED'
      `;

      return {
        kind: "bound",
        actor: actorFromRow(actorRows[0] as ActorRow),
        binding,
        recovered,
      } as const;
    });
  }
}
