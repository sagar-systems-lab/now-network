import { ApiFault } from "./errors.ts";
import { requiredString } from "./http.ts";
import { policyTemplateForKey } from "../../packages/policy/src/registry.ts";

export const ASK_CONFIG = Object.freeze({
  coverageRadiusM: 2000,
  minRadiusM: 100,
  maxRadiusM: 5000,
  exclusionRadiusM: 100,
  maxAccuracyM: 100,
  heartbeatSeconds: 60,
  presenceTtlSeconds: 180,
  locationMaxAgeSeconds: 60,
  presencePerMinute: 6,
  coveragePerMinute: 30,
  resolvesPerMinute: 10,
  locationsPerDay: 30,
  statesPerDay: 60,
});

export type Point = { lat: number; lng: number };
export type RequesterLocation = Point & { accuracy_m: number; captured_at: string };
export type AskInput = {
  location: Point & { name: string; location_type: string; display_address: string | null };
  policy_template_key: string;
  requester_location: RequesterLocation | null;
  custom_question: string | null;
};
export const ASK_TEMPLATES = {
  "parking.available_spaces.v1": {
    type: "PARKING",
    title: "Available parking",
    question: "How many parking spaces are available?",
    answer: { type: "integer", minimum: 0 },
    unit: "spaces",
  },
  "gate.open_closed.v1": {
    type: "GATE",
    title: "Gate access",
    question: "Is this gate open or closed?",
    answer: { type: "string", enum: ["OPEN", "CLOSED"] },
    unit: null,
  },
  "visual.current_condition.v1": {
    type: "PLACE",
    title: "Current condition",
    question: "What does this place look like now?",
    answer: { type: "string", maxLength: 500 },
    unit: null,
  },
} as const;

function invalid(field: string): never {
  throw new ApiFault(400, "INVALID_REQUEST", `Invalid ${field}.`);
}
export function numberField(
  body: Record<string, unknown>,
  key: string,
  min: number,
  max: number,
): number {
  const value = body[key];
  if (typeof value !== "number" || !Number.isFinite(value) || value < min || value > max) {
    invalid(key);
  }
  return value;
}
export function objectField(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) invalid("object");
  return value as Record<string, unknown>;
}
export function point(body: Record<string, unknown>): Point {
  return { lat: numberField(body, "lat", -90, 90), lng: numberField(body, "lng", -180, 180) };
}
export function requesterLocation(value: unknown, now: Date): RequesterLocation | null {
  if (value == null) return null;
  const body = objectField(value);
  const captured = requiredString(body, "captured_at", 40);
  const age = now.getTime() - Date.parse(captured);
  if (!Number.isFinite(age) || age < -5000 || age > ASK_CONFIG.locationMaxAgeSeconds * 1000) {
    throw new ApiFault(400, "LOCATION_STALE", "Get a fresh location to check coverage.");
  }
  return {
    ...point(body),
    accuracy_m: numberField(body, "accuracy_m", 0, ASK_CONFIG.maxAccuracyM),
    captured_at: captured,
  };
}
export function parseAsk(body: Record<string, unknown>, now: Date): AskInput {
  const location = objectField(body.location);
  const key = requiredString(body, "policy_template_key", 80);
  if (!Object.hasOwn(ASK_TEMPLATES, key) || policyTemplateForKey(key) == null) {
    invalid("policy_template_key");
  }
  const template = ASK_TEMPLATES[key as keyof typeof ASK_TEMPLATES];
  const name = requiredString(location, "name", 120).normalize("NFKC").replace(/\s+/gu, " ").trim();
  if (!name || name.length > 120 || /[\p{Cc}\p{Cf}]/u.test(name)) invalid("name");
  const displayAddress = location.display_address == null
    ? null
    : requiredString(location, "display_address", 250).normalize("NFKC").replace(/\s+/gu, " ")
      .trim();
  if (displayAddress !== null && (!displayAddress || /[\p{Cc}\p{Cf}]/u.test(displayAddress))) {
    invalid("display_address");
  }
  if (location.location_type !== template.type) invalid("location_type");
  const customQuestion = body.custom_question == null
    ? null
    : requiredString(body, "custom_question", 200).normalize("NFKC").replace(/\s+/gu, " ").trim();
  if (
    customQuestion !== null && (key !== "visual.current_condition.v1" ||
      customQuestion.length < 8 || customQuestion.length > 200 ||
      /[\p{Cc}\p{Cf}]/u.test(customQuestion))
  ) {
    invalid("custom_question");
  }
  return {
    location: {
      ...point(location),
      name,
      location_type: template.type,
      display_address: displayAddress,
    },
    policy_template_key: key,
    requester_location: requesterLocation(body.requester_location, now),
    custom_question: customQuestion,
  };
}

export async function questionFingerprint(question: string | null): Promise<string> {
  if (question === null) return "";
  const bytes = new TextEncoder().encode(question.toLowerCase());
  return Array.from(
    new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
    (b) => b.toString(16).padStart(2, "0"),
  ).join("");
}

// A named place in a ~1 m coordinate cell. Nearby places with different names never collapse.
export function locationFingerprint(location: AskInput["location"]): string {
  return JSON.stringify([
    location.name.toLowerCase(),
    location.location_type,
    Math.round(location.lat * 100000),
    Math.round(location.lng * 100000),
  ]);
}

export function coveragePayload(count: number, checked: Date, hasOrigin: boolean) {
  return {
    coverage_available: hasOrigin && count > 0,
    active_contributors: hasOrigin ? count : 0,
    status: !hasOrigin ? "LOCATION_REQUIRED" : count > 0 ? "AVAILABLE" : "NO_COVERAGE",
    checked_at: checked.toISOString(),
    exclusion_radius_m: ASK_CONFIG.exclusionRadiusM,
  };
}
