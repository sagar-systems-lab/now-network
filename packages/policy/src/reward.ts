import type { VerificationClass } from "../../contracts/src/core.ts";

const BASIS_POINTS = 10_000n;

export interface RewardContribution {
  amount_atomic: bigint;
  status:
    | "PREPARING"
    | "SUBMITTED"
    | "CONFIRMING"
    | "CONFIRMED"
    | "FINALIZED"
    | "REFUNDED"
    | "FAILED"
    | "UNKNOWN";
}

export interface RewardSuggestionInput {
  base_atomic: bigint;
  min_atomic: bigint;
  max_suggested_atomic: bigint;
  friendly_step_atomic: bigint;
  contributor_distance_m: number;
  remaining_seconds: number;
  verification_class: VerificationClass;
}

export interface RewardSuggestion {
  raw_atomic: bigint;
  suggested_atomic: bigint;
  distance_factor_bps: number;
  urgency_factor_bps: number;
  verification_factor_bps: number;
  clamped: boolean;
}

export function aggregateConfirmedContributions(
  contributions: readonly RewardContribution[],
): bigint {
  let total = 0n;

  for (const contribution of contributions) {
    if (contribution.amount_atomic < 0n) {
      throw new RangeError("contribution amount must be >= 0");
    }

    if (contribution.status === "CONFIRMED" || contribution.status === "FINALIZED") {
      total += contribution.amount_atomic;
    }
  }

  return total;
}

function distanceFactorBps(distanceM: number): number {
  if (!Number.isFinite(distanceM) || distanceM < 0) {
    throw new RangeError("contributor_distance_m must be finite and >= 0");
  }

  if (distanceM <= 100) return 10_000;
  if (distanceM <= 500) return 12_500;
  if (distanceM <= 1_000) return 15_000;
  return 20_000;
}

function urgencyFactorBps(remainingSeconds: number): number {
  if (!Number.isFinite(remainingSeconds) || remainingSeconds < 0) {
    throw new RangeError("remaining_seconds must be finite and >= 0");
  }

  if (remainingSeconds > 600) return 10_000;
  if (remainingSeconds >= 300) return 11_500;
  if (remainingSeconds >= 120) return 13_500;
  return 10_000;
}

function verificationFactorBps(verificationClass: VerificationClass): number {
  switch (verificationClass) {
    case "FAST":
      return 10_000;
    case "CORROBORATED":
      return 13_500;
    case "STRICT":
      return 17_500;
  }
}

function multiplyBps(value: bigint, factorBps: number): bigint {
  return (value * BigInt(factorBps)) / BASIS_POINTS;
}

function roundUpToStep(value: bigint, step: bigint): bigint {
  if (step <= 0n) throw new RangeError("friendly_step_atomic must be > 0");
  if (value === 0n) return 0n;
  return ((value + step - 1n) / step) * step;
}

export function suggestReward(input: RewardSuggestionInput): RewardSuggestion {
  if (input.base_atomic < 0n) throw new RangeError("base_atomic must be >= 0");
  if (input.min_atomic < 0n) throw new RangeError("min_atomic must be >= 0");
  if (input.max_suggested_atomic < input.min_atomic) {
    throw new RangeError("max_suggested_atomic must be >= min_atomic");
  }

  const distanceBps = distanceFactorBps(input.contributor_distance_m);
  const urgencyBps = urgencyFactorBps(input.remaining_seconds);
  const verificationBps = verificationFactorBps(input.verification_class);

  let raw = multiplyBps(input.base_atomic, distanceBps);
  raw = multiplyBps(raw, urgencyBps);
  raw = multiplyBps(raw, verificationBps);

  const rounded = roundUpToStep(raw, input.friendly_step_atomic);
  const bounded = rounded < input.min_atomic
    ? input.min_atomic
    : rounded > input.max_suggested_atomic
    ? input.max_suggested_atomic
    : rounded;

  return {
    raw_atomic: raw,
    suggested_atomic: bounded,
    distance_factor_bps: distanceBps,
    urgency_factor_bps: urgencyBps,
    verification_factor_bps: verificationBps,
    clamped: bounded !== rounded,
  };
}
