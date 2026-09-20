export const STATE_KINDS = ["binary", "numeric", "visual"] as const;
export type StateKind = (typeof STATE_KINDS)[number];

export const FRESHNESS_STATUSES = ["LIVE", "AGING", "STALE"] as const;
export type FreshnessStatus = (typeof FRESHNESS_STATUSES)[number];

export const REFRESH_OVERLAYS = ["REFRESHING", "CONFLICT"] as const;
export type RefreshOverlay = (typeof REFRESH_OVERLAYS)[number];

export const VERIFICATION_CLASSES = ["FAST", "CORROBORATED", "STRICT"] as const;
export type VerificationClass = (typeof VERIFICATION_CLASSES)[number];

export const VERIFICATION_RESULTS = [
  "VERIFIED",
  "REJECTED",
  "CONFLICT",
  "REQUIRES_ADDITIONAL_VERIFICATION",
  "EXPIRED",
] as const;
export type VerificationResult = (typeof VERIFICATION_RESULTS)[number];

export const PAYMENT_STATUSES = [
  "NOT_STARTED",
  "PENDING",
  "VERIFYING",
  "PAID",
  "NOT_SETTLED",
  "REFUNDED",
  "FAILED",
] as const;
export type PaymentStatus = (typeof PAYMENT_STATUSES)[number];

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
