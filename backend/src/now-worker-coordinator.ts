import type { NotificationRunner, PushSummary } from "./notification-delivery.ts";
import type { ReceiptCoordinator, ReceiptTickSummary } from "./receipt-coordinator.ts";
import type { RealtimePublisher, RealtimePublishSummary } from "./realtime-outbox.ts";
import type { SettlementCoordinator, SettlementTickSummary } from "./settlement-coordinator.ts";

export type NowWorkerTickSummary = {
  settlement: SettlementTickSummary;
  receipts: ReceiptTickSummary;
  realtime: RealtimePublishSummary;
  notifications?: PushSummary;
};

export class NowWorkerCoordinator {
  constructor(
    private readonly settlement: SettlementCoordinator,
    private readonly receipts: ReceiptCoordinator,
    private readonly realtime: RealtimePublisher,
    private readonly notifications?: NotificationRunner,
  ) {}

  async runOnce(limit = 8): Promise<NowWorkerTickSummary> {
    const bounded = Math.max(1, Math.min(32, Math.trunc(limit)));
    const settlement = await this.settlement.runOnce(bounded);
    const receipts = await this.receipts.runOnce(bounded);
    const realtime = await this.realtime.runOnce(bounded * 2);
    // Notification transport must never fail money reconciliation. Durable delivery retries next tick.
    const notifications = await this.notifications?.runOnce(bounded).catch(() => ({ sent: 0, skipped: 0, deferred: 0, failed: 1 }));
    return { settlement, receipts, realtime, ...(notifications ? { notifications } : {}) };
  }
}
