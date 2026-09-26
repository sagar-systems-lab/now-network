import type { NowWorkerCoordinator } from "../src/now-worker-coordinator.ts";
import { createSettlementWorkerHandler } from "../src/settlement-worker.ts";

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
