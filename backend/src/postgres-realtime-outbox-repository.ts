import postgres from "npm:postgres@3.4.7";
import type { RealtimeOutboxRepository, RealtimePublishSummary } from "./realtime-outbox.ts";

type DateLike = Date | string;

type OutboxRow = {
  outbox_id: string;
  event_type: string;
  entity_type: string;
  entity_id: string;
  entity_revision: number | string | null;
  audience_type: "PUBLIC_ENTITY" | "PUBLIC_AREA" | "ACTOR_PRIVATE";
  audience_actor_id: string | null;
  area_key: string | null;
  payload: unknown;
  created_at: DateLike;
  expires_at: DateLike | null;
};

function date(value: DateLike): Date {
  return value instanceof Date ? value : new Date(value);
}

export class PostgresRealtimeOutboxRepository implements RealtimeOutboxRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async publishBatch(
    input: Parameters<RealtimeOutboxRepository["publishBatch"]>[0],
  ): Promise<RealtimePublishSummary> {
    const bounded = Math.max(1, Math.min(64, Math.trunc(input.limit)));
    let published = 0;

    for (let index = 0; index < bounded; index += 1) {
      const didPublish = await this.sql.begin(async (tx) => {
        const rows = await tx`
          select
            outbox_id,
            event_type,
            entity_type,
            entity_id,
            entity_revision,
            audience_type,
            audience_actor_id,
            area_key,
            payload,
            created_at,
            expires_at
          from app.outbox_events
          where status in ('PENDING', 'RETRY_WAIT')
            and (next_attempt_at is null or next_attempt_at <= ${input.observedAt})
          order by coalesce(next_attempt_at, created_at), created_at, outbox_id
          for update skip locked
          limit 1
        `;
        if (!rows[0]) return false;
        const row = rows[0] as unknown as OutboxRow;

        await tx`
          update app.outbox_events
          set
            status = 'PUBLISHING',
            attempt_count = attempt_count + 1,
            last_error = null
          where outbox_id = ${row.outbox_id}::uuid
        `;

        await tx`
          insert into public.realtime_events_v1(
            realtime_event_id,
            source_outbox_id,
            event_type,
            entity_type,
            entity_id,
            entity_revision,
            audience_type,
            audience_actor_id,
            area_key,
            payload,
            created_at,
            expires_at
          ) values (
            ${row.outbox_id}::uuid,
            ${row.outbox_id}::uuid,
            ${row.event_type},
            ${row.entity_type},
            ${row.entity_id}::uuid,
            ${row.entity_revision === null ? null : Number(row.entity_revision)},
            ${row.audience_type}::app.event_visibility,
            ${row.audience_actor_id}::uuid,
            ${row.area_key},
            ${JSON.stringify(row.payload)}::text::jsonb,
            ${date(row.created_at)},
            ${row.expires_at === null ? null : date(row.expires_at)}
          )
          on conflict (source_outbox_id) do nothing
        `;

        await tx`
          update app.outbox_events
          set
            status = 'PUBLISHED',
            next_attempt_at = null,
            published_at = coalesce(published_at, ${input.observedAt}),
            last_error = null
          where outbox_id = ${row.outbox_id}::uuid
        `;

        return true;
      });
      if (!didPublish) break;
      published += 1;
    }

    return { published };
  }
}
