import gate from "../templates/gate.open_closed.v1.json" with { type: "json" };
import parking from "../templates/parking.available_spaces.v1.json" with { type: "json" };
import visual from "../templates/visual.current_condition.v1.json" with { type: "json" };
import { parsePolicyTemplate } from "./template.ts";
import type { PolicyTemplateV1 } from "./types.ts";

const TEMPLATES = new Map<string, PolicyTemplateV1>(
  [gate, parking, visual].map((value) => {
    const template = parsePolicyTemplate(value);
    return [template.template_key, template] as const;
  }),
);

export function policyTemplateForKey(templateKey: string): PolicyTemplateV1 | null {
  const template = TEMPLATES.get(templateKey);
  return template ? structuredClone(template) : null;
}
