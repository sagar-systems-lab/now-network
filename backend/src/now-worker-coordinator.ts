import type { NotificationRunner, PushSummary } from "./notification-delivery.ts";
import type { ReceiptCoordinator, ReceiptTickSummary } from "./receipt-coordinator.ts";
import type { RealtimePublisher, RealtimePublishSummary } from "./realtime-outbox.ts";
import type { SettlementCoordinator, SettlementTickSummary } from "./settlement-coordinator.ts";

import type { VerificationTickSummary, VerificationWorker } from "./verification-worker.ts";

export type NowWorkerTickSummary = {
  verification?: VerificationTickSummary;
  settlement: SettlementTickSummary;
  receipts: ReceiptTickSummary;
  realtime: RealtimePublishSummary;
  notifications?: PushSummary;
  discoveryCleanup?: "OK" | "RETRY";
};

export class NowWorkerCoordinator {
  constructor(
    private readonly settlement: SettlementCoordinator,
    private readonly receipts: ReceiptCoordinator,
    private readonly realtime: RealtimePublisher,
    private readonly notifications?: NotificationRunner,
    private readonly discoveryCleanup?: () => Promise<void>,
    private readonly verification?: VerificationWorker,
  ) {}

  async runOnce(limit = 8): Promise<NowWorkerTickSummary> {
    const bounded = Math.max(1, Math.min(32, Math.trunc(limit)));
    const verification = await this.verification?.runOnce(bounded).catch(() => ({
      verified: 0,
      awaitingEvidence: 0,
      deferred: 1,
    }));
    const settlement = await this.settlement.runOnce(bounded);
    const receipts = await this.receipts.runOnce(bounded);
    const realtime = await this.realtime.runOnce(bounded * 2);
    // Notification transport must never fail money reconciliation. Durable delivery retries next tick.
    const notifications = await this.notifications?.runOnce(bounded).catch(() => ({
      sent: 0,
      skipped: 0,
      deferred: 0,
      failed: 1,
    }));
    const discoveryCleanup = await this.discoveryCleanup?.().then(() => "OK" as const).catch(() =>
      "RETRY" as const
    );
    return {
      ...(verification ? { verification } : {}),
      settlement,
      receipts,
      realtime,
      ...(notifications ? { notifications } : {}),
      ...(discoveryCleanup ? { discoveryCleanup } : {}),
    };
  }
}
