import postgres from "npm:postgres@3.4.7";
import { ApiFault } from "./errors.ts";
import { readJsonObject, requiredQueryNumber } from "./http.ts";
import type { ActorRecord } from "./identity-repository.ts";
import {
  ASK_CONFIG,
  ASK_TEMPLATES,
  coveragePayload,
  locationFingerprint,
  numberField,
  parseAsk,
  type Point,
  point,
  questionFingerprint,
  type RequesterLocation,
  requesterLocation,
} from "./ask-contract.ts";
import { policyTemplateForKey } from "../../packages/policy/src/registry.ts";
import { canonicalJson } from "../../packages/policy/src/template.ts";

export interface AskApi {
  request(request: Request, actor: ActorRecord): Promise<unknown | null>;
}
type Sql = ReturnType<typeof postgres>;
type Tx = postgres.TransactionSql;

export class PostgresAskService implements AskApi {
  private readonly sql: Sql;
  constructor(connection: string) {
    this.sql = postgres(connection, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }
  async close() {
    await this.sql.end({ timeout: 1 });
  }
  async pruneExpired(): Promise<void> {
    await this.sql`delete from app.contributor_presence where expires_at<=now()`;
    await this.sql`delete from app.ask_rate_limits where bucket_start<now()-interval '2 days'`;
  }

  async request(request: Request, actor: ActorRecord): Promise<unknown | null> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/^.*\/v1\//u, "");
    if (!["me/contributor-presence", "coverage", "asks/resolve"].includes(path)) return null;
    if (actor.status !== "ACTIVE") {
      throw new ApiFault(403, "ACTOR_RESTRICTED", "This account cannot use discovery.");
    }
    if (path === "me/contributor-presence" && request.method === "DELETE") {
      await this.sql`delete from app.contributor_presence where actor_id=${actor.actorId}::uuid`;
      return { available: false };
    }
    if (path === "me/contributor-presence" && request.method === "PUT") {
      const body = await this.body(request);
      if (body.available === false) {
        await this.sql`delete from app.contributor_presence where actor_id=${actor.actorId}::uuid`;
        return { available: false };
      }
      if (body.available !== true) {
        throw new ApiFault(400, "INVALID_REQUEST", "Explicit availability is required.");
      }
      const position = point(body);
      const accuracy = numberField(body, "accuracy_m", 0, ASK_CONFIG.maxAccuracyM);
      const radius = body.coverage_radius_m === undefined
        ? ASK_CONFIG.coverageRadiusM
        : numberField(body, "coverage_radius_m", ASK_CONFIG.minRadiusM, ASK_CONFIG.maxRadiusM);
      return await this.sql.begin(async (tx) => {
        await this.rate(tx, actor.actorId, "presence", ASK_CONFIG.presencePerMinute);
        const rows =
          await tx`insert into app.contributor_presence(actor_id,center,accuracy_m,coverage_radius_m,available,heartbeat_at,expires_at)
          values (${actor.actorId}::uuid,extensions.st_setsrid(extensions.st_makepoint(${position.lng},${position.lat}),4326)::extensions.geography,
          ${accuracy},${radius},true,now(),now()+${ASK_CONFIG.presenceTtlSeconds}*interval '1 second')
          on conflict(actor_id) do update set center=excluded.center,accuracy_m=excluded.accuracy_m,
          coverage_radius_m=excluded.coverage_radius_m,available=true,heartbeat_at=excluded.heartbeat_at,
          expires_at=excluded.expires_at,revision=app.contributor_presence.revision+1
          returning expires_at,revision`;
        // Expired positions are disposable; no movement history is retained.
        await tx`delete from app.contributor_presence where expires_at <= now()`;
        return {
          available: true,
          expires_at: rows[0].expires_at,
          revision: Number(rows[0].revision),
          heartbeat_seconds: ASK_CONFIG.heartbeatSeconds,
          coverage_radius_m: radius,
        };
      });
    }
    if (path === "coverage" && request.method === "GET") {
      const target = {
        lat: requiredQueryNumber(url, "lat", -90, 90),
        lng: requiredQueryNumber(url, "lng", -180, 180),
      };
      const origin = url.searchParams.has("requester_lat")
        ? requesterLocation({
          lat: requiredQueryNumber(url, "requester_lat", -90, 90),
          lng: requiredQueryNumber(url, "requester_lng", -180, 180),
          accuracy_m: requiredQueryNumber(url, "requester_accuracy_m", 0, ASK_CONFIG.maxAccuracyM),
          captured_at: url.searchParams.get("requester_captured_at"),
        }, new Date())
        : null;
      return await this.sql.begin(async (tx) => {
        await this.rate(tx, actor.actorId, "coverage", ASK_CONFIG.coveragePerMinute);
        return await this.coverage(tx, actor.actorId, target, origin);
      });
    }
    if (path === "asks/resolve" && request.method === "POST") {
      const raw = await this.body(request);
      const key = request.headers.get("idempotency-key")?.trim() ?? "";
      if (!/^[\w-]{16,128}$/u.test(key)) {
        throw new ApiFault(400, "INVALID_REQUEST", "A durable Idempotency-Key is required.");
      }
      // Hash the exact submitted payload before location-age validation: a timeout replay must
      // still succeed after its original GPS sample ages out.
      const hash = new Uint8Array(
        await crypto.subtle.digest("SHA-256", new TextEncoder().encode(canonicalJson(raw))),
      );
      return await this.sql.begin(async (tx) => {
        const storedKey = `${actor.actorId}:ask:resolve:v1:${key}`;
        await tx`select pg_advisory_xact_lock(hashtextextended(${storedKey},14))`;
        const replay =
          await tx`select request_hash,response_body from app.idempotency_records where idempotency_key=${storedKey}`;
        if (replay[0]) {
          if (Array.from(replay[0].request_hash as Uint8Array).join() !== Array.from(hash).join()) {
            throw new ApiFault(
              409,
              "IDEMPOTENCY_CONFLICT",
              "This key belongs to a different ASK draft.",
            );
          }
          return replay[0].response_body;
        }
        const input = parseAsk(raw, new Date());
        const questionKey = await questionFingerprint(input.custom_question);
        const template = ASK_TEMPLATES[input.policy_template_key as keyof typeof ASK_TEMPLATES];
        const policy = policyTemplateForKey(input.policy_template_key)!;
        await this.rate(tx, actor.actorId, "resolve", ASK_CONFIG.resolvesPerMinute);
        const fingerprint = locationFingerprint(input.location);
        await tx`select pg_advisory_xact_lock(hashtextextended(${fingerprint},15))`;
        let matches =
          await tx`select location_id from app.locations where ask_fingerprint=${fingerprint}`;
        if (!matches[0]) {
          matches = await tx`select location_id from app.locations where ask_fingerprint is null
            and lower(regexp_replace(btrim(name),'\\s+',' ','g'))=${input.location.name.toLowerCase()}
            and location_type=${input.location.location_type}
            and floor(extensions.st_y(center::extensions.geometry)*100000+0.5)=${
            Math.round(input.location.lat * 100000)
          }
            and floor(extensions.st_x(center::extensions.geometry)*100000+0.5)=${
            Math.round(input.location.lng * 100000)
          }
            and extensions.st_dwithin(center,extensions.st_setsrid(extensions.st_makepoint(${input.location.lng},${input.location.lat}),4326)::extensions.geography,2)
            order by location_id limit 1 for update`;
          if (matches[0]) {
            await tx`update app.locations set ask_fingerprint=${fingerprint} where location_id=${
              matches[0].location_id
            }::uuid`;
          }
        }
        let locationId: string;
        if (matches[0]) {
          locationId = String(matches[0].location_id);
          if (input.location.display_address) {
            await tx`update app.locations set
              display_address=coalesce(display_address,${input.location.display_address})
              where location_id=${locationId}::uuid`;
          }
        } else {
          await this.rate(tx, actor.actorId, "new_location", ASK_CONFIG.locationsPerDay, 86400);
          locationId = crypto.randomUUID();
          await tx`insert into app.locations(location_id,name,location_type,center,display_address,ask_fingerprint)
            values (${locationId}::uuid,${input.location.name},${input.location.location_type},
            extensions.st_setsrid(extensions.st_makepoint(${input.location.lng},${input.location.lat}),4326)::extensions.geography,
            ${input.location.display_address},${fingerprint})`;
        }
        const states =
          await tx`select state_id,status from app.state_definitions where location_id=${locationId}::uuid
          and policy_template_key=${input.policy_template_key} and ask_question_key=${questionKey} and version=1`;
        let stateId: string;
        if (states[0]) {
          if (states[0].status !== "ACTIVE") {
            throw new ApiFault(
              409,
              "STATE_UNAVAILABLE",
              "This state is not available for requests.",
            );
          }
          stateId = String(states[0].state_id);
        } else {
          await this.rate(tx, actor.actorId, "new_state", ASK_CONFIG.statesPerDay, 86400);
          stateId = crypto.randomUUID();
          await tx`insert into app.state_definitions(state_id,version,canonical_key,policy_template_key,ask_question_key,title,question,
            state_type,answer_schema,unit_code,freshness_policy,location_id,status,created_by_actor_id)
            values (${stateId}::uuid,1,${
            input.policy_template_key + ":" + locationId + (questionKey ? ":" + questionKey : "")
          },${input.policy_template_key},${questionKey},
            ${
            input.location.name + " · " +
            (input.custom_question ? "On-site update" : template.title)
          },${input.custom_question ?? template.question},${policy.state_type},
            ${JSON.stringify(template.answer)}::text::jsonb,${template.unit},
            ${
            JSON.stringify({
              fresh_ttl_seconds: policy.fresh_ttl_seconds,
              aging_ratio: policy.aging_ratio,
            })
          }::text::jsonb,
            ${locationId}::uuid,'ACTIVE',${actor.actorId}::uuid)`;
        }
        const active =
          await tx`select refresh_id from app.refresh_requests where state_id=${stateId}::uuid
          and status not in ('COMPLETED','CANCELLED','EXPIRED','FAILED') and refresh_expires_at>now()
          order by created_at desc limit 1`;
        const result = {
          location_id: locationId,
          state_id: stateId,
          state_reused: states.length > 0,
          active_refresh_id: active[0]?.refresh_id ?? null,
          coverage: await this.coverage(
            tx,
            actor.actorId,
            input.location,
            input.requester_location,
          ),
        };
        await tx`insert into app.idempotency_records(idempotency_key,actor_id,operation_type,request_hash,status,response_code,response_body,operation_id,expires_at)
          values (${storedKey},${actor.actorId}::uuid,'ASK_RESOLVE_V1',${hash},'COMPLETED',200,${
          JSON.stringify(result)
        }::text::jsonb,
          ${stateId}::uuid,now()+interval '7 days')`;
        return result;
      });
    }
    throw new ApiFault(405, "METHOD_NOT_ALLOWED", "This method is not supported.");
  }

