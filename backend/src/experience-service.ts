import postgres from "npm:postgres@3.4.7";
import type { AuthPrincipal } from "./auth.ts";
import type { ActorRecord } from "./identity-repository.ts";
import { ApiFault } from "./errors.ts";
import { optionalQueryInteger, requiredString, requiredUuid, UUID_PATTERN } from "./http.ts";
import { canonicalPayouts, DEFAULT_NOTIFICATION_PREFERENCES, notificationPreferences, sanitizeAvatarPng } from "./experience-contract.ts";

export interface ExperienceApi {
  publicRequest(request: Request): Promise<unknown | null>;
  assertSession(principal: AuthPrincipal): Promise<void>;
  privateRequest(request: Request, actor: ActorRecord, principal: AuthPrincipal): Promise<unknown | null>;
}

type StorageConfig = { url: string; serviceKey: string; evidenceBucket: string };
const iso = (date: Date | string) => new Date(date).toISOString();
const route = (request: Request) => new URL(request.url).pathname.split("/v1/").pop() ?? "";

async function body(request: Request, max = 32_768): Promise<Record<string, unknown>> {
  const reader = request.body?.getReader();
  if (!reader) throw new ApiFault(400, "INVALID_REQUEST", "A JSON body is required.");
  const decoder = new TextDecoder(); let text = "", size = 0;
  while (true) {
    const next = await reader.read(); if (next.done) break;
    size += next.value.byteLength;
    if (size > max) { await reader.cancel(); throw new ApiFault(413, "REQUEST_TOO_LARGE", "The request is too large."); }
    text += decoder.decode(next.value, { stream: true });
  }
  text += decoder.decode();
  try {
    const result = JSON.parse(text);
    if (result && typeof result === "object" && !Array.isArray(result)) return result;
  } catch { /* translated below */ }
  throw new ApiFault(400, "INVALID_REQUEST", "A JSON object is required.");
}

function idFromPath(value: string): string {
  if (!UUID_PATTERN.test(value)) throw new ApiFault(400, "INVALID_REQUEST", "Invalid identifier.");
  return value;
}

export class PostgresExperienceService implements ExperienceApi {
  private readonly sql: ReturnType<typeof postgres>;
  constructor(connectionString: string, private readonly storage: StorageConfig, private readonly pushConfigured = false) {
    this.sql = postgres(connectionString, { max: 2, prepare: false, idle_timeout: 20, connect_timeout: 10 });
  }

  async close(): Promise<void> { await this.sql.end({ timeout: 1 }); }

  async assertSession(principal: AuthPrincipal): Promise<void> {
    if (!principal.sessionId) return; // Older API clients can read; registering devices requires a verified session claim.
    const revoked = await this.sql`select 1 from app.installations where auth_user_id = ${principal.authUserId}::uuid
      and auth_session_id = ${principal.sessionId}::uuid and revoked_at is not null limit 1`;
    if (revoked.length) throw new ApiFault(401, "SESSION_REVOKED", "This device's access has been revoked. Recover with a linked wallet.");
  }

  async publicRequest(request: Request): Promise<unknown | null> {
    if (request.method !== "GET" || route(request) !== "areas") return null;
    const url = new URL(request.url), q = (url.searchParams.get("q") ?? "").trim();
    const filter = url.searchParams.get("filter") ?? "all";
    if (!["all", "live", "stale"].includes(filter)) throw new ApiFault(400,"INVALID_FILTER","Choose an area filter.");
    if (q.length > 80) throw new ApiFault(400, "INVALID_SEARCH", "Use a shorter search.");
    const limit = optionalQueryInteger(url, "limit", 30, 1, 50);
    const offset = optionalQueryInteger(url, "offset", 0, 0, 10_000);
    const rows = await this.sql`
      select l.location_id as area_id, l.name, l.display_address,
        extensions.st_y(l.center::extensions.geometry) as latitude,
        extensions.st_x(l.center::extensions.geometry) as longitude,
        counts.total::integer, counts.live::integer, counts.aging::integer,
        counts.stale::integer, counts.unobserved::integer
      from app.locations l
      cross join lateral (
        select count(*) as total,
          count(*) filter (where ls.observed_at is not null and now() < ls.aging_at and not ls.conflict_active) as live,
          count(*) filter (where now() >= ls.aging_at and now() < ls.fresh_until and not ls.conflict_active) as aging,
          count(*) filter (where ls.observed_at is not null and (now() >= ls.fresh_until or ls.conflict_active)) as stale,
          count(*) filter (where ls.observed_at is null) as unobserved
        from app.state_definitions sd join app.locations location on location.location_id = sd.location_id
        left join app.live_states ls on ls.state_id = sd.state_id
        where sd.status = 'ACTIVE' and extensions.st_dwithin(location.center,l.center,3000)
      ) counts
      where counts.total > 0 and (${filter}='all' or (${filter}='live' and counts.live>0) or (${filter}='stale' and counts.stale+counts.unobserved>0)) and (position(lower(${q}) in lower(l.name || ' ' || coalesce(l.display_address,''))) > 0)
      order by lower(l.name), l.location_id limit ${limit + 1} offset ${offset}`;
    return { items: rows.slice(0, limit).map((r) => ({ ...r, center: { latitude: Number(r.latitude), longitude: Number(r.longitude) }, radius_m: 3000 })),
      next_offset: rows.length > limit ? offset + limit : null };
  }

