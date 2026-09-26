import type { NowWorkerCoordinator } from "../src/now-worker-coordinator.ts";
import { createSettlementWorkerHandler } from "../src/settlement-worker.ts";
import type { WorkerHeartbeat } from "../src/runtime-health.ts";

const TOKEN = "worker-test-token-0123456789-abcdef";

function coordinator(
  summary: Record<string, unknown>,
): NowWorkerCoordinator {
  return {
    runOnce: () => Promise.resolve(summary),
  } as unknown as NowWorkerCoordinator;
}

const EMPTY = {
  settlement: {
    prepared: 0,
    signed: 0,
    submitted: 0,
    ambiguous: 0,
    pending: 0,
    confirmed: 0,
    finalized: 0,
    safeRetries: 0,
    conflicts: 0,
    deferred: 0,
  },
  receipts: {
    finalized: 0,
    replayed: 0,
    conflicts: 0,
  },
  realtime: {
    published: 0,
  },
};

Deno.test("settlement worker rejects unauthenticated execution", async () => {
  const handler = createSettlementWorkerHandler(
    coordinator(EMPTY),
    TOKEN,
  );
  const response = await handler(
    new Request("https://worker.invalid/", { method: "POST" }),
  );
  if (response.status !== 401) {
    throw new Error("worker accepted unauthenticated invocation");
  }
});

Deno.test("settlement worker runs the complete bounded worker tick", async () => {
  const expected = {
    settlement: {
      ...EMPTY.settlement,
      prepared: 1,
      signed: 1,
      submitted: 1,
    },
    receipts: {
      finalized: 1,
      replayed: 0,
      conflicts: 0,
    },
    realtime: {
      published: 2,
    },
  };
  const handler = createSettlementWorkerHandler(
    coordinator(expected),
    TOKEN,
    8,
  );
  const response = await handler(
    new Request("https://worker.invalid/", {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}` },
    }),
  );
  const body = await response.json() as Record<string, unknown>;
  if (
    response.status !== 200 ||
    body.ok !== true ||
    JSON.stringify(body.settlement) !== JSON.stringify(expected.settlement) ||
    JSON.stringify(body.receipts) !== JSON.stringify(expected.receipts) ||
    JSON.stringify(body.realtime) !== JSON.stringify(expected.realtime)
  ) {
    throw new Error("authenticated worker tick returned the wrong result");
  }
});

Deno.test("settlement worker rejects non-POST execution", async () => {
  const handler = createSettlementWorkerHandler(
    coordinator(EMPTY),
    TOKEN,
  );
  const response = await handler(
    new Request("https://worker.invalid/", {
      method: "GET",
      headers: { authorization: `Bearer ${TOKEN}` },
    }),
  );
  if (response.status !== 405) {
    throw new Error("worker accepted a non-POST execution");
  }
});

Deno.test("settlement worker records one successful heartbeat after a tick", async () => {
  const heartbeats: WorkerHeartbeat[] = [];
  const observedAt = new Date("2026-09-26T09:30:00.000Z");
  const handler = createSettlementWorkerHandler(
    coordinator(EMPTY),
    TOKEN,
    8,
    {
      repository: {
        recordWorkerHeartbeat(input) {
          heartbeats.push(input);
          return Promise.resolve();
        },
      },
      workerId: "worker-test",
      buildVersion: "test-build",
      now: () => observedAt,
    },
  );

  const response = await handler(
    new Request("https://worker.invalid/", {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}` },
    }),
  );

  if (response.status !== 200 || heartbeats.length !== 1) {
    throw new Error("successful worker tick did not record one heartbeat");
  }
  const heartbeat = heartbeats[0];
  if (
    heartbeat.workerId !== "worker-test" ||
    heartbeat.buildVersion !== "test-build" ||
    heartbeat.result !== "SUCCESS" ||
    heartbeat.observedAt.getTime() !== observedAt.getTime()
  ) {
    throw new Error("worker heartbeat identity drifted");
  }
});
