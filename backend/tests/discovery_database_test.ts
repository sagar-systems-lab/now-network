import postgres from "npm:postgres@3.4.7";
import { PostgresOpportunityRepository } from "../src/postgres-opportunity-repository.ts";
import { PostgresStateRepository } from "../src/postgres-state-repository.ts";
import { OpportunityMatcher } from "../src/opportunity-matcher.ts";
import { StateReadService } from "../src/state-read.ts";
import { ApiFault } from "../src/errors.ts";

const permission = await Deno.permissions.query({ name: "env", variable: "NOW_TEST_DB_URL" });
const database = permission.state === "granted" ? Deno.env.get("NOW_TEST_DB_URL") : undefined;

function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}

Deno.test({
  name: "discovery database: global filters, reward ordering and claim eligibility before paging",
  ignore: !database,
  fn: async () => {
    const sql = postgres(database!, { max: 1, prepare: false });
    const opportunities = new PostgresOpportunityRepository(database!);
    const states = new PostgresStateRepository(database!);
    const matcher = new OpportunityMatcher(opportunities);
    const now = new Date();
    const reader = new StateReadService(states, () => now);
    const actors = Array.from({ length: 3 }, () => crypto.randomUUID());
    const locations = Array.from({ length: 6 }, () => crypto.randomUUID());
    const stateIds = Array.from({ length: 6 }, () => crypto.randomUUID());
    const refreshes = Array.from({ length: 6 }, () => crypto.randomUUID());
    const run = crypto.randomUUID();
    const actor = { actorId: actors[0], status: "ACTIVE" as const, revision: 1 };
    const query = { lat: 12.3, lng: 55, radiusM: 3000, limit: 1, cursor: null };
    const policy = {
      template_key: "parking.available_spaces.v1",
      capture: { media_required: true, location_required: true },
    };
    try {
      for (const id of actors) {
        await sql`insert into app.actors(actor_id,status) values (${id}::uuid,'ACTIVE')`;
      }
      for (let i = 0; i < 6; i++) {
        await sql`insert into app.locations(location_id,name,location_type,center)
          values (${locations[i]}::uuid,${run + i},${i === 2 ? "SHOP" : "PARKING"},
            extensions.st_setsrid(extensions.st_makepoint(${55 + i * 0.001},12.3),4326)::extensions.geography)`;
        await sql`insert into app.state_definitions(state_id,version,canonical_key,title,question,
          state_type,answer_schema,freshness_policy,location_id,status)
          values (${stateIds[i]}::uuid,1,${stateIds[i]},${run + "-" + i},'Available spaces?',
            'NUMERIC','{}','{}',${locations[i]}::uuid,'ACTIVE')`;
        if (i !== 3) {
          const aging = new Date(now.getTime() + (i === 2 || i === 5 ? -60_000 : 60_000));
          const until = new Date(now.getTime() + (i === 2 ? -30_000 : 120_000));
          await sql`insert into app.live_states(state_id,state_version,current_value,observed_at,
            aging_at,fresh_until,verification_class,conflict_active)
            values (${stateIds[i]}::uuid,1,'2',${new Date(now.getTime() - 120_000)},
              ${aging},${until},'FAST',${i === 4})`;
        }
        const expiry = new Date(now.getTime() + (i === 5 ? -60_000 : (20 - i) * 60_000));
        const pool = [100, 1000, 900, 90000, 100, 100][i];
        await sql`insert into app.refresh_requests(refresh_id,state_id,state_version,
          requester_actor_id,status,verification_class,required_witnesses,max_witnesses,
          proof_policy_snapshot,proof_policy_digest,intent_core_hash,refresh_expires_at,
          evidence_deadline,reward_mint,chain_total_funded,payout_rule)
          values (${refreshes[i]}::uuid,${stateIds[i]}::uuid,1,
            ${i === 4 ? actors[0] : actors[1]}::uuid,'AVAILABLE','FAST',${i === 1 ? 2 : 1},
            ${i === 1 ? 2 : 1},${sql.json(policy)},decode(repeat('11',32),'hex'),
            decode(repeat('22',32),'hex'),${expiry},${expiry},${i === 3 ? "MintB" : "MintA"},
            ${pool},${i === 1 ? "EQUAL_SPLIT_REQUIRED_WITNESSES" : "SINGLE_WINNER_ALL"})`;
      }
      const first = await matcher.nearby(actor, query);
      assert(first.total === 4, "self-owned and expired requests must be excluded from total");
      assert(JSON.stringify(first.categories) === '["PARKING","SHOP"]', "category catalog is global");
      const ordered: string[] = [];
      let cursor: string | null = null;
      do {
        const page = await matcher.nearby(actor, { ...query, sort: "payout", cursor });
        const items = page.items as { refresh_id: string }[];
        ordered.push(...items.map((row) => row.refresh_id));
        cursor = page.next_cursor as string | null;
        assert(ordered.length <= 4, "payout paging did not advance");
      } while (cursor);
      assert(
        JSON.stringify(ordered) ===
          JSON.stringify([refreshes[2], refreshes[1], refreshes[0], refreshes[3]]),
        "payout order must use per-witness estimates and keep tokens separate",
      );
      const category = await matcher.nearby(actor, { ...query, category: "SHOP" });
      assert(category.total === 1, "category must filter before the page limit");
      const ending = await matcher.nearby(actor, { ...query, sort: "ending" });
      assert((ending.items as { refresh_id: string }[])[0].refresh_id === refreshes[3], "ending order");
      const all = await reader.nearby({ ...query, search: run });
      const counts = all.counts as { total: number; live: number; aging: number; stale: number };
      assert(
        counts.total === 6 && counts.live === 2 && counts.aging === 1 && counts.stale === 2,
        "area counts must include rows beyond the visible page and distinguish conflicts",
      );
      const stale = await reader.nearby({ ...query, search: run, freshness: "stale" });
      assert((stale.items as { state_id: string }[])[0].state_id === stateIds[2], "global freshness");
      const searched = await reader.nearby({ ...query, search: run + "-3" });
      assert((searched.items as { state_id: string }[])[0].state_id === stateIds[3], "global search");
      try {
        await reader.nearby({ ...query, search: run + "-3", cursor: all.next_cursor as string });
        throw new Error("query-mismatched cursor accepted");
      } catch (error) {
        assert(error instanceof ApiFault && error.code === "INVALID_CURSOR", "cursor validation");
      }
      await sql`insert into app.refresh_acceptances(acceptance_id,refresh_id,actor_id,wallet_address,
        claim_slot,claim_deadline,status)
        values (${crypto.randomUUID()}::uuid,${refreshes[2]}::uuid,${actors[2]}::uuid,
          'TestWallet',0,${new Date(now.getTime() + 60_000)},'CLAIMED')`;
      const filled = await matcher.nearby(actor, { ...query, category: "SHOP" });
      assert(filled.total === 0, "filled capacity must disappear from filtered totals");
    } finally {
      await opportunities.close();
      await states.close();
      await sql`delete from app.refresh_acceptances where refresh_id=any(${refreshes}::uuid[])`;
      await sql`delete from app.refresh_requests where refresh_id=any(${refreshes}::uuid[])`;
      await sql`delete from app.live_states where state_id=any(${stateIds}::uuid[])`;
      await sql`delete from app.state_definitions where state_id=any(${stateIds}::uuid[])`;
      await sql`delete from app.locations where location_id=any(${locations}::uuid[])`;
      await sql`delete from app.actors where actor_id=any(${actors}::uuid[])`;
      await sql.end({ timeout: 1 });
    }
  },
});