  async privateRequest(request: Request, actor: ActorRecord, principal: AuthPrincipal): Promise<unknown | null> {
    const path = route(request), method = request.method;
    const supported = /^(me\/(profile|activity|preferences|notifications|installations|payout-preferences|states)(\/.*)?|evidence\/[0-9a-f-]+\/preview)$/.test(path);
    if (!supported) return null;
    if (actor.status !== "ACTIVE") throw new ApiFault(403, "ACTOR_UNAVAILABLE", "Account access is restricted.");
    if (path === "me/profile") {
      if (method === "GET") return await this.profile(actor.actorId);
      if (method === "POST") {
        const input = await body(request), name = requiredString(input, "display_name", 64);
        if (/[\u0000-\u001f\u007f]/.test(name)) throw new ApiFault(400, "INVALID_NAME", "Remove control characters from your name.");
        await this.sql`insert into app.actor_profiles(actor_id,display_name) values (${actor.actorId}::uuid,${name})
          on conflict(actor_id) do update set display_name=excluded.display_name,updated_at=now(),revision=app.actor_profiles.revision+1`;
        return await this.profile(actor.actorId);
      }
    }
    if (path === "me/profile/avatar" && method === "POST") {
      const input = await body(request, 720_000);
      if (input.remove === true) {
        await this.sql`update app.actor_profiles set avatar_object_key=null,updated_at=now(),revision=revision+1 where actor_id=${actor.actorId}::uuid`;
        return await this.profile(actor.actorId);
      }
      const raw = requiredString(input, "png_base64", 700_000); let bytes: Uint8Array;
      try { bytes = Uint8Array.from(atob(raw), (c) => c.charCodeAt(0)); }
      catch { throw new ApiFault(400, "INVALID_AVATAR", "Choose a valid image."); }
      const sanitized = sanitizeAvatarPng(bytes), key = `${actor.actorId}/${crypto.randomUUID()}.png`;
      await this.storageRequest(`/object/now-avatars/${key}`, { method: "POST", headers: { "content-type": "image/png", "x-upsert": "false" }, body: sanitized.buffer as ArrayBuffer });
      await this.sql`insert into app.actor_profiles(actor_id,avatar_object_key) values (${actor.actorId}::uuid,${key})
        on conflict(actor_id) do update set avatar_object_key=excluded.avatar_object_key,updated_at=now(),revision=app.actor_profiles.revision+1`;
      return await this.profile(actor.actorId);
    }
    if (path === "me/preferences") {
      if (method === "GET") return await this.preferences(actor.actorId);
      if (method === "POST") {
        const prefs = notificationPreferences(await body(request));
        if (prefs.area_ids.length) {
          const found = await this.sql`select location_id from app.locations where location_id = any(${prefs.area_ids}::uuid[])`;
          if (found.length !== prefs.area_ids.length) throw new ApiFault(400, "INVALID_AREAS", "An area is no longer available.");
        }
        await this.sql`insert into app.actor_preferences(actor_id,notification_preferences) values (${actor.actorId}::uuid,${this.sql.json(prefs)})
          on conflict(actor_id) do update set notification_preferences=excluded.notification_preferences,updated_at=now(),revision=app.actor_preferences.revision+1`;
        return await this.preferences(actor.actorId);
      }
    }
    if (path === "me/payout-preferences" && method === "POST") {
      const input = await body(request), binding = requiredUuid(input, "wallet_binding_id");
      await this.sql.begin(async (tx) => {
        const matches = await tx`select wallet_binding_id from app.wallet_bindings where wallet_binding_id=${binding}::uuid and actor_id=${actor.actorId}::uuid and status='ACTIVE' for update`;
        if (!matches.length) throw new ApiFault(409, "WALLET_UNAVAILABLE", "Connect and verify this wallet first.");
        await tx`insert into app.actor_preferences(actor_id,payout_wallet_binding_id) values (${actor.actorId}::uuid,${binding}::uuid)
          on conflict(actor_id) do update set payout_wallet_binding_id=excluded.payout_wallet_binding_id,updated_at=now(),revision=app.actor_preferences.revision+1`;
      });
      return { wallet_binding_id: binding, applies_to: "FUTURE_CLAIMS_ONLY" };
    }
    if (path === "me/activity" && method === "GET") return await this.activity(actor.actorId, new URL(request.url));
    if (path === "me/notifications" && method === "GET") return await this.inbox(actor.actorId, new URL(request.url));
    if (path === "me/notifications/read" && method === "POST") {
      const input = await body(request);
      if (typeof input.through === "string") {
        const through = new Date(input.through);
        if (!Number.isFinite(through.getTime()) || through.getTime() > Date.now() + 1000) throw new ApiFault(400, "INVALID_SNAPSHOT", "Reload notifications before marking all read.");
        await this.sql`update app.notifications set read_at=now() where actor_id=${actor.actorId}::uuid and read_at is null and created_at <= ${through}`;
      } else {
        const id = requiredUuid(input, "notification_id");
        await this.sql`update app.notifications set read_at=coalesce(read_at,now()) where actor_id=${actor.actorId}::uuid and notification_id=${id}::uuid`;
      }
      return { saved: true };
    }
    if (path === "me/installations") {
      if (method === "GET") {
        const rows = await this.sql`select installation_id,device_name,app_version,created_at,last_seen_at,revoked_at,
          auth_session_id=${principal.sessionId ?? null}::uuid as current from app.installations where actor_id=${actor.actorId}::uuid order by last_seen_at desc limit 50`;
        return { items: rows };
      }
      if (method === "POST") {
        if (!principal.sessionId) throw new ApiFault(409, "SESSION_REGISTRATION_UNAVAILABLE", "Refresh your authentication session to register this device.");
        const input = await body(request), id = requiredUuid(input, "installation_id");
        const name = requiredString(input, "device_name", 100), version = requiredString(input, "app_version", 40);
        const token = typeof input.push_token === "string" && input.push_token.length <= 4096 ? input.push_token : null;
        await this.sql.begin(async (tx) => {
          const existing = await tx`select * from app.installations where installation_id=${id}::uuid or auth_session_id=${principal.sessionId!}::uuid for update`;
          if (existing.some((r) => r.revoked_at || r.auth_user_id !== principal.authUserId || r.auth_session_id !== principal.sessionId)) throw new ApiFault(409, "INSTALLATION_CONFLICT", "This installation cannot replace another device's access.");
          await tx`insert into app.installations(installation_id,actor_id,auth_user_id,auth_session_id,device_name,app_version,push_token,push_permission)
            values (${id}::uuid,${actor.actorId}::uuid,${principal.authUserId}::uuid,${principal.sessionId!}::uuid,${name},${version},${token},${input.push_permission === true})
            on conflict(auth_session_id) do update set actor_id=excluded.actor_id,device_name=excluded.device_name,app_version=excluded.app_version,
              push_token=coalesce(excluded.push_token,app.installations.push_token),push_permission=excluded.push_permission,last_seen_at=now()`;
        });
        return { installation_id: id, registered: true, push_available: this.pushConfigured };
      }
    }
    if (path === "me/installations/revoke" && method === "POST") {
      const input = await body(request), id = requiredUuid(input, "installation_id");
      const rows = await this.sql`update app.installations set revoked_at=coalesce(revoked_at,now()),push_token=null,push_permission=false
        where installation_id=${id}::uuid and actor_id=${actor.actorId}::uuid returning installation_id`;
      if (!rows.length) throw new ApiFault(404, "INSTALLATION_NOT_FOUND", "This device is not linked to your account.");
      return { revoked: true };
    }
    const stateProof = /^me\/states\/([0-9a-f-]+)\/proof$/.exec(path);
    if (stateProof && method === "GET") {
      const id = idFromPath(stateProof[1]);
      const rows = await this.sql`select ep.evidence_id,ep.media_object_key,ep.status::text,ep.committed_at from app.evidence_packets ep
        join app.refresh_requests rr on rr.refresh_id=ep.refresh_id
        where ep.state_id=${id}::uuid and ep.status='VERIFIED' and ep.media_object_key is not null
        and (ep.actor_id=${actor.actorId}::uuid or rr.requester_actor_id=${actor.actorId}::uuid or exists
          (select 1 from app.refresh_contributions c where c.refresh_id=ep.refresh_id and c.actor_id=${actor.actorId}::uuid and c.status in ('CONFIRMED','FINALIZED')))
        order by ep.committed_at desc,ep.evidence_id desc limit 1`;
      if (!rows.length) return { available: false };
      return { available: true, evidence_id: rows[0].evidence_id, status: rows[0].status, committed_at: iso(rows[0].committed_at),
        url: await this.signedUrl(this.storage.evidenceBucket,rows[0].media_object_key), expires_in: 60 };
    }
    const preview = /^evidence\/([0-9a-f-]+)\/preview$/.exec(path);
    if (preview && method === "GET") {
      const id = idFromPath(preview[1]);
      const rows = await this.sql`select ep.media_object_key from app.evidence_packets ep join app.refresh_requests rr on rr.refresh_id=ep.refresh_id
        where ep.evidence_id=${id}::uuid and ep.media_object_key is not null and ep.status in ('COMMITTED','VERIFYING','VERIFIED','CONFLICT','REJECTED')
        and (ep.actor_id=${actor.actorId}::uuid or rr.requester_actor_id=${actor.actorId}::uuid or exists
          (select 1 from app.refresh_contributions c where c.refresh_id=ep.refresh_id and c.actor_id=${actor.actorId}::uuid and c.status in ('CONFIRMED','FINALIZED'))) limit 1`;
      if (!rows.length) throw new ApiFault(404, "EVIDENCE_UNAVAILABLE", "This proof is private or unavailable.");
      return { url: await this.signedUrl(this.storage.evidenceBucket, rows[0].media_object_key), expires_in: 60 };
    }
    throw new ApiFault(405, "METHOD_NOT_ALLOWED", "This action is not supported.");
  }

