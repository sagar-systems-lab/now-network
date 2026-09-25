import { ApiFault } from "./errors.ts";
import type {
  StateProjectionContext,
  StateProjectionRepository,
  StateProjectionResult,
} from "./state-projection-repository.ts";
import {
  canonicalJson,
  parsePolicyTemplate,
} from "../../packages/policy/src/index.ts";
import { sha256Bytes } from "../../packages/contracts/src/refresh-intent.ts";
import type { PolicyTemplateV1 } from "../../packages/policy/src/types.ts";
import { deriveFreshness } from "../../packages/domain/src/freshness.ts";

const encoder = new TextEncoder();

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function parseFrozenPolicy(value: unknown): PolicyTemplateV1 {
  try {
    return parsePolicyTemplate(value);
  } catch {
    throw new ApiFault(
      409,
      "STATE_PROJECTION_INVALID",
      "Frozen state projection policy is invalid.",
    );
  }
}

function ensureProjectionAuthority(
  context: StateProjectionContext,
): Uint8Array {
  if (
    context.verificationStatus !== "VERIFIED" ||
    context.refreshExecutionHash === null ||
    context.refreshExecutionHash.length !== 32 ||
    context.verificationExecutionHash.length !== 32 ||
    !bytesEqual(
      context.refreshExecutionHash,
      context.verificationExecutionHash,
    )
  ) {
    throw new ApiFault(
      409,
      "STATE_PROJECTION_INVALID",
      "Verified state is not eligible for authoritative projection.",
    );
  }

  if (
    ![
      "VERIFIED",
      "SETTLEMENT_PENDING",
      "SETTLEMENT_VERIFYING",
      "COMPLETED",
    ].includes(context.refreshStatus)
  ) {
    throw new ApiFault(
      409,
      "STATE_PROJECTION_INVALID",
      "Refresh is not in a projectable state.",
    );
  }

  if (
    context.completedAt.getTime() >= context.refreshExpiresAt.getTime()
  ) {
    throw new ApiFault(
      410,
      "REFRESH_EXPIRED",
      "Verified refresh expired before it became projectable.",
    );
  }

  return context.refreshExecutionHash;
}

function canonicalValue(
  context: StateProjectionContext,
  policy: PolicyTemplateV1,
): unknown {
  if (policy.state_type !== context.stateType) {
    throw new ApiFault(
      409,
      "STATE_PROJECTION_INVALID",
      "Projection policy does not match the state type.",
    );
  }

  switch (context.stateType) {
    case "BINARY":
      if (
        typeof context.finalAnswer !== "string" ||
        context.finalAnswer.length === 0
      ) {
        throw new ApiFault(
          409,
          "STATE_PROJECTION_INVALID",
          "Verified binary result is malformed.",
        );
      }
      return {
        kind: "binary",
        value: context.finalAnswer,
      };

    case "NUMERIC":
      if (
        typeof context.finalAnswer !== "number" ||
        !Number.isSafeInteger(context.finalAnswer) ||
        policy.numeric === undefined
      ) {
        throw new ApiFault(
          409,
          "STATE_PROJECTION_INVALID",
          "Verified numeric result is malformed.",
        );
      }
      return {
        kind: "numeric",
        scaled_value: String(context.finalAnswer),
        scale: policy.numeric.scale,
        unit: context.unitCode,
      };

    case "VISUAL": {
      const evidenceId = context.matchingEvidenceIds[0];
      if (!evidenceId) {
        throw new ApiFault(
          409,
          "STATE_PROJECTION_INVALID",
          "Verified visual result has no matching evidence identity.",
        );
      }
      return {
        kind: "visual",
        evidence_id: evidenceId,
      };
    }
  }
}