  private async body(request: Request) {
    const text = await request.text();
    if (text.length > 4096) {
      throw new ApiFault(413, "INVALID_REQUEST", "The ASK payload is too large.");
    }
    return await readJsonObject(new Request("https://now.invalid", { method: "POST", body: text }));
  }

  private async coverage(tx: Tx, actorId: string, target: Point, origin: RequesterLocation | null) {
    if (!origin) return coveragePayload(0, new Date(), false);
    const rows = await tx`select count(*)::integer as count from app.contributor_presence p
      join app.actors a on a.actor_id=p.actor_id
      where p.available and p.expires_at>now() and a.status='ACTIVE' and p.actor_id<>${actorId}::uuid
      and p.accuracy_m<=${ASK_CONFIG.maxAccuracyM}
      and extensions.st_dwithin(p.center,extensions.st_setsrid(extensions.st_makepoint(${target.lng},${target.lat}),4326)::extensions.geography,${ASK_CONFIG.maxRadiusM})
      and extensions.st_dwithin(p.center,extensions.st_setsrid(extensions.st_makepoint(${target.lng},${target.lat}),4326)::extensions.geography,p.coverage_radius_m)
      and not extensions.st_dwithin(p.center,extensions.st_setsrid(extensions.st_makepoint(${origin.lng},${origin.lat}),4326)::extensions.geography,
        ${ASK_CONFIG.exclusionRadiusM}::double precision+${origin.accuracy_m}::double precision+p.accuracy_m)`;
    return coveragePayload(Number(rows[0].count), new Date(), true);
  }

  private async rate(tx: Tx, actorId: string, operation: string, limit: number, seconds = 60) {
    const bucket = Math.floor(Date.now() / (seconds * 1000)) * seconds;
    const rows = await tx`insert into app.ask_rate_limits(actor_id,operation,bucket_start,requests)
      values (${actorId}::uuid,${operation},to_timestamp(${bucket}),1)
      on conflict(actor_id,operation,bucket_start) do update set requests=app.ask_rate_limits.requests+1
      returning requests`;
    if (Number(rows[0].requests) > limit) {
      throw new ApiFault(
        429,
        "RATE_LIMITED",
        "Please wait before trying again.",
        true,
        seconds * 1000,
      );
    }
    await tx`delete from app.ask_rate_limits where actor_id=${actorId}::uuid and bucket_start<now()-interval '2 days'`;
  }
}