  private async preferences(actorId: string) {
    const rows = await this.sql`select notification_preferences,payout_wallet_binding_id,revision from app.actor_preferences where actor_id=${actorId}::uuid`;
    return { notifications: rows[0]?.notification_preferences ?? DEFAULT_NOTIFICATION_PREFERENCES,
      payout_wallet_binding_id: rows[0]?.payout_wallet_binding_id ?? null,
      revision: Number(rows[0]?.revision ?? 0), push_available: this.pushConfigured };
  }

  private async profile(actorId: string) {
    const rows = await this.sql`select a.actor_id,a.created_at,coalesce(p.display_name,'') as display_name,p.avatar_object_key,
      coalesce(p.revision,0) as revision,
      (select count(*)::integer from app.evidence_packets e where e.actor_id=a.actor_id and e.status='VERIFIED') as verified_contributions,
      (select count(*)::integer from app.refresh_requests r where r.requester_actor_id=a.actor_id and r.status='COMPLETED') as completed_refreshes
      from app.actors a left join app.actor_profiles p on p.actor_id=a.actor_id where a.actor_id=${actorId}::uuid`;
    const row = rows[0]; if (!row) throw new ApiFault(404, "ACCOUNT_NOT_FOUND", "Account unavailable.");
    const { avatar_object_key, ...safe } = row;
    return { ...safe, avatar_url: avatar_object_key ? await this.signedUrl("now-avatars", avatar_object_key) : null,
      preferences: await this.preferences(actorId) };
  }

