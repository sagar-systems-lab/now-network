import type { FreshnessStatus } from "../../contracts/src/core.ts";

export interface FreshnessWindow {
  observedAtMs: number;
  agingAtMs: number;
  freshUntilMs: number;
}

function assertFiniteTimestamp(value: number, name: string): void {
  if (!Number.isFinite(value)) {
    throw new RangeError(`${name} must be a finite timestamp`);
  }
}

export function validateFreshnessWindow(window: FreshnessWindow): void {
  assertFiniteTimestamp(window.observedAtMs, "observedAtMs");
  assertFiniteTimestamp(window.agingAtMs, "agingAtMs");
  assertFiniteTimestamp(window.freshUntilMs, "freshUntilMs");

  if (window.observedAtMs > window.agingAtMs) {
    throw new RangeError("observedAtMs must be <= agingAtMs");
  }

  if (window.agingAtMs > window.freshUntilMs) {
    throw new RangeError("agingAtMs must be <= freshUntilMs");
  }
}

export function deriveFreshness(
  nowMs: number,
  window: FreshnessWindow,
): FreshnessStatus {
  assertFiniteTimestamp(nowMs, "nowMs");
  validateFreshnessWindow(window);

  if (nowMs < window.agingAtMs) {
    return "LIVE";
  }

  if (nowMs < window.freshUntilMs) {
    return "AGING";
  }

  return "STALE";
}
