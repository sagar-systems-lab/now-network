import type { FreshnessStatus } from "../../contracts/src/core.ts";
import {
  deriveFreshness,
  type FreshnessWindow,
  validateFreshnessWindow,
} from "../../domain/src/freshness.ts";
import type { PolicyTemplateV1 } from "./types.ts";

export function freshnessWindowFromPolicy(
  observedAtMs: number,
  policy: PolicyTemplateV1,
): FreshnessWindow {
  if (!Number.isFinite(observedAtMs)) {
    throw new RangeError("observedAtMs must be finite");
  }

  const ttlMs = policy.fresh_ttl_seconds * 1_000;
  const agingOffsetMs = Math.floor(ttlMs * policy.aging_ratio);
  const window = {
    observedAtMs,
    agingAtMs: observedAtMs + agingOffsetMs,
    freshUntilMs: observedAtMs + ttlMs,
  };

  validateFreshnessWindow(window);
  return window;
}

export function derivePolicyFreshness(
  nowMs: number,
  observedAtMs: number,
  policy: PolicyTemplateV1,
): FreshnessStatus {
  return deriveFreshness(nowMs, freshnessWindowFromPolicy(observedAtMs, policy));
}
