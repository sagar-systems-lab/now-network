export const STATE_KINDS = ["binary", "numeric", "visual"] as const;
export type StateKind = (typeof STATE_KINDS)[number];

export const FRESHNESS_STATUSES = ["LIVE", "AGING", "STALE"] as const;
export type FreshnessStatus = (typeof FRESHNESS_STATUSES)[number];

export const REFRESH_OVERLAYS = ["REFRESHING", "CONFLICT"] as const;
export type RefreshOverlay = (typeof REFRESH_OVERLAYS)[number];

export const VERIFICATION_CLASSES = ["FAST", "CORROBORATED", "STRICT"] as const;
export type VerificationClass = (typeof VERIFICATION_CLASSES)[number];

export const VERIFICATION_OUTCOMES = [
  "VERIFIED",
  "REJECTED",
  "CONFLICT",
  "REQUIRES_ADDITIONAL_VERIFICATION",
  "EXPIRED",
] as const;
export type VerificationOutcome = (typeof VERIFICATION_OUTCOMES)[number];

export const SETTLEMENT_OUTCOMES = [
  "NOT_STARTED",
  "PENDING",
  "VERIFYING",
  "PAID",
  "NOT_SETTLED",
  "REFUNDED",
  "FAILED",
] as const;
export type SettlementOutcome = (typeof SETTLEMENT_OUTCOMES)[number];

export const ERROR_CATEGORIES = [
  "VALIDATION",
  "AUTH",
  "BUSINESS",
  "CONFLICT",
  "NETWORK",
  "DEPENDENCY",
  "STORAGE",
  "RPC",
  "DATABASE",
  "INTERNAL",
  "SECURITY",
] as const;
export type ErrorCategory = (typeof ERROR_CATEGORIES)[number];
