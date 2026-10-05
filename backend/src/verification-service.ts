import { ApiFault } from "./errors.ts";
import type { ActorRecord } from "./identity-repository.ts";
import type {
  PersistVerificationResult,
  VerificationContext,
  VerificationEvidence,
  VerificationOutcome,
  VerificationRepository,
} from "./verification-repository.ts";
import type { VerificationResult } from "../../packages/contracts/src/core.ts";
import type { VerificationReasonCode } from "../../packages/contracts/src/reason-codes.ts";
import {
  canonicalJson,
  evaluateBinaryConflict,
  evaluateNumericConflict,
  parsePolicyTemplate,
  policyVersion,
} from "../../packages/policy/src/index.ts";
import { sha256Bytes } from "../../packages/contracts/src/refresh-intent.ts";
import { transitionEvidence } from "../../packages/domain/src/evidence-machine.ts";
import { transitionRefresh } from "../../packages/domain/src/refresh-machine.ts";
import { transitionVerification } from "../../packages/domain/src/verification-machine.ts";
import type { PolicyTemplateV1 } from "../../packages/policy/src/types.ts";

const encoder = new TextEncoder();
export const DEFAULT_VERIFIER_BUILD = "now-verifier-v1";

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function bytesToHex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function assertActorActive(actor: ActorRecord): void {
  if (actor.status === "DISABLED") {
    throw new ApiFault(403, "ACTOR_DISABLED", "This actor is disabled.");
  }
  if (actor.status === "RESTRICTED") {
    throw new ApiFault(403, "ACTOR_RESTRICTED", "This actor is restricted.");
  }
}

function parseFrozenPolicy(value: unknown): PolicyTemplateV1 {
  try {
    return parsePolicyTemplate(value);
  } catch {
    throw new ApiFault(
      409,
      "VERIFICATION_NOT_ELIGIBLE",
      "Frozen verification policy is invalid.",
    );
  }
}

function authorizedActor(context: VerificationContext, actorId: string): boolean {
  return context.requesterActorId === actorId ||
    context.evidence.some((evidence) => evidence.actorId === actorId);
}

function allEvidenceIds(evidence: readonly VerificationEvidence[]): string[] {
  return evidence.map((item) => item.evidenceId);
}

function latestMatchingAnswer(
  evidence: readonly VerificationEvidence[],
  indexes: readonly number[],
): unknown | null {
  if (indexes.length === 0) return null;
  const candidates = indexes
    .map((index) => evidence[index])
    .filter((item): item is VerificationEvidence => item !== undefined)
    .sort((left, right) =>
      left.committedAt.getTime() - right.committedAt.getTime() ||
      left.evidenceId.localeCompare(right.evidenceId)
    );
  return candidates.at(-1)?.answerValue ?? null;
}

function reasonCodesForResult(
  result: VerificationResult,
): VerificationReasonCode[] {
  switch (result) {
    case "CONFLICT":
      return ["WITNESS_CONFLICT"];
    case "REQUIRES_ADDITIONAL_VERIFICATION":
      return ["INSUFFICIENT_WITNESSES"];
    case "REJECTED":
      return ["POLICY_INTERNAL_ERROR"];
    case "VERIFIED":
    case "EXPIRED":
      return [];
  }
}

function evaluateVisual(
  evidence: readonly VerificationEvidence[],
  requiredWitnesses: number,
): {
  decision: "AGREEMENT" | "CONFLICT" | "REQUIRE_MORE_EVIDENCE";
  reason_codes: string[];
  matching_indexes?: number[];
  resolved_value?: unknown;
} {
  if (evidence.length < requiredWitnesses) {
    return {
      decision: "REQUIRE_MORE_EVIDENCE",
      reason_codes: ["INSUFFICIENT_WITNESSES"],
    };
  }

  const canonical = evidence.map((item) => canonicalJson(item.answerValue));
  if (canonical.every((value) => value === canonical[0])) {
    return {
      decision: "AGREEMENT",
      reason_codes: ["REPORTS_AGREE"],
      matching_indexes: evidence.map((_, index) => index),
      resolved_value: evidence[0]?.answerValue,
    };
  }

  return {
    decision: "CONFLICT",
    reason_codes: ["WITNESS_CONFLICT"],
  };
}

