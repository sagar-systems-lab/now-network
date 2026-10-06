import {
  ASK_CONFIG,
  coveragePayload,
  locationFingerprint,
  parseAsk,
  questionFingerprint,
  requesterLocation,
} from "../src/ask-contract.ts";
import { ApiFault } from "../src/errors.ts";
function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}
const now = new Date("2026-10-04T12:00:00Z");
function input(name = "Lot A", lat = 28.6) {
  return {
    location: { name, lat, lng: 77.2, location_type: "PARKING" },
    policy_template_key: "parking.available_spaces.v1",
  };
}
function rejects(fn: () => unknown) {
  try {
    fn();
  } catch (e) {
    assert(e instanceof ApiFault, "expected validation fault");
    return;
  }
  throw new Error("invalid input accepted");
}
Deno.test("ASK identity normalizes equivalent labels and retains distinct nearby places", () => {
  const a = parseAsk(input("  Lot   A  "), now), b = parseAsk(input("lot a"), now);
  assert(
    locationFingerprint(a.location) === locationFingerprint(b.location),
    "equivalent name mismatch",
  );
  assert(
    locationFingerprint(a.location) !== locationFingerprint(parseAsk(input("Lot B"), now).location),
    "distinct name collapsed",
  );
  assert(
    locationFingerprint(a.location) !==
      locationFingerprint(parseAsk(input("Lot A", 28.61), now).location),
    "remote place collapsed",
  );
});

Deno.test("ASK preserves a bounded human-readable display address", () => {
  const parsed = parseAsk({
    ...input(),
    location: {
      ...input().location,
      display_address: "  Sector 7, Faridabad, Haryana  ",
    },
  }, now);
  assert(
    parsed.location.display_address === "Sector 7, Faridabad, Haryana",
    "address normalization failed",
  );
  rejects(() =>
    parseAsk({
      ...input(),
      location: { ...input().location, display_address: "x".repeat(251) },
    }, now)
  );
  rejects(() =>
    parseAsk({
      ...input(),
      location: { ...input().location, display_address: "Hidden\u200b address" },
    }, now)
  );
});

Deno.test("custom photo questions are bounded and keep their own identity", async () => {
  const base = {
    ...input(),
    location: { ...input().location, location_type: "PLACE" },
    policy_template_key: "visual.current_condition.v1",
  };
  const first = parseAsk({ ...base, custom_question: "  How busy  is the entrance? " }, now);
  const same = parseAsk({ ...base, custom_question: "how busy is the entrance?" }, now);
  const other = parseAsk({ ...base, custom_question: "Is the entrance flooded?" }, now);
  assert(first.custom_question === "How busy is the entrance?", "question normalization failed");
  assert(
    await questionFingerprint(first.custom_question) ===
      await questionFingerprint(same.custom_question),
    "same question duplicated",
  );
  assert(
    await questionFingerprint(first.custom_question) !==
      await questionFingerprint(other.custom_question),
    "different questions merged",
  );
  assert(await questionFingerprint(null) === "", "standard template identity changed");
  rejects(() => parseAsk({ ...base, custom_question: "Short" }, now));
  rejects(() => parseAsk({ ...base, custom_question: "x".repeat(201) }, now));
  rejects(() => parseAsk({ ...base, custom_question: "Hidden\u200b question" }, now));
  rejects(() => parseAsk({ ...input(), custom_question: "Is there an open entrance?" }, now));
});
Deno.test("ASK rejects unregistered work, mismatched place type and invalid coordinates", () => {
  rejects(() => parseAsk({ ...input(), policy_template_key: "custom.delivery" }, now));
  rejects(() =>
    parseAsk({ ...input(), location: { ...input().location, location_type: "GATE" } }, now)
  );
  rejects(() => parseAsk(input("Lot A", NaN), now));
  rejects(() => parseAsk(input("Lot A", 91), now));
  rejects(() => parseAsk(input("\u200b"), now));
});
Deno.test("coverage cannot expose contributors without a fresh accurate requester position", () => {
  const unknown = coveragePayload(9, now, false);
  assert(
    unknown.active_contributors === 0 && unknown.status === "LOCATION_REQUIRED",
    "unfiltered contributors exposed",
  );
  assert(ASK_CONFIG.exclusionRadiusM === 100, "100 m exclusion changed");
  assert(requesterLocation(null, now) === null, "remote target forced GPS");
  rejects(() =>
    requesterLocation({ lat: 0, lng: 0, accuracy_m: 101, captured_at: now.toISOString() }, now)
  );
  rejects(() =>
    requesterLocation({ lat: 0, lng: 0, accuracy_m: 10, captured_at: "2026-10-04T11:58:00Z" }, now)
  );
  assert(
    !JSON.stringify(coveragePayload(2, now, true)).includes("actor"),
    "private identity exposed",
  );
});
