import postgres from "npm:postgres@3.4.7";
import { PostgresAskService } from "../src/ask-service.ts";
import { ASK_TEMPLATES } from "../src/ask-contract.ts";
import { policyTemplateForKey } from "../../packages/policy/src/registry.ts";
import { canonicalJson } from "../../packages/policy/src/template.ts";
import { ApiFault } from "../src/errors.ts";
const permission = await Deno.permissions.query({ name: "env", variable: "NOW_TEST_DB_URL" });
const database = permission.state === "granted" ? Deno.env.get("NOW_TEST_DB_URL") : undefined;
function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}
async function rejects(block: () => Promise<unknown>, code: string) {
  try {
    await block();
  } catch (e) {
    assert(e instanceof ApiFault && e.code === code, `expected ${code}, got ${e}`);
    return;
  }
  throw new Error(`expected ${code}`);
}
Deno.test({
  name: "ASK database: geographic exclusion, TTL, privacy, concurrent identity and replay",
  ignore: !database,
  fn: async () => {
    const sql = postgres(database!, { max: 1, prepare: false });
    const service = new PostgresAskService(database!),
      concurrent = new PostgresAskService(database!);
    const actors = Array.from(
      { length: 8 },
      () => ({ actorId: crypto.randomUUID(), status: "ACTIVE" as const, revision: 1 }),
    );
    const locationIds = new Set<string>();
    async function call(
      index: number,
      path: string,
      method = "GET",
      body?: unknown,
      key?: string,
      other = service,
    ) {
      return await other.request(
        new Request(`https://now.test/v1/${path}`, {
          method,
          headers: key ? { "Idempotency-Key": key } : {},
          body: body === undefined ? undefined : JSON.stringify(body),
        }),
        actors[index],
      ) as Record<string, unknown>;
    }
    function coverageQuery() {
      return "coverage?" +
        new URLSearchParams({
          lat: "28",
          lng: "77",
          requester_lat: "28",
          requester_lng: "77",
          requester_accuracy_m: "0",
          requester_captured_at: new Date().toISOString(),
        });
    }
    try {
      for (
        const a of actors
      ) await sql`insert into app.actors(actor_id,status) values(${a.actorId}::uuid,'ACTIVE')`;
      // Own presence and four cardinal directions inside 100 m must not count.
      for (let i = 0; i < 8; i++) {
        const distance = i === 0 ? 400 : i <= 4 ? 80 : i === 5 ? 150 : 800;
        await sql`insert into app.contributor_presence(actor_id,center,accuracy_m,coverage_radius_m,available,heartbeat_at,expires_at)
        values(${
          actors[i].actorId
        }::uuid,extensions.st_project(extensions.st_setsrid(extensions.st_makepoint(77,28),4326)::extensions.geography,${distance}::double precision,${
          i * Math.PI / 2
        }::double precision),0,2000,true,now(),now()+interval '180 seconds')`;
      }
      await sql`update app.contributor_presence set heartbeat_at=now()-interval '181 seconds',expires_at=now()-interval '1 second' where actor_id=${
        actors[6].actorId
      }::uuid`;
      await sql`update app.actors set status='RESTRICTED' where actor_id=${
        actors[7].actorId
      }::uuid`;
      let result = await call(0, coverageQuery());
      assert(result.active_contributors === 1, "nearby, own, stale or restricted presence counted");
      const responseKeys = Object.keys(result).sort().join();
      assert(
        responseKeys ===
          "active_contributors,checked_at,coverage_available,exclusion_radius_m,status",
        "coverage exposed private data",
      );
      result = await call(0, "coverage?lat=28&lng=77");
      assert(
        result.status === "LOCATION_REQUIRED" && result.active_contributors === 0,
        "missing origin leaked unfiltered count",
      );
      await call(5, "me/contributor-presence", "DELETE");
      assert((await call(0, coverageQuery())).active_contributors === 0, "OFF presence counted");
      await call(5, "me/contributor-presence", "PUT", {
        available: true,
        lat: 28.005,
        lng: 77,
        accuracy_m: 10,
      });
      assert(
        (await call(0, coverageQuery())).active_contributors === 1,
        "eligible contributor missing",
      );
      assert(
        (await call(0, coverageQuery().replace("lat=28&", "lat=29&"))).active_contributors === 0,
        "out-of-radius target covered",
      );
      await rejects(() =>
        call(5, "me/contributor-presence", "PUT", {
          available: true,
          lat: 28,
          lng: 77,
          accuracy_m: 101,
        }), "INVALID_REQUEST");
      await rejects(
        () =>
          service.request(new Request("https://now.test/v1/coverage?lat=28&lng=77"), {
            ...actors[0],
            status: "RESTRICTED",
          }),
        "ACTOR_RESTRICTED",
      );
      await rejects(() => call(0, "asks/resolve", "POST", {}), "INVALID_REQUEST");
      const body = {
        location: {
          name: `Test lot ${actors[0].actorId}`,
          location_type: "PARKING",
          lat: 28,
          lng: 77,
        },
        policy_template_key: "parking.available_spaces.v1",
      };
      const key = crypto.randomUUID();
      const pair = await Promise.all([
        call(0, "asks/resolve", "POST", body, key),
        call(1, "asks/resolve", "POST", body, crypto.randomUUID(), concurrent),
      ]);
      for (const resolved of pair) {
        locationIds.add(String(resolved.location_id));
      }
      assert(
        pair[0].state_id === pair[1].state_id && locationIds.size === 1,
        "concurrent resolution duplicated state/location",
      );
      const replay = await call(0, "asks/resolve", "POST", body, key, concurrent);
      assert(
        typeof replay === "object" && replay !== null,
        "ASK replay returned encoded JSON text",
      );
      assert(
        canonicalJson(replay) === canonicalJson(pair[0]),
        "timeout retry changed the result",
      );
      await rejects(() =>
        call(0, "asks/resolve", "POST", {
          ...body,
          location: { ...body.location, name: "Changed" },
        }, key), "IDEMPOTENCY_CONFLICT");
      const remote = await call(0, "asks/resolve", "POST", {
        ...body,
        location: { ...body.location, lat: 29 },
      }, crypto.randomUUID());
      locationIds.add(String(remote.location_id));
      assert(remote.state_id !== pair[0].state_id, "separate place reused another state");
      const sameCellOtherName = await call(0, "asks/resolve", "POST", {
        ...body,
        location: { ...body.location, name: body.location.name + " B" },
      }, crypto.randomUUID());
      locationIds.add(String(sameCellOtherName.location_id));
      assert(sameCellOtherName.state_id !== pair[0].state_id, "adjacent named lots collapsed");
      for (const templateKey of ["gate.open_closed.v1", "visual.current_condition.v1"] as const) {
        const template = ASK_TEMPLATES[templateKey];
        const draft = {
          location: { ...body.location, location_type: template.type },
          policy_template_key: templateKey,
        };
        const draftKey = crypto.randomUUID();
        const resolved = await call(2, "asks/resolve", "POST", draft, draftKey);
        locationIds.add(String(resolved.location_id));
        const retried = await call(2, "asks/resolve", "POST", draft, draftKey, concurrent);
        assert(canonicalJson(retried) === canonicalJson(resolved), `${templateKey} replay changed`);
      }
      const definitions =
        await sql`select canonical_key,policy_template_key,version,answer_schema,freshness_policy from app.state_definitions where location_id=any(${
          Array.from(locationIds)
        }::uuid[])`;
      assert(
        definitions.length === 5,
        "registered ASK templates did not persist their states",
      );
      for (const row of definitions) {
        const templateKey = row.policy_template_key as keyof typeof ASK_TEMPLATES;
        const template = ASK_TEMPLATES[templateKey];
        const policy = policyTemplateForKey(templateKey);
        assert(template && policy, "unregistered ASK template persisted");
        assert(
          row.canonical_key !== templateKey && row.version === 1,
          "place and policy identities coupled",
        );
        assert(
          canonicalJson(row.answer_schema) === canonicalJson(template.answer),
          `${templateKey} answer schema was not stored as JSON`,
        );
        assert(
          canonicalJson(row.freshness_policy) === canonicalJson({
            fresh_ttl_seconds: policy.fresh_ttl_seconds,
            aging_ratio: policy.aging_ratio,
          }),
          `${templateKey} freshness policy was not stored as JSON`,
        );
      }
      const responses = await sql`select response_body from app.idempotency_records
        where actor_id=any(${
        actors.map((a) => a.actorId)
      }::uuid[]) and operation_type='ASK_RESOLVE_V1'`;
      assert(
        responses.length === 6 &&
          responses.every((row) =>
            typeof row.response_body === "object" && row.response_body?.state_id &&
            typeof row.response_body.coverage === "object"
          ),
        "ASK responses were not stored as JSON objects",
      );
      const secure =
        await sql`select relrowsecurity from pg_class where oid in ('app.contributor_presence'::regclass,'app.ask_rate_limits'::regclass)`;
      assert(
        secure.length === 2 && secure.every((row) => row.relrowsecurity),
        "RLS missing",
      );
      for (const role of ["anon", "authenticated"]) {
        const grants =
          await sql`select has_table_privilege(${role},'app.contributor_presence','SELECT,INSERT,UPDATE,DELETE') as access`;
        assert(!grants[0].access, "raw presence granted to clients");
      }
      await service.pruneExpired();
      const expired =
        await sql`select count(*)::integer as count from app.contributor_presence where actor_id=${
          actors[6].actorId
        }::uuid`;
      assert(expired[0].count === 0, "expired coordinate retained");
      // Cover a minute rollover between seeding the counter and sending the request.
      await sql`insert into app.ask_rate_limits(actor_id,operation,bucket_start,requests)
      select ${actors[0].actorId}::uuid,'resolve',bucket,10
      from generate_series(date_trunc('minute',now()),date_trunc('minute',now())+interval '1 minute',interval '1 minute') as bucket
      on conflict(actor_id,operation,bucket_start) do update set requests=10`;
      await rejects(
        () => call(0, "asks/resolve", "POST", body, crypto.randomUUID()),
        "RATE_LIMITED",
      );
      assert(
        (await call(0, "asks/resolve", "POST", body, key)).state_id === pair[0].state_id,
        "rate limit blocked exact replay",
      );
    } finally {
      await service.close();
      await concurrent.close();
      const ids = actors.map((a) => a.actorId);
      const created =
        await sql`select operation_id from app.idempotency_records where actor_id=any(${ids}::uuid[]) and operation_type='ASK_RESOLVE_V1'`;
      await sql`delete from app.idempotency_records where actor_id=any(${ids}::uuid[])`;
      await sql`delete from app.state_definitions where state_id=any(${
        created.map((row) => String(row.operation_id))
      }::uuid[])`;
      await sql`delete from app.locations where location_id=any(${
        Array.from(locationIds)
      }::uuid[])`;
      await sql`delete from app.actors where actor_id=any(${ids}::uuid[])`;
      await sql.end({ timeout: 1 });
    }
  },
});

