import type { SettlementCoordinator } from "../src/settlement-coordinator.ts";
import { createSettlementWorkerHandler } from "../src/settlement-worker.ts";

const TOKEN = "worker-test-token-0123456789-abcdef";

function coordinator(summary: Record<string, number>): SettlementCoordinator {
  return {
    runOnce: () => Promise.resolve(summary),
  } as unknown as SettlementCoordinator;
}

Deno.test("settlement worker rejects unauthenticated execution", async () => {
  const handler = createSettlementWorkerHandler(
    coordinator({ prepared: 0 }),
    TOKEN,
  );
  const response = await handler(
    new Request("https://worker.invalid/", { method: "POST" }),
  );
  if (response.status !== 401) {
    throw new Error("worker accepted unauthenticated invocation");
  }
});

Deno.test("settlement worker runs a bounded authenticated tick", async () => {
  const expected = {
    prepared: 1,
    signed: 1,
    submitted: 1,
    ambiguous: 0,
    pending: 0,
    confirmed: 0,
    finalized: 0,
    safeRetries: 0,
    conflicts: 0,
    deferred: 0,
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
    JSON.stringify(body.settlement) !== JSON.stringify(expected)
  ) {
    throw new Error("authenticated worker tick returned the wrong result");
  }
});

Deno.test("settlement worker rejects non-POST execution", async () => {
  const handler = createSettlementWorkerHandler(
    coordinator({ prepared: 0 }),
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