function evaluatePolicy(
  context: VerificationContext,
): {
  result: VerificationResult;
  policyReasonCodes: string[];
  matchingIndexes: number[];
  finalAnswer: unknown | null;
} {
  const policy = parseFrozenPolicy(context.proofPolicySnapshot);
  const reports = context.evidence;

  if (reports.length < policy.required_witnesses) {
    return {
      result: "REQUIRES_ADDITIONAL_VERIFICATION",
      policyReasonCodes: ["INSUFFICIENT_WITNESSES"],
      matchingIndexes: [],
      finalAnswer: null,
    };
  }

  if (context.stateType === "NUMERIC") {
    const values = reports.map((item) => item.answerValue);
    if (!values.every((value) => typeof value === "number" && Number.isSafeInteger(value))) {
      throw new ApiFault(
        409,
        "VERIFICATION_NOT_ELIGIBLE",
        "Committed evidence does not match the frozen answer schema.",
      );
    }

    const numeric = policy.numeric;
    if (!numeric) {
      throw new ApiFault(
        409,
        "VERIFICATION_NOT_ELIGIBLE",
        "Verification policy is incomplete for the frozen state.",
      );
    }

    const decision = evaluateNumericConflict(
      values as number[],
      policy.required_witnesses,
      numeric.conflict_tolerance,
      numeric.allow_two_of_three ?? false,
    );
    if (decision.decision === "CONFLICT") {
      return {
        result: "CONFLICT",
        policyReasonCodes: [...decision.reason_codes],
        matchingIndexes: [],
        finalAnswer: null,
      };
    }
    if (decision.decision === "REQUIRE_MORE_EVIDENCE") {
      return {
        result: "REQUIRES_ADDITIONAL_VERIFICATION",
        policyReasonCodes: [...decision.reason_codes],
        matchingIndexes: [],
        finalAnswer: null,
      };
    }

    const finalAnswer = decision.resolved_value ??
      latestMatchingAnswer(reports, decision.matching_indexes);
    return {
      result: "VERIFIED",
      policyReasonCodes: [...decision.reason_codes],
      matchingIndexes: [...decision.matching_indexes],
      finalAnswer,
    };
  }

  if (context.stateType === "BINARY") {
    const values = reports.map((item) => item.answerValue);
    if (!values.every((value) => typeof value === "string" && value.length > 0)) {
      throw new ApiFault(
        409,
        "VERIFICATION_NOT_ELIGIBLE",
        "Committed evidence does not match the frozen answer schema.",
      );
    }

    const decision = evaluateBinaryConflict(
      values as string[],
      policy.required_witnesses,
    );
    if (decision.decision === "CONFLICT") {
      return {
        result: "CONFLICT",
        policyReasonCodes: [...decision.reason_codes],
        matchingIndexes: [],
        finalAnswer: null,
      };
    }
    if (decision.decision === "REQUIRE_MORE_EVIDENCE") {
      return {
        result: "REQUIRES_ADDITIONAL_VERIFICATION",
        policyReasonCodes: [...decision.reason_codes],
        matchingIndexes: [],
        finalAnswer: null,
      };
    }

    return {
      result: "VERIFIED",
      policyReasonCodes: [...decision.reason_codes],
      matchingIndexes: [...decision.matching_indexes],
      finalAnswer: decision.resolved_value ??
        latestMatchingAnswer(reports, decision.matching_indexes),
    };
  }

  const decision = evaluateVisual(reports, policy.required_witnesses);
  if (decision.decision === "CONFLICT") {
    return {
      result: "CONFLICT",
      policyReasonCodes: decision.reason_codes,
      matchingIndexes: [],
      finalAnswer: null,
    };
  }
  if (decision.decision === "REQUIRE_MORE_EVIDENCE") {
    return {
      result: "REQUIRES_ADDITIONAL_VERIFICATION",
      policyReasonCodes: decision.reason_codes,
      matchingIndexes: [],
      finalAnswer: null,
    };
  }

  return {
    result: "VERIFIED",
    policyReasonCodes: decision.reason_codes,
    matchingIndexes: decision.matching_indexes ?? [],
    finalAnswer: decision.resolved_value ?? null,
  };
}

function outcomeStatus(
  result: VerificationResult,
): VerificationOutcome["status"] {
  switch (result) {
    case "VERIFIED":
      return "VERIFIED";
    case "CONFLICT":
      return "CONFLICT";
    case "REQUIRES_ADDITIONAL_VERIFICATION":
      return "WAITING_FOR_MORE_EVIDENCE";
    case "REJECTED":
    case "EXPIRED":
      return "REJECTED";
  }
}

