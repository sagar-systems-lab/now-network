import postgres from "npm:postgres@3.4.7";
import { PostgresEvidenceUploadRepository } from "../src/postgres-evidence-upload-repository.ts";
import { EvidenceUploadService } from "../src/evidence-upload-service.ts";
import type { EvidenceObjectStorage } from "../src/evidence-object-storage.ts";
import { ApiFault } from "../src/errors.ts";

const permission = await Deno.permissions.query({ name: "env", variable: "NOW_TEST_DB_URL" });
const database = permission.state === "granted" ? Deno.env.get("NOW_TEST_DB_URL") : undefined;

Deno.test({
  name:
    "upload database: persisted links survive restart and partial media upload without overwriting",
  ignore: !database,
  fn: async () => {
    const sql = postgres(database!, { max: 1, prepare: false });
    const first = new PostgresEvidenceUploadRepository(database!);
    const restarted = new PostgresEvidenceUploadRepository(database!);
    const [requesterId, actorId, locationId, stateId, refreshId, acceptanceId, challengeId] = Array
      .from({ length: 7 }, () => crypto.randomUUID());
    const nonceBytes = new Uint8Array(32).fill(9);
    const nonce = btoa(String.fromCharCode(...nonceBytes)).replaceAll("+", "-").replaceAll("/", "_")
      .replace(/=+$/u, "");
    const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", nonceBytes));
    let now = new Date();
    let calls = 0;
    let uploaded = false;
    const storage: EvidenceObjectStorage = {
      createSignedUpload: (key) => {
        calls++;
        if (uploaded) return Promise.reject(new Error("The resource already exists"));
        return Promise.resolve({ signedUrl: `https://storage.invalid/${key}?token=immutable` });
      },
      inspectUploadedObject: () => Promise.reject(new Error("not part of authorization")),
    };
    const input = {
      actor: { actorId, status: "ACTIVE" as const, revision: 1 },
      challengeId,
      nonce,
      mediaMime: "image/jpeg",
    };
    try {
      await sql`insert into app.actors(actor_id,status) values (${requesterId}::uuid,'ACTIVE'),(${actorId}::uuid,'ACTIVE')`;
      await sql`insert into app.locations(location_id,name,location_type,center)
        values (${locationId}::uuid,'Upload regression','PARKING',extensions.st_setsrid(extensions.st_makepoint(77,28),4326)::extensions.geography)`;
      await sql`insert into app.state_definitions(state_id,version,canonical_key,title,question,state_type,answer_schema,freshness_policy,location_id,status)
        values (${stateId}::uuid,1,${stateId},'Upload regression','What does it look like?','VISUAL','{}','{}',${locationId}::uuid,'ACTIVE')`;
      await sql`insert into app.refresh_requests(refresh_id,state_id,state_version,requester_actor_id,status,verification_class,required_witnesses,max_witnesses,
        proof_policy_snapshot,proof_policy_digest,intent_core_hash,refresh_expires_at,evidence_deadline,reward_mint,chain_total_funded,payout_rule)
        values (${refreshId}::uuid,${stateId}::uuid,1,${requesterId}::uuid,'CAPTURE_IN_PROGRESS','FAST',1,1,
          '{"capture":{"video_required":true}}',decode(repeat('11',32),'hex'),decode(repeat('22',32),'hex'),
          ${new Date(now.getTime() + 600_000)},${new Date(
        now.getTime() + 500_000,
      )},'TestMint',1,'SINGLE_WINNER_ALL')`;
      await sql`insert into app.refresh_acceptances(acceptance_id,refresh_id,actor_id,wallet_address,claim_slot,claim_deadline,status)
        values (${acceptanceId}::uuid,${refreshId}::uuid,${actorId}::uuid,'TestWallet',0,${new Date(
        now.getTime() + 300_000,
      )},'CAPTURE_ACTIVE')`;
      await sql`insert into app.evidence_challenges(challenge_id,refresh_id,acceptance_id,actor_id,wallet_address,nonce_hash,status,issued_at,expires_at,policy_version)
        values (${challengeId}::uuid,${refreshId}::uuid,${acceptanceId}::uuid,${actorId}::uuid,'TestWallet',${hash},'ISSUED',${now},${new Date(
        now.getTime() + 300_000,
      )},1)`;
      const original = await new EvidenceUploadService(first, storage, () => now).authorize(input);
      uploaded = true;
      const replay = await new EvidenceUploadService(restarted, storage, () => now).authorize(
        input,
      );
      if (
        calls !== 2 || !replay.replayed || original.evidence_id !== replay.evidence_id ||
        JSON.stringify(original.upload) !== JSON.stringify(replay.upload) ||
        JSON.stringify(original.video_upload) !== JSON.stringify(replay.video_upload)
      ) {
        throw new Error("persisted immutable photo/video links were not replayed after restart");
      }
      const saved = await restarted.getContext(challengeId);
      const winner = await restarted.saveAuthorization({
        challengeId,
        actorId,
        evidenceId: String(original.evidence_id),
        observedAt: now,
        authorization: {
          signedUrl: "https://wrong.invalid",
          expiresAt: new Date(now.getTime() + 60_000).toISOString(),
        },
      });
      if (winner?.signedUrl !== saved?.authorization?.signedUrl) {
        throw new Error("concurrent writer replaced the winning credentials");
      }
      const outsider = await restarted.saveAuthorization({
        challengeId,
        actorId: requesterId,
        evidenceId: String(original.evidence_id),
        observedAt: now,
        authorization: saved!.authorization!,
      });
      if (outsider !== null) throw new Error("cross-actor cache mutation succeeded");
      now = new Date(now.getTime() + 300_001);
      try {
        await new EvidenceUploadService(restarted, storage, () => now).authorize(input);
        throw new Error("expired upload credentials were returned");
      } catch (error) {
        if (!(error instanceof ApiFault) || error.code !== "CHALLENGE_EXPIRED") throw error;
      }
    } finally {
      await first.close();
      await restarted.close();
      await sql`delete from app.evidence_challenges where challenge_id=${challengeId}::uuid`;
      await sql`delete from app.refresh_acceptances where acceptance_id=${acceptanceId}::uuid`;
      await sql`delete from app.refresh_requests where refresh_id=${refreshId}::uuid`;
      await sql`delete from app.state_definitions where state_id=${stateId}::uuid`;
      await sql`delete from app.locations where location_id=${locationId}::uuid`;
      await sql`delete from app.notification_deliveries where notification_id in
        (select notification_id from app.notifications where actor_id in (${actorId}::uuid,${requesterId}::uuid))`;
      await sql`delete from app.notifications where actor_id in (${actorId}::uuid,${requesterId}::uuid)`;
      // Preserve the actor referenced by the append-only authorization audit.
      await sql`delete from app.actors where actor_id in (${actorId}::uuid,${requesterId}::uuid)
        and not exists(select 1 from app.domain_events de where de.actor_id=app.actors.actor_id)`;
      await sql.end({ timeout: 1 });
    }
  },
});