  private async inbox(actorId: string, url: URL) {
    const filter = url.searchParams.get("filter") ?? "all";
    if (!["all", "unread", "proof", "payments", "security", "opportunities"].includes(filter)) throw new ApiFault(400, "INVALID_FILTER", "Choose a notification category.");
    const offset = optionalQueryInteger(url, "offset", 0, 0, 10_000), limit = optionalQueryInteger(url, "limit", 30, 1, 50);
    const snapshot = url.searchParams.has("snapshot_at") ? new Date(url.searchParams.get("snapshot_at")!) : new Date();
    if (!Number.isFinite(snapshot.getTime()) || snapshot.getTime() > Date.now()+1000) throw new ApiFault(400,"INVALID_SNAPSHOT","Reload the inbox.");
    const rows = await this.sql`select notification_id,category,title,body,destination,entity_id,created_at,read_at from app.notifications
      where actor_id=${actorId}::uuid and (${filter}='all' or (${filter}='unread' and read_at is null) or category=${filter})
      and created_at <= ${snapshot} order by created_at desc,notification_id desc limit ${limit + 1} offset ${offset}`;
    const counts = await this.sql`select count(*)::integer as unread from app.notifications where actor_id=${actorId}::uuid and read_at is null`;
    return { items: rows.slice(0, limit), unread_count: counts[0].unread, snapshot_at: snapshot.toISOString(), next_offset: rows.length > limit ? offset + limit : null };
  }

