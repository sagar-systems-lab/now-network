import type { ConflictDecision } from "./types.ts";

function requireWitnessCount(requiredWitnesses: number): void {
  if (!Number.isSafeInteger(requiredWitnesses) || requiredWitnesses < 1 || requiredWitnesses > 3) {
    throw new RangeError("requiredWitnesses must be an integer between 1 and 3");
  }
}

export function evaluateBinaryConflict(
  reports: readonly string[],
  requiredWitnesses: number,
  allowTwoOfThree = false,
): ConflictDecision {
  requireWitnessCount(requiredWitnesses);

  if (reports.length < requiredWitnesses) {
    return {
      decision: "REQUIRE_MORE_EVIDENCE",
      reason_codes: ["INSUFFICIENT_WITNESSES"],
    };
  }

  if (reports.length === 0) {
    return {
      decision: "REQUIRE_MORE_EVIDENCE",
      reason_codes: ["INSUFFICIENT_WITNESSES"],
    };
  }

  if (reports.every((value) => value === reports[0])) {
    return {
      decision: "AGREEMENT",
      reason_codes: ["REPORTS_AGREE"],
      matching_indexes: reports.map((_, index) => index),
      resolved_value: reports[0],
    };
  }

  if (allowTwoOfThree && reports.length >= 3) {
    const counts = new Map<string, number[]>();

    reports.forEach((value, index) => {
      const indexes = counts.get(value) ?? [];
      indexes.push(index);
      counts.set(value, indexes);
    });

    const majority = [...counts.entries()]
      .filter(([, indexes]) => indexes.length >= 2)
      .sort(([left], [right]) => left.localeCompare(right))[0];

    if (majority !== undefined) {
      return {
        decision: "AGREEMENT",
        reason_codes: ["MAJORITY_RESOLUTION"],
        matching_indexes: majority[1],
        resolved_value: majority[0],
      };
    }
  }

  return {
    decision: "CONFLICT",
    reason_codes: ["WITNESS_CONFLICT"],
  };
}

function requireScaledInteger(value: number, name: string): void {
  if (!Number.isSafeInteger(value)) {
    throw new TypeError(`${name} must be a scaled safe integer`);
  }
}

export function evaluateNumericConflict(
  reports: readonly number[],
  requiredWitnesses: number,
  tolerance: number,
  allowTwoOfThree = false,
): ConflictDecision {
  requireWitnessCount(requiredWitnesses);
  requireScaledInteger(tolerance, "tolerance");
  if (tolerance < 0) throw new RangeError("tolerance must be >= 0");

  reports.forEach((value, index) => requireScaledInteger(value, `reports[${index}]`));

  if (reports.length < requiredWitnesses || reports.length === 0) {
    return {
      decision: "REQUIRE_MORE_EVIDENCE",
      reason_codes: ["INSUFFICIENT_WITNESSES"],
    };
  }

  const minimum = Math.min(...reports);
  const maximum = Math.max(...reports);

  if (maximum - minimum <= tolerance) {
    const allEqual = reports.every((value) => value === reports[0]);

    return {
      decision: "AGREEMENT",
      reason_codes: allEqual ? ["REPORTS_AGREE"] : ["NUMERIC_WITHIN_TOLERANCE"],
      matching_indexes: reports.map((_, index) => index),
      ...(allEqual ? { resolved_value: reports[0] } : {}),
    };
  }

  if (allowTwoOfThree && reports.length >= 3) {
    const candidates: Array<{ left: number; right: number; spread: number; low: number }> = [];

    for (let left = 0; left < reports.length; left++) {
      for (let right = left + 1; right < reports.length; right++) {
        const spread = Math.abs(reports[left] - reports[right]);
        if (spread <= tolerance) {
          candidates.push({
            left,
            right,
            spread,
            low: Math.min(reports[left], reports[right]),
          });
        }
      }
    }

    candidates.sort((a, b) =>
      a.spread - b.spread || a.low - b.low || a.left - b.left || a.right - b.right
    );

    const winner = candidates[0];
    if (winner !== undefined) {
      const sameValue = reports[winner.left] === reports[winner.right];

      return {
        decision: "AGREEMENT",
        reason_codes: ["MAJORITY_RESOLUTION"],
        matching_indexes: [winner.left, winner.right],
        ...(sameValue ? { resolved_value: reports[winner.left] } : {}),
      };
    }
  }

  return {
    decision: "CONFLICT",
    reason_codes: ["WITNESS_CONFLICT"],
  };
}
