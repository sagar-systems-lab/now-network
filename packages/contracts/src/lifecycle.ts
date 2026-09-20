export const STATE_TYPES = ["BINARY", "NUMERIC", "VISUAL"] as const;
export type StateType = (typeof STATE_TYPES)[number];

export const REFRESH_STATUSES = [
  "DRAFT",
  "AWAITING_FUNDING",
  "FUNDED",
  "AVAILABLE",
  "CLAIMED",
  "CAPTURE_IN_PROGRESS",
  "EVIDENCE_SUBMITTED",
  "VERIFYING",
  "ADDITIONAL_VERIFICATION",
  "CONFLICT",
  "VERIFIED",
  "SETTLEMENT_PENDING",
  "SETTLEMENT_VERIFYING",
  "COMPLETED",
  "CANCELLED",
  "EXPIRED",
  "FAILED",
] as const;
export type RefreshStatus = (typeof REFRESH_STATUSES)[number];

export const EVIDENCE_STATUSES = [
  "NONE",
  "CAPTURING",
  "CAPTURED_LOCAL",
  "HASHING",
  "UPLOAD_READY",
  "UPLOADING",
  "UPLOADED",
  "COMMITTING",
  "COMMITTED",
  "VERIFYING",
  "VERIFIED",
  "REJECTED",
  "CONFLICT",
  "EXPIRED",
  "FAILED",
] as const;
export type EvidenceStatus = (typeof EVIDENCE_STATUSES)[number];

export const VERIFICATION_STATUSES = [
  "NOT_STARTED",
  "QUEUED",
  "RUNNING",
  "WAITING_FOR_MORE_EVIDENCE",
  "VERIFIED",
  "REJECTED",
  "CONFLICT",
  "EXPIRED",
  "INTERNAL_ERROR",
] as const;
export type VerificationStatus = (typeof VERIFICATION_STATUSES)[number];

export const SETTLEMENT_STATUSES = [
  "NOT_STARTED",
  "ELIGIBLE",
  "BUILDING",
  "SUBMITTING",
  "SUBMITTED",
  "VERIFYING",
  "CONFIRMED",
  "FINALIZING",
  "FINALIZED",
  "NOT_SETTLED",
  "FAILED",
] as const;
export type SettlementStatus = (typeof SETTLEMENT_STATUSES)[number];

export const REFUND_STATUSES = [
  "NOT_ELIGIBLE",
  "ELIGIBLE",
  "PREPARING",
  "WALLET_PENDING",
  "SUBMITTED",
  "VERIFYING",
  "CONFIRMED",
  "FINALIZED",
  "NOT_REFUNDED",
  "FAILED",
] as const;
export type RefundStatus = (typeof REFUND_STATUSES)[number];

export const RECEIPT_STATUSES = [
  "NONE",
  "DRAFT",
  "CONFIRMED",
  "FINALIZING",
  "FINAL",
  "ANNOTATED",
] as const;
export type ReceiptStatus = (typeof RECEIPT_STATUSES)[number];

export const BACKEND_OUTBOX_STATUSES = [
  "PENDING",
  "PUBLISHING",
  "PUBLISHED",
  "RETRY_WAIT",
  "DEAD_LETTER",
] as const;
export type BackendOutboxStatus = (typeof BACKEND_OUTBOX_STATUSES)[number];

export const EVENT_VISIBILITIES = ["PUBLIC_ENTITY", "PUBLIC_AREA", "ACTOR_PRIVATE"] as const;
export type EventVisibility = (typeof EVENT_VISIBILITIES)[number];
