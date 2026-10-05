import { STATE_TYPES } from "../../contracts/src/lifecycle.ts";
import { VERIFICATION_CLASSES } from "../../contracts/src/core.ts";
import type { PolicyTemplateV1 } from "./types.ts";

const topLevelKeys = new Set([
  "template_key",
  "state_type",
  "fresh_ttl_seconds",
  "aging_ratio",
  "verification_class",
  "required_witnesses",
  "capture",
  "numeric",
]);

const captureKeys = new Set(["media_required", "location_required", "video_required"]);
const numericKeys = new Set(["scale", "min", "max", "conflict_tolerance", "allow_two_of_three"]);
const stateTypes = new Set<string>(STATE_TYPES);
const verificationClasses = new Set<string>(VERIFICATION_CLASSES);

function asRecord(value: unknown, name: string): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError(`${name} must be an object`);
  }

  return value as Record<string, unknown>;
}

function rejectUnknownKeys(
  value: Record<string, unknown>,
  allowed: ReadonlySet<string>,
  name: string,
): void {
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) {
      throw new TypeError(`${name} contains unknown field: ${key}`);
    }
  }
}

function requiredString(value: unknown, name: string): string {
  if (typeof value !== "string" || value.length === 0) {
    throw new TypeError(`${name} must be a non-empty string`);
  }

  return value;
}

function requiredBoolean(value: unknown, name: string): boolean {
  if (typeof value !== "boolean") {
    throw new TypeError(`${name} must be boolean`);
  }

  return value;
}

function requiredFiniteNumber(value: unknown, name: string): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new TypeError(`${name} must be a finite number`);
  }

  return value;
}

function requiredSafeInteger(value: unknown, name: string): number {
  const number = requiredFiniteNumber(value, name);

  if (!Number.isSafeInteger(number)) {
    throw new TypeError(`${name} must be a safe integer`);
  }

  return number;
}

function parseCapture(value: unknown): PolicyTemplateV1["capture"] {
  const capture = asRecord(value, "capture");
  rejectUnknownKeys(capture, captureKeys, "capture");
  if (capture.video_required === true && capture.media_required !== true) {
    throw new TypeError("Video proof requires the primary photo");
  }

  return {
    media_required: requiredBoolean(capture.media_required, "capture.media_required"),
    ...(capture.video_required === undefined
      ? {}
      : { video_required: requiredBoolean(capture.video_required, "capture.video_required") }),
    location_required: requiredBoolean(capture.location_required, "capture.location_required"),
  };
}

function parseNumeric(value: unknown): NonNullable<PolicyTemplateV1["numeric"]> {
  const numeric = asRecord(value, "numeric");
  rejectUnknownKeys(numeric, numericKeys, "numeric");

  const scale = requiredSafeInteger(numeric.scale, "numeric.scale");
  const min = requiredSafeInteger(numeric.min, "numeric.min");
  const conflictTolerance = requiredSafeInteger(
    numeric.conflict_tolerance,
    "numeric.conflict_tolerance",
  );

  if (scale < 0) throw new RangeError("numeric.scale must be >= 0");
  if (conflictTolerance < 0) {
    throw new RangeError("numeric.conflict_tolerance must be >= 0");
  }

  let max: number | undefined;
  if (numeric.max !== undefined) {
    max = requiredSafeInteger(numeric.max, "numeric.max");
    if (max < min) throw new RangeError("numeric.max must be >= numeric.min");
  }

  let allowTwoOfThree: boolean | undefined;
  if (numeric.allow_two_of_three !== undefined) {
    allowTwoOfThree = requiredBoolean(
      numeric.allow_two_of_three,
      "numeric.allow_two_of_three",
    );
  }

  return {
    scale,
    min,
    ...(max === undefined ? {} : { max }),
    conflict_tolerance: conflictTolerance,
    ...(allowTwoOfThree === undefined ? {} : { allow_two_of_three: allowTwoOfThree }),
  };
}

export function policyVersion(templateKey: string): number {
  const match = templateKey.match(/\.v([1-9][0-9]*)$/);
  if (!match) throw new TypeError("template_key must end with a positive .vN suffix");
  return Number(match[1]);
}

export function parsePolicyTemplate(value: unknown): PolicyTemplateV1 {
  const raw = asRecord(value, "policy");
  rejectUnknownKeys(raw, topLevelKeys, "policy");

  const templateKey = requiredString(raw.template_key, "template_key");
  policyVersion(templateKey);

  const stateType = requiredString(raw.state_type, "state_type");
  if (!stateTypes.has(stateType)) {
    throw new TypeError(`unsupported state_type: ${stateType}`);
  }

  const ttl = requiredSafeInteger(raw.fresh_ttl_seconds, "fresh_ttl_seconds");
  if (ttl <= 0) throw new RangeError("fresh_ttl_seconds must be > 0");

  const agingRatio = requiredFiniteNumber(raw.aging_ratio, "aging_ratio");
  if (agingRatio <= 0 || agingRatio >= 1) {
    throw new RangeError("aging_ratio must be between 0 and 1");
  }

  const verificationClass = requiredString(raw.verification_class, "verification_class");
  if (!verificationClasses.has(verificationClass)) {
    throw new TypeError(`unsupported verification_class: ${verificationClass}`);
  }

  const requiredWitnesses = requiredSafeInteger(raw.required_witnesses, "required_witnesses");
  if (requiredWitnesses < 1 || requiredWitnesses > 3) {
    throw new RangeError("required_witnesses must be between 1 and 3");
  }

  const numeric = raw.numeric === undefined ? undefined : parseNumeric(raw.numeric);
  if (stateType === "NUMERIC" && numeric === undefined) {
    throw new TypeError("NUMERIC policy requires numeric configuration");
  }
  if (stateType !== "NUMERIC" && numeric !== undefined) {
    throw new TypeError("numeric configuration is only valid for NUMERIC state");
  }

  return {
    template_key: templateKey,
    state_type: stateType as PolicyTemplateV1["state_type"],
    fresh_ttl_seconds: ttl,
    aging_ratio: agingRatio,
    verification_class: verificationClass as PolicyTemplateV1["verification_class"],
    required_witnesses: requiredWitnesses,
    capture: parseCapture(raw.capture),
    ...(numeric === undefined ? {} : { numeric }),
  };
}

export function canonicalJson(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "string") {
    return JSON.stringify(value);
  }

  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new TypeError("canonical JSON rejects non-finite numbers");
    return JSON.stringify(value);
  }

  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(",")}]`;
  }

  if (typeof value === "object") {
    const record = value as Record<string, unknown>;
    const entries = Object.keys(record)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(record[key])}`);
    return `{${entries.join(",")}}`;
  }

  throw new TypeError("canonical JSON only accepts JSON values");
}

export async function policyDigestHex(template: PolicyTemplateV1): Promise<string> {
  const bytes = new TextEncoder().encode(canonicalJson(template));
  const digest = await crypto.subtle.digest("SHA-256", bytes);

  return [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}
