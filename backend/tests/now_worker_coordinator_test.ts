import type { ReceiptCoordinator } from "../src/receipt-coordinator.ts";
import { NowWorkerCoordinator } from "../src/now-worker-coordinator.ts";
import type { RealtimePublisher } from "../src/realtime-outbox.ts";
import type { SettlementCoordinator } from "../src/settlement-coordinator.ts";

Deno.test("worker runs settlement then receipts then realtime", async () => {
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

  const worker = new NowWorkerCoordinator(settlement, receipts, realtime);
  const result = await worker.runOnce(8);

  if (
    order.join(",") !== "settlement,receipts,realtime" ||
    result.settlement.finalized !== 1 ||
    result.receipts.finalized !== 1 ||
    result.realtime.published !== 2
  ) {
    throw new Error("worker dependency order is not deterministic");
  }
});
