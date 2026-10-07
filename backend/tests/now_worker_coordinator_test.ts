import type { VerificationWorker } from "../src/verification-worker.ts";
import type { ReceiptCoordinator } from "../src/receipt-coordinator.ts";
import { NowWorkerCoordinator } from "../src/now-worker-coordinator.ts";
import type { RealtimePublisher } from "../src/realtime-outbox.ts";
import type { SettlementCoordinator } from "../src/settlement-coordinator.ts";

Deno.test("worker verifies proof before settlement, receipts and realtime", async () => {
  const order: string[] = [];

  const settlement = {
    runOnce: () => {
      order.push("settlement");
      return Promise.resolve({
        prepared: 0,
        signed: 0,
        submitted: 0,
        ambiguous: 0,
        pending: 0,
        confirmed: 0,
        finalized: 1,
        safeRetries: 0,
        conflicts: 0,
        deferred: 0,
      });
    },
  } as unknown as SettlementCoordinator;

  const receipts = {
    runOnce: () => {
      order.push("receipts");
      return Promise.resolve({
        finalized: 1,
        replayed: 0,
        conflicts: 0,
      });
    },
  } as unknown as ReceiptCoordinator;

  const realtime = {
    runOnce: () => {
      order.push("realtime");
      return Promise.resolve({ published: 2 });
    },
  } as unknown as RealtimePublisher;

  const verification = {
    runOnce: () => {
      order.push("verification");
      return Promise.resolve({ verified: 1, awaitingEvidence: 0, deferred: 0 });
    },
  } as unknown as VerificationWorker;
  const worker = new NowWorkerCoordinator(
    settlement,
    receipts,
    realtime,
    undefined,
    undefined,
    verification,
  );
  const result = await worker.runOnce(8);

  if (
    order.join(",") !== "verification,settlement,receipts,realtime" ||
    result.verification?.verified !== 1 ||
    result.settlement.finalized !== 1 ||
    result.receipts.finalized !== 1 ||
    result.realtime.published !== 2
  ) {
    throw new Error("worker dependency order is not deterministic");
  }
});
