import { parsePolicyTemplate } from "./template.ts";
import type { PolicyTemplateV1 } from "./types.ts";

const RAW_TEMPLATES: Record<string, unknown> = {
  "gate.open_closed.v1": {
    template_key: "gate.open_closed.v1",
    state_type: "BINARY",
    fresh_ttl_seconds: 1200,
    aging_ratio: 0.7,
    verification_class: "FAST",
    required_witnesses: 1,
    capture: {
      media_required: true,
      location_required: true,
    },
  },
  "parking.available_spaces.v1": {
    template_key: "parking.available_spaces.v1",
    state_type: "NUMERIC",
    fresh_ttl_seconds: 600,
    aging_ratio: 0.7,
    verification_class: "FAST",
    required_witnesses: 1,
    capture: {
      media_required: true,
      location_required: true,
    },
    numeric: {
      scale: 0,
      min: 0,
      conflict_tolerance: 1,
      allow_two_of_three: true,
    },
  },
  "visual.current_condition.v1": {
    template_key: "visual.current_condition.v1",
    state_type: "VISUAL",
    fresh_ttl_seconds: 3600,
    aging_ratio: 0.7,
    verification_class: "FAST",
    required_witnesses: 1,
    capture: {
      media_required: true,
      location_required: true,
    },
  },
};

const TEMPLATES = new Map<string, PolicyTemplateV1>(
  Object.entries(RAW_TEMPLATES).map(([key, value]) => [key, parsePolicyTemplate(value)]),
);

export function policyTemplateForKey(templateKey: string): PolicyTemplateV1 | null {
  const template = TEMPLATES.get(templateKey);
  return template ? structuredClone(template) : null;
}
