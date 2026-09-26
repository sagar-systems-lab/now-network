import {
  type RuntimeHealthRepository,
  type RuntimeHealthSnapshot,
  RuntimeReadinessProbe,
} from "../src/runtime-health.ts";

const NOW = new Date("2026-09-26T09:30:00.000Z");

function snapshot(
  overrides: Partial<RuntimeHealthSnapshot> = {},
): RuntimeHealthSnapshot {
  return {
    workerId: "now-worker",
    workerLastSeen: new Date(NOW.getTime() - 10_000),
    workerLastJobAt: new Date(NOW.getTime() - 10_000),
    workerBuildVersion: "test",
    workerLastResult: "SUCCESS",
    verification: { count: 0, oldestAgeSeconds: null },
    settlement: { count: 0, oldestAgeSeconds: null },
    outbox: { count: 0, oldestAgeSeconds: null },
    ...overrides,
  };
}

function repository(
  value: RuntimeHealthSnapshot | Error,
): RuntimeHealthRepository {
  return {
    snapshot: () => value instanceof Error ? Promise.reject(value) : Promise.resolve(value),
    recordWorkerHeartbeat: () => Promise.resolve(),
  };
}

Deno.test("readiness is healthy with fresh worker and empty backlogs", async () => {
  const probe = new RuntimeReadinessProbe(repository(snapshot()), () => NOW);
  if (await probe.check() !== "HEALTHY") {
    throw new Error("healthy runtime was not ready");
  }
});

Deno.test("readiness degrades on stale worker heartbeat", async () => {
  const probe = new RuntimeReadinessProbe(
    repository(snapshot({
      workerLastSeen: new Date(NOW.getTime() - 6 * 60 * 1_000),
    })),
    () => NOW,
  );
  if (await probe.check() !== "DEGRADED") {
    throw new Error("stale worker did not degrade readiness");
  }
});

Deno.test("readiness degrades on aged financial backlog", async () => {
  const probe = new RuntimeReadinessProbe(
    repository(snapshot({
      settlement: { count: 1, oldestAgeSeconds: 121 },
    })),
    () => NOW,
  );
  if (await probe.check() !== "DEGRADED") {
    throw new Error("aged settlement backlog did not degrade readiness");
  }
});

Deno.test("readiness is unavailable when database snapshot fails", async () => {
  const probe = new RuntimeReadinessProbe(
    repository(new Error("database unavailable")),
    () => NOW,
  );
  if (await probe.check() !== "UNAVAILABLE") {
    throw new Error("database failure did not fail readiness closed");
  }
});
