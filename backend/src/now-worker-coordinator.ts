import type { ReceiptCoordinator, ReceiptTickSummary } from "./receipt-coordinator.ts";
import type {
  RealtimePublishSummary,
  RealtimePublisher,
} from "./realtime-outbox.ts";
import type {
  SettlementCoordinator,
  SettlementTickSummary,
} from "./settlement-coordinator.ts";

export type NowWorkerTickSummary = {
  settlement: SettlementTickSummary;
  receipts: ReceiptTickSummary;
  realtime: RealtimePublishSummary;
};

export class NowWorkerCoordinator {
  constructor(
    private readonly settlement: SettlementCoordinator,
    private readonly receipts: ReceiptCoordinator,
    private readonly realtime: RealtimePublisher,
  ) {}

  async runOnce(limit = 8): Promise<NowWorkerTickSummary> {
    const bounded = Math.max(1, Math.min(32, Math.trunc(limit)));
    const settlement = await this.settlement.runOnce(bounded);
    const receipts = await this.receipts.runOnce(bounded);
    const realtime = await this.realtime.runOnce(bounded * 2);
    return { settlement, receipts, realtime };
  }
}
