import type { VerificationClass } from "../../contracts/src/core.ts";
import type { StateType } from "../../contracts/src/lifecycle.ts";

export interface CapturePolicy {
  media_required: boolean;
  video_required?: boolean;
  location_required: boolean;
}

export interface NumericPolicy {
  scale: number;
  min: number;
  max?: number;
  conflict_tolerance: number;
  allow_two_of_three?: boolean;
}

export interface PolicyTemplateV1 {
  template_key: string;
  state_type: StateType;
  fresh_ttl_seconds: number;
  aging_ratio: number;
  verification_class: VerificationClass;
  required_witnesses: number;
  capture: CapturePolicy;
  numeric?: NumericPolicy;
}

export type PolicyReasonCode =
  | "REPORTS_AGREE"
  | "NUMERIC_WITHIN_TOLERANCE"
  | "MAJORITY_RESOLUTION"
  | "INSUFFICIENT_WITNESSES"
  | "WITNESS_CONFLICT";

export type ConflictDecision =
  | {
    decision: "AGREEMENT";
    reason_codes: readonly PolicyReasonCode[];
    matching_indexes: readonly number[];
    resolved_value?: string | number;
  }
  | {
    decision: "REQUIRE_MORE_EVIDENCE";
    reason_codes: readonly PolicyReasonCode[];
  }
  | {
    decision: "CONFLICT";
    reason_codes: readonly PolicyReasonCode[];
  };