Deno.test({
  name: "ASK database: custom questions persist, reuse only matching questions and replay",
  ignore: !database,
  fn: async () => {
    const sql = postgres(database!, { max: 1, prepare: false });
    const service = new PostgresAskService(database!);
    const actor = { actorId: crypto.randomUUID(), status: "ACTIVE" as const, revision: 1 };
    let locationId: string | null = null;
    const resolve = (body: unknown, key = crypto.randomUUID()) =>
      service.request(
        new Request("https://now.test/v1/asks/resolve", {
          method: "POST",
          headers: { "Idempotency-Key": key },
          body: JSON.stringify(body),
        }),
        actor,
      ) as Promise<Record<string, unknown>>;
    try {
      await sql`insert into app.actors(actor_id,status) values(${actor.actorId}::uuid,'ACTIVE')`;
      const base = {
        location: {
          name: `Entrance ${actor.actorId}`,
          location_type: "PLACE",
          lat: 28.55,
          lng: 77.25,
        },
        policy_template_key: "visual.current_condition.v1",
      };
      const standard = await resolve(base);
      locationId = String(standard.location_id);
      const key = crypto.randomUUID();
      const request = { ...base, custom_question: "How busy is the main entrance?" };
      const custom = await resolve(request, key);
      const repeated = await resolve({
        ...base,
        custom_question: "  how busy is the main entrance?  ",
      });
      const other = await resolve({
        ...base,
        custom_question: "Is water collecting at the entrance?",
      });
      assert(
        custom.state_id !== standard.state_id && other.state_id !== custom.state_id,
        "different questions shared a state",
      );
      assert(
        repeated.state_id === custom.state_id && repeated.state_reused === true,
        "equivalent question was not reused",
      );
      assert(
        canonicalJson(await resolve(request, key)) === canonicalJson(custom),
        "custom question retry changed",
      );
      const rows =
        await sql`select question,state_type,policy_template_key,answer_schema,ask_question_key
        from app.state_definitions where state_id=${String(custom.state_id)}::uuid`;
      assert(
        rows[0].question === request.custom_question && rows[0].state_type === "VISUAL" &&
          rows[0].policy_template_key === "visual.current_condition.v1" &&
          rows[0].answer_schema.type === "string" &&
          rows[0].ask_question_key.length === 64,
        "custom question lost its registered proof policy",
      );
    } finally {
      await service.close();
      await sql`delete from app.idempotency_records where actor_id=${actor.actorId}::uuid`;
      await sql`delete from app.state_definitions where created_by_actor_id=${actor.actorId}::uuid`;
      if (locationId) await sql`delete from app.locations where location_id=${locationId}::uuid`;
      await sql`delete from app.actors where actor_id=${actor.actorId}::uuid`;
      await sql.end({ timeout: 1 });
    }
  },
});
