export type HealthState = "HEALTHY" | "DEGRADED" | "UNAVAILABLE";

export type BacklogMetric = {
  count: number;
  oldestAgeSeconds: number | null;
};

export type RuntimeHealthSnapshot = {
  workerId: string | null;
  workerLastSeen: Date | null;
  workerLastJobAt: Date | null;
  workerBuildVersion: string | null;
  workerLastResult: "SUCCESS" | "FAILURE" | null;
  verification: BacklogMetric;
  settlement: BacklogMetric;
  outbox: BacklogMetric;
};

export type WorkerHeartbeat = {
  workerId: string;
  observedAt: Date;
  lastJobAt: Date;
  buildVersion: string;
  result: "SUCCESS" | "FAILURE";
  summary: unknown;
};

export interface RuntimeHealthRepository {
  snapshot(observedAt: Date): Promise<RuntimeHealthSnapshot>;
  recordWorkerHeartbeat(input: WorkerHeartbeat): Promise<void>;
}

export interface ReadinessProbe {
  check(): Promise<HealthState>;
}

export const DEFAULT_WORKER_STALE_MS = 5 * 60 * 1_000;
export const DEFAULT_VERIFICATION_BACKLOG_MAX_AGE_SECONDS = 10;
export const DEFAULT_SETTLEMENT_BACKLOG_MAX_AGE_SECONDS = 120;
export const DEFAULT_OUTBOX_BACKLOG_MAX_AGE_SECONDS = 30;

function exceeds(metric: BacklogMetric, maximumAgeSeconds: number): boolean {
  return metric.count > 0 &&
    metric.oldestAgeSeconds !== null &&
    metric.oldestAgeSeconds > maximumAgeSeconds;
}

export class RuntimeReadinessProbe implements ReadinessProbe {
  constructor(
    private readonly repository: RuntimeHealthRepository,
    private readonly now: () => Date = () => new Date(),
    private readonly workerStaleMs = DEFAULT_WORKER_STALE_MS,
  ) {
    if (!Number.isSafeInteger(workerStaleMs) || workerStaleMs <= 0) {
      throw new Error("worker stale threshold must be a positive integer");
    }
  }

  async check(): Promise<HealthState> {
    const observedAt = this.now();
    let snapshot: RuntimeHealthSnapshot;

    try {
      snapshot = await this.repository.snapshot(observedAt);
    } catch {
      return "UNAVAILABLE";
    }

    const workerStale = snapshot.workerLastSeen === null ||
      observedAt.getTime() - snapshot.workerLastSeen.getTime() > this.workerStaleMs;

    if (
      workerStale ||
      snapshot.workerLastResult === "FAILURE" ||
      exceeds(
        snapshot.verification,
        DEFAULT_VERIFICATION_BACKLOG_MAX_AGE_SECONDS,
      ) ||
      exceeds(
        snapshot.settlement,
        DEFAULT_SETTLEMENT_BACKLOG_MAX_AGE_SECONDS,
      ) ||
      exceeds(snapshot.outbox, DEFAULT_OUTBOX_BACKLOG_MAX_AGE_SECONDS)
    ) {
      return "DEGRADED";
    }

    return "HEALTHY";
  }
}