function freshnessWindow(
  observedAt: Date,
  policy: PolicyTemplateV1,
): { agingAt: Date; freshUntil: Date } {
  const ttlMs = policy.fresh_ttl_seconds * 1_000;
  const agingOffsetMs = Math.floor(ttlMs * policy.aging_ratio);
  if (
    !Number.isSafeInteger(ttlMs) ||
    ttlMs <= 0 ||
    !Number.isSafeInteger(agingOffsetMs) ||
    agingOffsetMs < 0 ||
    agingOffsetMs > ttlMs
  ) {
    throw new ApiFault(
      409,
      "STATE_PROJECTION_INVALID",
      "Frozen freshness policy cannot produce a valid window.",
    );
  }
  return {
    agingAt: new Date(observedAt.getTime() + agingOffsetMs),
    freshUntil: new Date(observedAt.getTime() + ttlMs),
  };
}

function projectionFault(
  result: Exclude<
    StateProjectionResult,
    { kind: "projected" | "replayed" | "superseded" }
  >,
): ApiFault {
  switch (result.kind) {
    case "not_found":
      return new ApiFault(
        404,
        "REFRESH_NOT_FOUND",
        "Refresh projection authority was not found.",
      );
    case "expired":
      return new ApiFault(
        410,
        "REFRESH_EXPIRED",
        "Refresh expired before state projection.",
      );
    case "not_verified":
    case "authority_conflict":
      return new ApiFault(
        409,
        "STATE_PROJECTION_INVALID",
        "Verified state is not eligible for projection.",
      );
  }
}

export class StateProjectionService {
  constructor(
    private readonly repository: StateProjectionRepository,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async project(
    refreshId: string,
    verificationResultId: string,
  ): Promise<Record<string, unknown>> {
    const context = await this.repository.getContext(
      refreshId,
      verificationResultId,
    );
    if (context === null) {
      throw projectionFault({ kind: "not_found" });
    }

    const now = this.now();
    const executionHash = ensureProjectionAuthority(context);
    const policy = parseFrozenPolicy(context.proofPolicySnapshot);
    const currentValue = canonicalValue(context, policy);
    const currentValueDigest = await sha256Bytes(
      encoder.encode(canonicalJson(currentValue)),
    );
    const { agingAt, freshUntil } = freshnessWindow(
      context.completedAt,
      policy,
    );

    if (
      context.observationEarliest.getTime() >
        context.observationLatest.getTime() ||
      context.observationLatest.getTime() > context.completedAt.getTime()
    ) {
      throw new ApiFault(
        409,
        "STATE_PROJECTION_INVALID",
        "Verified observation window is invalid.",
      );
    }

    const result = await this.repository.project({
      refreshId: context.refreshId,
      verificationResultId: context.verificationResultId,
      stateId: context.stateId,
      stateVersion: context.stateVersion,
      currentValue,
      currentValueDigest,
      observedAt: context.completedAt,
      observationEarliest: context.observationEarliest,
      observationLatest: context.observationLatest,
      agingAt,
      freshUntil,
      verificationClass: context.verificationClass,
      executionHash,
    });
    if (
      result.kind !== "projected" &&
      result.kind !== "replayed" &&
      result.kind !== "superseded"
    ) {
      throw projectionFault(result);
    }

    return {
      state_id: result.stateId,
      refresh_id: result.refreshId,
      verification_result_id: result.verificationResultId,
      state_revision: result.stateRevision,
      history_id: result.historyId,
      observed_at: result.observedAt.toISOString(),
      aging_at: result.agingAt.toISOString(),
      fresh_until: result.freshUntil.toISOString(),
      current_value: result.currentValue,
      projected: result.kind === "projected",
      replayed: result.kind === "replayed",
      superseded: result.kind === "superseded",
      freshness: result.kind === "superseded"
        ? null
        : deriveFreshness(now.getTime(), {
          observedAtMs: result.observedAt.getTime(),
          agingAtMs: result.agingAt.getTime(),
          freshUntilMs: result.freshUntil.getTime(),
        }),
    };
  }
}