function persistedFault(
  result: Exclude<PersistVerificationResult, { kind: "recorded" | "replayed" }>,
): ApiFault {
  switch (result.kind) {
    case "not_found":
    case "actor_mismatch":
      return new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
    case "not_eligible":
      return new ApiFault(
        409,
        "VERIFICATION_NOT_ELIGIBLE",
        "Refresh is not eligible for verification.",
      );
    case "expired":
      return new ApiFault(410, "REFRESH_EXPIRED", "Refresh has expired.");
    case "evidence_set_changed":
    case "policy_changed":
    case "execution_conflict":
      return new ApiFault(
        409,
        "REVISION_CONFLICT",
        "Verification authority changed before the result could be committed.",
        true,
      );
  }
}

export class VerificationService {
  constructor(
    private readonly repository: VerificationRepository,
    private readonly verifierBuild = DEFAULT_VERIFIER_BUILD,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async verify(
    actor: ActorRecord,
    refreshId: string,
  ): Promise<{ status: number; data: Record<string, unknown> }> {
    assertActorActive(actor);
    const context = await this.repository.getContext(refreshId);
    if (context === null || !authorizedActor(context, actor.actorId)) {
      throw new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
    }

    const policy = parseFrozenPolicy(context.proofPolicySnapshot);
    if (policy.state_type !== context.stateType) {
      throw new ApiFault(
        409,
        "VERIFICATION_NOT_ELIGIBLE",
        "Verification policy does not match the frozen state type.",
      );
    }
    const currentPolicyVersion = policyVersion(policy.template_key);
    const evidenceSetRevision = context.evidence.length;
    if (evidenceSetRevision < 1) {
      throw new ApiFault(
        409,
        "VERIFICATION_NOT_ELIGIBLE",
        "No committed evidence is available for verification.",
      );
    }

    if (
      context.existing !== null &&
      context.existing.evidenceSetRevision === evidenceSetRevision &&
      context.existing.policyVersion === currentPolicyVersion &&
      ["VERIFIED", "CONFLICT", "WAITING_FOR_MORE_EVIDENCE", "REJECTED"].includes(
        context.existing.status,
      )
    ) {
      const replayResult: VerificationResult = context.existing.status === "VERIFIED"
        ? "VERIFIED"
        : context.existing.status === "CONFLICT"
        ? "CONFLICT"
        : context.existing.status === "WAITING_FOR_MORE_EVIDENCE"
        ? "REQUIRES_ADDITIONAL_VERIFICATION"
        : "REJECTED";
      return {
        status: 200,
        data: {
          verification_result_id: context.existing.verificationResultId,
          refresh_id: context.refreshId,
          result: replayResult,
          status: context.existing.status,
          reason_codes: context.existing.reasonCodes,
          evidence_ids: context.existing.evidenceIds,
          final_answer: context.existing.finalAnswer,
          evidence_set_revision: evidenceSetRevision,
          policy_version: currentPolicyVersion,
          replayed: true,
          next_step: replayResult === "VERIFIED" ? "PROJECTION" : replayResult === "CONFLICT" ||
              replayResult === "REQUIRES_ADDITIONAL_VERIFICATION"
            ? "ADDITIONAL_VERIFICATION"
            : "REVIEW",
        },
      };
    }

    const observedAt = this.now();
    if (context.refreshExpiresAt.getTime() <= observedAt.getTime()) {
      throw new ApiFault(410, "REFRESH_EXPIRED", "Refresh has expired.");
    }

    if (context.refreshStatus !== "EVIDENCE_SUBMITTED") {
      throw new ApiFault(
        409,
        "VERIFICATION_NOT_ELIGIBLE",
        "Refresh is not ready for verification.",
      );
    }

    const executionHash = context.executionHash;
    if (
      executionHash === null ||
      executionHash.length !== 32 ||
      context.intentCoreHash.length !== 32
    ) {
      throw new ApiFault(
        409,
        "VERIFICATION_NOT_ELIGIBLE",
        "Locked execution identity is unavailable.",
      );
    }

    for (const evidence of context.evidence) {
      if (
        evidence.intentCoreHash.length !== 32 ||
        evidence.executionHash.length !== 32 ||
        !bytesEqual(evidence.intentCoreHash, context.intentCoreHash) ||
        !bytesEqual(evidence.executionHash, executionHash) ||
        evidence.answerType !== context.stateType
      ) {
        throw new ApiFault(
          409,
          "VERIFICATION_NOT_ELIGIBLE",
          "Evidence authority does not match the refresh.",
        );
      }
      if (
        policy.capture.video_required && (!evidence.video || evidence.video.duration_ms < 3000 ||
          evidence.video.duration_ms > 15000 || !/^[a-f0-9]{64}$/u.test(evidence.video.sha256))
      ) {
        throw new ApiFault(
          409,
          "VERIFICATION_NOT_ELIGIBLE",
          "Required video proof is unavailable.",
        );
      }
      if (
        policy.capture.media_required &&
        (
          evidence.mediaSha256 === null ||
          evidence.mediaSha256.length !== 32 ||
          evidence.mediaSizeBytes === null ||
          evidence.mediaSizeBytes <= 0 ||
          evidence.mediaMime === null
        )
      ) {
        throw new ApiFault(
          409,
          "VERIFICATION_NOT_ELIGIBLE",
          "Required media integrity evidence is unavailable.",
        );
      }
      if (policy.capture.location_required && evidence.locationSampleCount < 1) {
        throw new ApiFault(
          409,
          "VERIFICATION_NOT_ELIGIBLE",
          "Required location context is unavailable.",
        );
      }
      if (
        evidence.serverObservationEarliest.getTime() >
          evidence.serverObservationLatest.getTime() ||
        evidence.serverObservationLatest.getTime() >
          context.evidenceDeadline.getTime() ||
        evidence.serverObservationLatest.getTime() >
          context.refreshExpiresAt.getTime()
      ) {
        throw new ApiFault(
          409,
          "VERIFICATION_NOT_ELIGIBLE",
          "Evidence observation timing is outside the frozen refresh authority.",
        );
      }
    }

    transitionVerification("QUEUED", "START", {
      evidenceSetFrozen: true,
      policySnapshotValid: true,
    });
    transitionRefresh("EVIDENCE_SUBMITTED", "VERIFICATION_STARTED", {
      evidenceSetCommitted: true,
    });
    for (const evidence of context.evidence) {
      if (evidence.status === "COMMITTED") {
        transitionEvidence("COMMITTED", "VERIFY", {
          serverCommitObserved: true,
        });
      }
    }

    const evaluated = evaluatePolicy(context);
    const status = outcomeStatus(evaluated.result);
    const reasonCodes = reasonCodesForResult(evaluated.result);
    const matchingEvidenceIds = evaluated.matchingIndexes
      .map((index) => context.evidence[index]?.evidenceId)
      .filter((id): id is string => typeof id === "string");

    const outcome: VerificationOutcome = {
      result: evaluated.result,
      status,
      reasonCodes,
      policyReasonCodes: evaluated.policyReasonCodes,
      finalAnswer: evaluated.finalAnswer,
      matchingEvidenceIds,
      verificationTrace: {
        policy_template_key: policy.template_key,
        policy_version: currentPolicyVersion,
        state_type: context.stateType,
        evidence_set_revision: evidenceSetRevision,
        evidence_order: allEvidenceIds(context.evidence),
        policy_decision_reason_codes: evaluated.policyReasonCodes,
        matching_evidence_ids: matchingEvidenceIds,
        numeric_selection_rule: context.stateType === "NUMERIC" && evaluated.result === "VERIFIED"
          ? "resolved-value-or-latest-matching-evidence"
          : null,
      },
      locationSummary: {
        required: policy.capture.location_required,
        evidence_with_location:
          context.evidence.filter((item) => item.locationSampleCount > 0).length,
        sample_count: context.evidence.reduce(
          (total, item) => total + item.locationSampleCount,
          0,
        ),
      },
      freshnessSummary: {
        earliest_observation: new Date(
          Math.min(
            ...context.evidence.map((item) => item.serverObservationEarliest.getTime()),
          ),
        ).toISOString(),
        latest_observation: new Date(
          Math.max(
            ...context.evidence.map((item) => item.serverObservationLatest.getTime()),
          ),
        ).toISOString(),
        evidence_deadline: context.evidenceDeadline.toISOString(),
        refresh_expires_at: context.refreshExpiresAt.toISOString(),
      },
      mediaIntegritySummary: {
        required: policy.capture.media_required,
        video_required: policy.capture.video_required === true,
        bound_video_count: context.evidence.filter((item) => item.video != null).length,
        bound_media_count:
          context.evidence.filter((item) =>
            item.mediaSha256 !== null && item.mediaSha256.length === 32
          ).length,
      },
      replaySummary: {
        exact_media_replay_guarded_at_commit: true,
        evidence_ids: allEvidenceIds(context.evidence),
      },
      conflictSummary: evaluated.result === "CONFLICT"
        ? {
          reason: "WITNESS_CONFLICT",
          evidence_ids: allEvidenceIds(context.evidence),
        }
        : null,
    };

    switch (evaluated.result) {
      case "VERIFIED":
        transitionVerification("RUNNING", "PASS", { result: "VERIFIED" });
        transitionRefresh("VERIFYING", "VERIFIED", {
          verificationResult: "VERIFIED",
        });
        for (const evidence of context.evidence) {
          if (evidence.status === "COMMITTED") {
            if (matchingEvidenceIds.includes(evidence.evidenceId)) {
              transitionEvidence("VERIFYING", "PASS", {
                verificationResult: "VERIFIED",
              });
            } else {
              transitionEvidence("VERIFYING", "CONFLICT", {
                verificationResult: "CONFLICT",
              });
            }
          }
        }
        break;
      case "CONFLICT":
        transitionVerification("RUNNING", "CONFLICT", { result: "CONFLICT" });
        transitionRefresh("VERIFYING", "CONFLICT_DETECTED", {
          verificationResult: "CONFLICT",
        });
        transitionRefresh("CONFLICT", "REQUEST_ADDITIONAL_EVIDENCE");
        for (const evidence of context.evidence) {
          if (evidence.status === "COMMITTED") {
            transitionEvidence("VERIFYING", "CONFLICT", {
              verificationResult: "CONFLICT",
            });
          }
        }
        break;
      case "REQUIRES_ADDITIONAL_VERIFICATION":
        transitionVerification("RUNNING", "REQUIRE_MORE_EVIDENCE", {
          result: "REQUIRES_ADDITIONAL_VERIFICATION",
        });
        transitionRefresh("VERIFYING", "MORE_EVIDENCE_REQUIRED", {
          verificationResult: "REQUIRES_ADDITIONAL_VERIFICATION",
        });
        break;
      case "REJECTED":
        transitionVerification("RUNNING", "REJECT", { result: "REJECTED" });
        transitionRefresh("VERIFYING", "REJECTED_RECOVERABLE");
        break;
      case "EXPIRED":
        throw new ApiFault(410, "REFRESH_EXPIRED", "Refresh has expired.");
    }

    const canonicalDigest = await sha256Bytes(
      encoder.encode(
        canonicalJson({
          refresh_id: context.refreshId,
          evidence_set_revision: evidenceSetRevision,
          policy_version: currentPolicyVersion,
          evidence_ids: allEvidenceIds(context.evidence),
          result: evaluated.result,
          reason_codes: reasonCodes,
          policy_reason_codes: evaluated.policyReasonCodes,
          final_answer: evaluated.finalAnswer,
          execution_hash: bytesToHex(executionHash),
          verifier_build: this.verifierBuild,
        }),
      ),
    );

    const persisted = await this.repository.persist({
      actorId: actor.actorId,
      refreshId: context.refreshId,
      verificationResultId: crypto.randomUUID(),
      evidenceSetRevision,
      policyVersion: currentPolicyVersion,
      policySnapshot: policy,
      evidenceIds: allEvidenceIds(context.evidence),
      executionHash,
      canonicalDigest,
      outcome,
      verifierBuild: this.verifierBuild,
      observedAt,
    });
    if (persisted.kind !== "recorded" && persisted.kind !== "replayed") {
      throw persistedFault(persisted);
    }

    return {
      status: persisted.kind === "recorded" ? 201 : 200,
      data: {
        verification_result_id: persisted.verificationResultId,
        refresh_id: persisted.refreshId,
        result: persisted.result,
        status: persisted.status,
        reason_codes: persisted.reasonCodes,
        evidence_ids: persisted.evidenceIds,
        final_answer: persisted.finalAnswer,
        evidence_set_revision: persisted.evidenceSetRevision,
        policy_version: persisted.policyVersion,
        canonical_digest: bytesToHex(persisted.canonicalDigest),
        completed_at: persisted.completedAt.toISOString(),
        refresh_status: persisted.refreshStatus,
        refresh_revision: persisted.refreshRevision,
        replayed: persisted.kind === "replayed",
        next_step: persisted.result === "VERIFIED"
          ? "PROJECTION"
          : persisted.result === "CONFLICT" ||
              persisted.result === "REQUIRES_ADDITIONAL_VERIFICATION"
          ? "ADDITIONAL_VERIFICATION"
          : "REVIEW",
      },
    };
  }
}
