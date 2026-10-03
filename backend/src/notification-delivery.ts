import postgres from "npm:postgres@3.4.7";
import {
  DEFAULT_NOTIFICATION_PREFERENCES,
  notificationPreferences,
  quietNow,
} from "./experience-contract.ts";

export type PushSummary = { sent: number; skipped: number; deferred: number; failed: number };
export interface NotificationRunner {
  runOnce(limit: number): Promise<PushSummary>;
}
type Account = { project_id: string; client_email: string; private_key: string };
const encoder = new TextEncoder();
const b64url = (bytes: Uint8Array) =>
  btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");

/** FCM v1 credentials exist only in the worker. No credential or token is logged. */
export class FcmSender {
  private token: { value: string; expires: number } | undefined;
  private readonly account: Account;
  constructor(raw: string, private readonly request: typeof fetch = fetch) {
    this.account = JSON.parse(raw);
    if (
      !/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(this.account.project_id) ||
      !this.account.client_email?.endsWith(".iam.gserviceaccount.com") ||
      !this.account.private_key?.startsWith("-----BEGIN PRIVATE KEY-----")
    ) throw new Error("Invalid FCM service account configuration");
  }
  private async accessToken(): Promise<string> {
    if (this.token && this.token.expires > Date.now() + 60_000) return this.token.value;
    const now = Math.floor(Date.now() / 1000);
    const header = b64url(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
    const body = b64url(
      encoder.encode(
        JSON.stringify({
          iss: this.account.client_email,
          scope: "https://www.googleapis.com/auth/firebase.messaging",
          aud: "https://oauth2.googleapis.com/token",
          iat: now,
          exp: now + 3600,
        }),
      ),
    );
    const pem = this.account.private_key.replace(/-----[^-]+-----|\s/g, "");
    const key = await crypto.subtle.importKey(
      "pkcs8",
      Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const signature = await crypto.subtle.sign(
      "RSASSA-PKCS1-v1_5",
      key,
      encoder.encode(`${header}.${body}`),
    );
    const response = await this.request("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: `${header}.${body}.${b64url(new Uint8Array(signature))}`,
      }),
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new Error("Push authorization unavailable");
    const token = await response.json();
    if (typeof token.access_token !== "string" || !Number.isFinite(token.expires_in)) {
      throw new Error("Invalid push authorization response");
    }
    this.token = {
      value: token.access_token,
      expires: Date.now() + Math.min(3600, token.expires_in) * 1000,
    };
    return this.token.value;
  }
  async send(token: string, notificationId: string): Promise<"sent" | "invalid" | "retry"> {
    const response = await this.request(
      `https://fcm.googleapis.com/v1/projects/${this.account.project_id}/messages:send`,
      {
        method: "POST",
        headers: {
          authorization: `Bearer ${await this.accessToken()}`,
          "content-type": "application/json",
        },
        // Generic lock-screen copy. The inbox performs actor-authorized reads after opening.
        body: JSON.stringify({
          message: {
            token,
            data: { notification_id: notificationId, route: "inbox" },
            android: { priority: "normal", ttl: "86400s", collapse_key: "now_account_updates" },
          },
        }),
        signal: AbortSignal.timeout(10_000),
      },
    );
    if (response.ok) return "sent";
    if (response.status === 401) this.token = undefined;
    const payload = await response.json().catch(() => ({}));
    const invalid = payload.error?.details?.some((item: { errorCode?: string }) =>
      item.errorCode === "UNREGISTERED"
    );
    return invalid ? "invalid" : "retry";
  }
}

export class PostgresNotificationRunner implements NotificationRunner {
  private readonly sql: ReturnType<typeof postgres>;
  constructor(connection: string, private readonly sender: Pick<FcmSender, "send">) {
    this.sql = postgres(connection, {
      max: 2,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }
  async close(): Promise<void> {
    await this.sql.end({ timeout: 1 });
  }

  async runOnce(limit: number): Promise<PushSummary> {
    const count = Math.max(1, Math.min(8, Math.trunc(limit)));
    const summary: PushSummary = { sent: 0, skipped: 0, deferred: 0, failed: 0 };
    await this.sql`insert into app.notification_deliveries(notification_id,installation_id)
      select n.notification_id,i.installation_id from app.notifications n
      join app.installations i on i.actor_id=n.actor_id and i.revoked_at is null and i.push_permission and i.push_token is not null
      where n.created_at >= now()-interval '24 hours' and n.read_at is null
      and not exists(select 1 from app.notification_deliveries d where d.notification_id=n.notification_id and d.installation_id=i.installation_id)
      order by n.created_at limit 256 on conflict do nothing`;
    const leased = await this.sql`with candidate as (
      select notification_id,installation_id from app.notification_deliveries
      where status in ('PENDING','SENDING') and next_attempt_at<=now() and (lease_until is null or lease_until<now())
      order by next_attempt_at for update skip locked limit ${count}
    ) update app.notification_deliveries d set status='SENDING',lease_until=now()+interval '5 minutes'
      from candidate c where c.notification_id=d.notification_id and c.installation_id=d.installation_id returning d.notification_id,d.installation_id,d.attempts`;
    for (const delivery of leased) {
      const rows = await this
        .sql`select n.category,n.read_at,n.created_at,i.push_token,i.push_permission,i.revoked_at,a.status as actor_status,p.notification_preferences
        from app.notifications n join app.installations i on i.installation_id=${delivery.installation_id}::uuid and i.actor_id=n.actor_id
        join app.actors a on a.actor_id=n.actor_id left join app.actor_preferences p on p.actor_id=n.actor_id
        where n.notification_id=${delivery.notification_id}::uuid`;
      const row = rows[0];
      let outcome: "SENT" | "SKIPPED" | "PENDING" | "FAILED" = "SKIPPED";
      let attempts = Number(delivery.attempts), delaySeconds = 300;
      const prefs = notificationPreferences({
        ...DEFAULT_NOTIFICATION_PREFERENCES,
        ...(row?.notification_preferences ?? {}),
      });
      const enabled = row && row.actor_status === "ACTIVE" && !row.revoked_at && !row.read_at &&
        row.push_permission && row.push_token &&
        new Date(row.created_at).getTime() > Date.now() - 86_400_000 &&
        prefs[row.category as "proof" | "payments" | "security" | "opportunities"];
      if (enabled) {
        if (quietNow(prefs, new Date())) outcome = "PENDING";
        else {
          attempts++;
          const result = await this.sender.send(row.push_token, delivery.notification_id).catch(
            () => "retry" as const,
          );
          if (result === "sent") outcome = "SENT";
          else if (result === "invalid") {
            await this
              .sql`update app.installations set push_token=null where installation_id=${delivery.installation_id}::uuid and push_token=${row.push_token}`;
          } else {
            outcome = attempts >= 6 ? "FAILED" : "PENDING";
            delaySeconds = Math.min(3600, 30 * 2 ** attempts);
          }
        }
      }
      await this
        .sql`update app.notification_deliveries set status=${outcome}, attempts=${attempts},lease_until=null,
        next_attempt_at=now()+make_interval(secs=>${delaySeconds}),sent_at=case when ${outcome}='SENT' then now() else sent_at end
        where notification_id=${delivery.notification_id}::uuid and installation_id=${delivery.installation_id}::uuid`;
      summary[
        outcome === "SENT"
          ? "sent"
          : outcome === "SKIPPED"
          ? "skipped"
          : outcome === "FAILED"
          ? "failed"
          : "deferred"
      ]++;
    }
    return summary;
  }
}
