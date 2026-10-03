import postgres from "npm:postgres@3.4.7";
import { PostgresExperienceService } from "../src/experience-service.ts";
import { PostgresNotificationRunner } from "../src/notification-delivery.ts";
import { DEFAULT_NOTIFICATION_PREFERENCES } from "../src/experience-contract.ts";
import { ApiFault } from "../src/errors.ts";
import type { AuthPrincipal } from "../src/auth.ts";

const envPermission = await Deno.permissions.query({ name: "env", variable: "NOW_TEST_DB_URL" });
const database = envPermission.state === "granted" ? Deno.env.get("NOW_TEST_DB_URL") : undefined;
function assert(value: unknown, message: string): asserts value { if (!value) throw new Error(message); }
async function rejects(block: () => Promise<unknown>, code: string) {
  try { await block(); } catch (error) { assert(error instanceof ApiFault && error.code === code, `expected ${code}, received ${error}`); return; }
  throw new Error(`expected ${code}`);
}

Deno.test({ name: "experience database: actor isolation, live area filters, revocation and durable push delivery", ignore: !database,
  fn: async () => {
    const sql = postgres(database!, { max: 1, prepare: false });
    const service = new PostgresExperienceService(database!, { url: "https://storage.invalid", serviceKey: "test-only", evidenceBucket: "now-evidence" }, true);
    const sent: string[] = [];
    const runner = new PostgresNotificationRunner(database!, { send: async (_token, id) => { sent.push(id); return "sent"; } });
    const actorIds = [crypto.randomUUID(), crypto.randomUUID()];
    const locations = [crypto.randomUUID(), crypto.randomUUID()], states = [crypto.randomUUID(), crypto.randomUUID()];
    const installations = [crypto.randomUUID(), crypto.randomUUID()];
    const walletIds = [crypto.randomUUID(), crypto.randomUUID()];
    const principals: AuthPrincipal[] = actorIds.map(() => ({ authUserId: crypto.randomUUID(), sessionId: crypto.randomUUID(), principalType: "SUPABASE_ANONYMOUS" }));
    const actors = actorIds.map(actorId => ({ actorId, status: "ACTIVE" as const, revision: 1 }));
    const request = async (index: number, path: string, payload?: Record<string, unknown>) => {
      await service.assertSession(principals[index]);
      return await service.privateRequest(new Request(`https://now.test/v1/${path}`, payload ? { method: "POST", body: JSON.stringify(payload) } : {}), actors[index], principals[index]) as Record<string, unknown>;
    };
    try {
      for (let i=0;i<2;i++) {
        await sql`insert into app.actors(actor_id,status) values (${actorIds[i]}::uuid,'ACTIVE')`;
        await sql`insert into app.wallet_bindings(wallet_binding_id,actor_id,wallet_address,cluster,status,verified_at)
          values (${walletIds[i]}::uuid,${actorIds[i]}::uuid,${crypto.randomUUID()},'devnet','ACTIVE',now())`;
        await sql`insert into app.locations(location_id,name,location_type,center) values (${locations[i]}::uuid,${"test-"+actorIds[0]+i},'PARKING',extensions.st_setsrid(extensions.st_makepoint(${76+i*10},29),4326)::extensions.geography)`;
        await sql`insert into app.state_definitions(state_id,version,canonical_key,title,question,state_type,answer_schema,freshness_policy,location_id,status)
          values (${states[i]}::uuid,1,${states[i]},'Test parking','Available spaces?','NUMERIC','{}','{}',${locations[i]}::uuid,'ACTIVE')`;
      }
      await sql`insert into app.live_states(state_id,state_version,current_value,observed_at,aging_at,fresh_until,verification_class)
        values (${states[0]}::uuid,1,'{"scaled_value":"4","scale":0,"unit":"spaces"}',now()-interval '1 minute',now()+interval '5 minutes',now()+interval '10 minutes','FAST')`;
      const areas = await service.publicRequest(new Request(`https://now.test/v1/areas?q=${actorIds[0]}&filter=live`)) as { items: Record<string,unknown>[] };
      assert(areas.items.length===1 && areas.items[0].area_id===locations[0], "live area filtering must happen before pagination");
      const needsProof = await service.publicRequest(new Request(`https://now.test/v1/areas?q=${actorIds[0]}&filter=stale`)) as { items: Record<string,unknown>[] };
      assert(needsProof.items.length===1 && needsProof.items[0].area_id===locations[1], "unobserved area needs proof");
      await request(0,"me/profile",{display_name:"Alice"});
      assert((await request(1,"me/profile")).display_name==="", "profile leaked across actors");
      await request(0,"me/payout-preferences",{wallet_binding_id:walletIds[0]});
      await rejects(() => request(1,"me/payout-preferences",{wallet_binding_id:walletIds[0]}),"WALLET_UNAVAILABLE");
      const activity = await request(0,"me/activity");
      assert(Array.isArray(activity.items) && activity.items.length===0,"empty actor activity must be valid");
      assert((await request(0,`me/states/${states[0]}/proof`)).available===false,"public state must not expose private proof");
      for(let i=0;i<2;i++) await request(i,"me/installations",{installation_id:installations[i],device_name:"Test device",app_version:"test",push_permission:true,push_token:`test-token-${i}`});
      await rejects(() => request(1,"me/installations",{installation_id:installations[0],device_name:"Other",app_version:"test"}),"INSTALLATION_CONFLICT");
      await rejects(() => request(1,"me/installations/revoke",{installation_id:installations[0]}),"INSTALLATION_NOT_FOUND");
      const before=await request(0,"me/notifications");
      await request(0,"me/notifications/read",{through:before.snapshot_at});
      assert((await request(1,"me/notifications")).unread_count===1,"mark all read must be actor-scoped");
      await request(1,"me/notifications/read",{through:(await request(1,"me/notifications")).snapshot_at});
      const notification=crypto.randomUUID();
      await sql`insert into app.notifications(notification_id,actor_id,event_key,category,title,body,destination)
        values (${notification}::uuid,${actorIds[0]}::uuid,${notification},'proof','Fresh proof','Test update','activity')`;
      await request(0,"me/preferences",{...DEFAULT_NOTIFICATION_PREFERENCES,proof:false});
      const skipped=await runner.runOnce(8);
      assert(skipped.skipped>=1 && sent.length===0,"disabled category must never send");
      await request(0,"me/preferences",DEFAULT_NOTIFICATION_PREFERENCES);
      const next=crypto.randomUUID();
      await sql`insert into app.notifications(notification_id,actor_id,event_key,category,title,body,destination)
        values (${next}::uuid,${actorIds[0]}::uuid,${next},'payments','Receipt','Test receipt','activity')`;
      await runner.runOnce(8); await runner.runOnce(8);
      assert(Number(sent.length)===1 && sent[0]===next,"durable delivery must deduplicate repeated worker ticks");
      await request(0,"me/installations/revoke",{installation_id:installations[0]});
      await rejects(() => request(0,"me/profile"),"SESSION_REVOKED");
      assert((await request(1,"me/profile")).actor_id===actorIds[1],"revoking one device must preserve another actor");
    } finally {
      await service.close(); await runner.close();
      await sql`delete from app.notification_deliveries where installation_id=any(${installations}::uuid[])`;
      await sql`delete from app.notifications where actor_id=any(${actorIds}::uuid[])`;
      await sql`delete from app.installations where actor_id=any(${actorIds}::uuid[])`;
      await sql`delete from app.actor_preferences where actor_id=any(${actorIds}::uuid[])`;
      await sql`delete from app.actor_profiles where actor_id=any(${actorIds}::uuid[])`;
      await sql`delete from app.wallet_bindings where actor_id=any(${actorIds}::uuid[])`;
      await sql`delete from app.live_states where state_id=any(${states}::uuid[])`;
      await sql`delete from app.state_definitions where state_id=any(${states}::uuid[])`;
      await sql`delete from app.locations where location_id=any(${locations}::uuid[])`;
      await sql`delete from app.actors where actor_id=any(${actorIds}::uuid[])`;
      await sql.end({timeout:1});
    }
  }
});
