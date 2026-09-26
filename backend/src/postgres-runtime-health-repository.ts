import postgres from "npm:postgres@3.4.7";
import type {
  RuntimeHealthRepository,
  RuntimeHealthSnapshot,
  WorkerHeartbeat,
} from "./runtime-health.ts";

type DateLike = Date | string;

type HeartbeatRow = {
  worker_id: string;
  last_seen_at: DateLike;
  last_job_at: DateLike | null;
  build_version: string;
  last_result: "SUCCESS" | "FAILURE";
};

type BacklogRow = {
  pending_verification_count: number | string;
  oldest_pending_verification_age_seconds: number | string | null;
  pending_settlement_count: number | string;
  oldest_pending_settlement_age_seconds: number | string | null;
  pending_outbox_count: number | string;
  oldest_pending_outbox_age_seconds: number | string | null;
};

function date(value: DateLike): Date {
  return value instanceof Date ? value : new Date(value);
}

function number(value: number | string): number {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0) {
    throw new Error("runtime health metric is invalid");
  }
  return parsed;
}

function nullableNumber(value: number | string | null): number | null {
  return value === null ? null : number(value);
}

export class PostgresRuntimeHealthRepository implements RuntimeHealthRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async recordWorkerHeartbeat(input: WorkerHeartbeat): Promise<void> {
    await this.sql`
      insert into app.runtime_health(
        worker_id,
        last_seen_at,
        last_job_at,
        build_version,
        last_result,
        summary,
        updated_at
      ) values (
        ${input.workerId},
        ${input.observedAt},
        ${input.lastJobAt},
        ${input.buildVersion},
        ${input.result},
        ${JSON.stringify(input.summary ?? {})}::jsonb,
        ${input.observedAt}
      )
      on conflict (worker_id) do update
      set
        last_seen_at = excluded.last_seen_at,
        last_job_at = excluded.last_job_at,
        build_version = excluded.build_version,
        last_result = excluded.last_result,
        summary = excluded.summary,
        updated_at = excluded.updated_at
    `;
  }

  async snapshot(observedAt: Date): Promise<RuntimeHealthSnapshot> {
    const heartbeatRows = await this.sql`
      select
        worker_id,
        last_seen_at,
        last_job_at,
        build_version,
        last_result
      from app.runtime_health
      order by last_seen_at desc, worker_id
      limit 1
    `;

    const backlogRows = await this.sql`
      select *
      from app.runtime_backlog_snapshot_v1(${observedAt})
    `;
    const backlog = backlogRows[0] as unknown as BacklogRow | undefined;
    if (!backlog) throw new Error("runtime backlog snapshot is unavailable");

    const heartbeat = heartbeatRows[0] ? heartbeatRows[0] as unknown as HeartbeatRow : null;
    return {
      workerId: heartbeat?.worker_id ?? null,
      workerLastSeen: heartbeat ? date(heartbeat.last_seen_at) : null,
      workerLastJobAt: heartbeat && heartbeat.last_job_at !== null
        ? date(heartbeat.last_job_at)
        : null,
      workerBuildVersion: heartbeat?.build_version ?? null,
      workerLastResult: heartbeat?.last_result ?? null,
      verification: {
        count: number(backlog.pending_verification_count),
        oldestAgeSeconds: nullableNumber(
          backlog.oldest_pending_verification_age_seconds,
        ),
      },
      settlement: {
        count: number(backlog.pending_settlement_count),
        oldestAgeSeconds: nullableNumber(
          backlog.oldest_pending_settlement_age_seconds,
        ),
      },
      outbox: {
        count: number(backlog.pending_outbox_count),
        oldestAgeSeconds: nullableNumber(
          backlog.oldest_pending_outbox_age_seconds,
        ),
      },
    };
  }
}
