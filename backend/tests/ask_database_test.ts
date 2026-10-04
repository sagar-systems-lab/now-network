import postgres from "npm:postgres@3.4.7";
import { PostgresAskService } from "../src/ask-service.ts";
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
      const replay = await call(0, "asks/resolve", "POST", body, key);
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
      const definitions =
        await sql`select canonical_key,policy_template_key,version from app.state_definitions where location_id=any(${
          Array.from(locationIds)
        }::uuid[])`;
      assert(
        definitions.every((row) =>
          row.canonical_key !== row.policy_template_key &&
          row.policy_template_key === body.policy_template_key && row.version === 1
        ),
        "place and policy identities coupled",
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