  private async activity(actorId: string, url: URL) {
    const limit = optionalQueryInteger(url, "limit", 30, 1, 50), offset = optionalQueryInteger(url, "offset", 0, 0, 10_000);
    const refreshId = url.searchParams.get("refresh_id");
    if (refreshId != null) idFromPath(refreshId);
    const rows = await this.sql`select rr.refresh_id,rr.state_id,sd.title,rr.status::text as status,rr.updated_at,
        rr.reward_mint,coalesce(rr.chain_locked_reward,rr.chain_total_funded)::text as pool_atomic,
        ra.acceptance_id,ra.status as claim_status,r.receipt_id,r.status::text as receipt_status,
        so.status::text as payment_status,so.recipient_mask,so.recipient_wallets,so.locked_reward_atomic::text,
        rr.requester_actor_id=${actorId}::uuid as requester,
        (select coalesce(jsonb_agg(jsonb_build_object('slot',p.claim_slot,'wallet',p.wallet_address,'actor_id',p.actor_id) order by p.claim_slot),'[]'::jsonb)
          from app.refresh_acceptances p where p.refresh_id=rr.refresh_id and p.claim_slot is not null
          and p.wallet_address=any(so.recipient_wallets)) as recipients
      from app.refresh_requests rr join app.state_definitions sd on sd.state_id=rr.state_id
      left join app.refresh_acceptances ra on ra.refresh_id=rr.refresh_id and ra.actor_id=${actorId}::uuid
      left join app.receipts r on r.refresh_id=rr.refresh_id and r.status='FINAL'
      left join app.settlement_operations so on so.refresh_id=rr.refresh_id
      where (${refreshId}::uuid is null or rr.refresh_id=${refreshId}::uuid) and (rr.requester_actor_id=${actorId}::uuid or ra.actor_id=${actorId}::uuid or exists
        (select 1 from app.refresh_contributions c where c.refresh_id=rr.refresh_id and c.actor_id=${actorId}::uuid))
      order by rr.updated_at desc,rr.refresh_id desc limit ${limit + 1} offset ${offset}`;
    return { items: rows.slice(0, limit).map((row) => {
      let payout: string | null = null;
      const recipients = row.recipients as { slot: number; wallet: string; actor_id: string }[];
      if (row.receipt_status === "FINAL" && row.payment_status === "FINALIZED") {
        const authority = canonicalPayouts(BigInt(row.locked_reward_atomic), Number(row.recipient_mask), recipients);
        const own = new Set(recipients.filter((r) => r.actor_id === actorId).map((r) => r.wallet));
        payout = authority.filter((r) => own.has(r.wallet)).reduce((sum, r) => sum + BigInt(r.amount_atomic), 0n).toString();
      }
      return { refresh_id: row.refresh_id, state_id: row.state_id, title: row.title, status: row.status,
        updated_at: iso(row.updated_at), reward_mint: row.reward_mint, pool_atomic: row.pool_atomic,
        acceptance_id: row.acceptance_id, claim_status: row.claim_status, receipt_id: row.receipt_id,
        payment_status: row.payment_status, role: row.requester ? "REQUESTER" : row.acceptance_id ? "CONTRIBUTOR" : "FUNDER",
        payout_atomic: payout };
    }), next_offset: rows.length > limit ? offset + limit : null };
  }

  private async storageRequest(path: string, init: RequestInit): Promise<Response> {
    const response = await fetch(`${this.storage.url.replace(/\/$/, "")}/storage/v1${path}`, {
      ...init, headers: { ...init.headers, authorization: `Bearer ${this.storage.serviceKey}`, apikey: this.storage.serviceKey }, signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new ApiFault(503, "MEDIA_UNAVAILABLE", "Media is temporarily unavailable. Try again.", true);
    return response;
  }
  private async signedUrl(bucket: string, key: string): Promise<string> {
    const path = [bucket, ...key.split("/")].map(encodeURIComponent).join("/");
    const response = await this.storageRequest(`/object/sign/${path}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ expiresIn: 60 }) });
    const value = await response.json();
    if (typeof value.signedURL !== "string") throw new ApiFault(503, "MEDIA_UNAVAILABLE", "Media is temporarily unavailable.", true);
    return `${this.storage.url.replace(/\/$/, "")}/storage/v1${value.signedURL}`;
  }
}
