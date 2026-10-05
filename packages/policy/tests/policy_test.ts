import {
  aggregateConfirmedContributions,
  canonicalJson,
  derivePolicyFreshness,
  evaluateBinaryConflict,
  evaluateNumericConflict,
  parsePolicyTemplate,
  policyDigestHex,
  type PolicyTemplateV1,
  policyVersion,
  suggestReward,
} from "../src/index.ts";

const templateRoot = new URL("../templates/", import.meta.url);

function assertEquals<T>(actual: T, expected: T): void {
  if (actual !== expected) {
    throw new Error(`expected ${expected}, got ${actual}`);
  }
}

function assertJsonEquals(actual: unknown, expected: unknown): void {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(
      `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
    );
  }
}

function assertThrows(operation: () => unknown): void {
  try {
    operation();
  } catch {
    return;
  }

  throw new Error("expected operation to throw");
}

async function loadTemplate(name: string): Promise<PolicyTemplateV1> {
  const raw = JSON.parse(await Deno.readTextFile(new URL(name, templateRoot)));
  return parsePolicyTemplate(raw);
}

Deno.test("all shipped policy templates pass strict parsing", async () => {
  const parking = await loadTemplate("parking.available_spaces.v1.json");
  const gate = await loadTemplate("gate.open_closed.v1.json");
  const visual = await loadTemplate("visual.current_condition.v1.json");

  assertEquals(policyVersion(parking.template_key), 1);
  assertEquals(parking.state_type, "NUMERIC");
  assertEquals(parking.fresh_ttl_seconds, 600);
  assertEquals(parking.aging_ratio, 0.7);
  assertEquals(parking.verification_class, "FAST");
  assertEquals(parking.required_witnesses, 1);
  assertEquals(parking.numeric?.conflict_tolerance, 1);

  assertEquals(gate.state_type, "BINARY");
  assertEquals(visual.state_type, "VISUAL");
  for (const template of [parking, gate, visual]) {
    assertEquals(template.capture.media_required, true);
    assertEquals(template.capture.video_required, true);
    assertEquals(template.capture.location_required, true);
  }
});

Deno.test("policy parser rejects unknown critical fields and invalid cross-field data", async () => {
  const raw = JSON.parse(
    await Deno.readTextFile(
      new URL("parking.available_spaces.v1.json", templateRoot),
    ),
  ) as Record<string, unknown>;

  assertThrows(() => parsePolicyTemplate({ ...raw, arbitrary_expression: "allow()" }));

  const withoutNumeric = { ...raw };
  delete withoutNumeric.numeric;
  assertThrows(() => parsePolicyTemplate(withoutNumeric));

  assertThrows(() =>
    parsePolicyTemplate({
      ...raw,
      aging_ratio: 1,
    })
  );
});

Deno.test("policy canonicalization and digest are stable across key order", async () => {
  const left = parsePolicyTemplate({
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
  });

  const right = parsePolicyTemplate({
    capture: {
      location_required: true,
      media_required: true,
    },
    required_witnesses: 1,
    verification_class: "FAST",
    aging_ratio: 0.7,
    fresh_ttl_seconds: 1200,
    state_type: "BINARY",
    template_key: "gate.open_closed.v1",
  });

  assertEquals(canonicalJson(left), canonicalJson(right));
  assertEquals(await policyDigestHex(left), await policyDigestHex(right));
});

Deno.test("policy freshness uses template timestamps deterministically", async () => {
  const parking = await loadTemplate("parking.available_spaces.v1.json");
  const observedAt = 1_000_000;

  assertEquals(
    derivePolicyFreshness(observedAt + 419_999, observedAt, parking),
    "LIVE",
  );
  assertEquals(
    derivePolicyFreshness(observedAt + 420_000, observedAt, parking),
    "AGING",
  );
  assertEquals(
    derivePolicyFreshness(observedAt + 599_999, observedAt, parking),
    "AGING",
  );
  assertEquals(
    derivePolicyFreshness(observedAt + 600_000, observedAt, parking),
    "STALE",
  );
});

Deno.test("reward aggregation includes only confirmed active contributions", () => {
  const total = aggregateConfirmedContributions([
    { amount_atomic: 100_000n, status: "CONFIRMED" },
    { amount_atomic: 50_000n, status: "FINALIZED" },
    { amount_atomic: 70_000n, status: "SUBMITTED" },
    { amount_atomic: 30_000n, status: "REFUNDED" },
  ]);

  assertEquals(total, 150_000n);
});

Deno.test("reward suggestion uses bounded integer factors and friendly rounding", () => {
  const suggestion = suggestReward({
    base_atomic: 100_000n,
    min_atomic: 50_000n,
    max_suggested_atomic: 1_000_000n,
    friendly_step_atomic: 50_000n,
    contributor_distance_m: 250,
    remaining_seconds: 500,
    verification_class: "FAST",
  });

  assertEquals(suggestion.raw_atomic, 143_750n);
  assertEquals(suggestion.suggested_atomic, 150_000n);
  assertEquals(suggestion.distance_factor_bps, 12_500);
  assertEquals(suggestion.urgency_factor_bps, 11_500);
  assertEquals(suggestion.verification_factor_bps, 10_000);
  assertEquals(suggestion.clamped, false);

  const capped = suggestReward({
    base_atomic: 500_000n,
    min_atomic: 50_000n,
    max_suggested_atomic: 600_000n,
    friendly_step_atomic: 50_000n,
    contributor_distance_m: 2_000,
    remaining_seconds: 400,
    verification_class: "STRICT",
  });

  assertEquals(capped.suggested_atomic, 600_000n);
  assertEquals(capped.clamped, true);
});

Deno.test("binary conflict never silently resolves disagreement", () => {
  const conflict = evaluateBinaryConflict(["OPEN", "CLOSED"], 2);
  assertEquals(conflict.decision, "CONFLICT");
  assertJsonEquals(conflict.reason_codes, ["WITNESS_CONFLICT"]);

  const resolved = evaluateBinaryConflict(["OPEN", "CLOSED", "OPEN"], 2, true);
  assertEquals(resolved.decision, "AGREEMENT");
  if (resolved.decision === "AGREEMENT") {
    assertEquals(resolved.resolved_value, "OPEN");
    assertJsonEquals(resolved.matching_indexes, [0, 2]);
  }
});

Deno.test("numeric conflict uses scaled integer tolerance without averaging", () => {
  const withinTolerance = evaluateNumericConflict([2, 3], 2, 1);
  assertEquals(withinTolerance.decision, "AGREEMENT");
  if (withinTolerance.decision === "AGREEMENT") {
    assertEquals(withinTolerance.resolved_value, undefined);
  }

  const conflict = evaluateNumericConflict([2, 5], 2, 1);
  assertEquals(conflict.decision, "CONFLICT");

  const majority = evaluateNumericConflict([2, 8, 3], 2, 1, true);
  assertEquals(majority.decision, "AGREEMENT");
  if (majority.decision === "AGREEMENT") {
    assertJsonEquals(majority.matching_indexes, [0, 2]);
    assertEquals(majority.resolved_value, undefined);
  }
});

Deno.test("authoritative policy evaluation is identical across 1000 runs", async () => {
  const parking = await loadTemplate("parking.available_spaces.v1.json");

  const evaluate = () => {
    const conflict = evaluateNumericConflict([4, 5, 9], 2, 1, true);
    const reward = suggestReward({
      base_atomic: 100_000n,
      min_atomic: 50_000n,
      max_suggested_atomic: 500_000n,
      friendly_step_atomic: 50_000n,
      contributor_distance_m: 420,
      remaining_seconds: 360,
      verification_class: parking.verification_class,
    });

    return JSON.stringify({
      freshness: derivePolicyFreshness(1_450_000, 1_000_000, parking),
      conflict,
      reward: {
        raw_atomic: reward.raw_atomic.toString(),
        suggested_atomic: reward.suggested_atomic.toString(),
        distance_factor_bps: reward.distance_factor_bps,
        urgency_factor_bps: reward.urgency_factor_bps,
        verification_factor_bps: reward.verification_factor_bps,
        clamped: reward.clamped,
      },
    });
  };

  const expected = evaluate();
  for (let iteration = 0; iteration < 1_000; iteration++) {
    assertEquals(evaluate(), expected);
  }
});

Deno.test("legacy capture snapshots keep their original fields and new templates require video", () => {
  const current = parsePolicyTemplate({
    template_key: "visual.current_condition.v1",
    state_type: "VISUAL",
    fresh_ttl_seconds: 3600,
    aging_ratio: 0.7,
    verification_class: "FAST",
    required_witnesses: 1,
    capture: { media_required: true, location_required: true },
  });
  if (Object.hasOwn(current.capture, "video_required")) {
    throw new Error("Legacy snapshot was changed");
  }
  const next = parsePolicyTemplate({
    ...current,
    capture: { ...current.capture, video_required: true },
  });
  if (!next.capture.video_required) {
    throw new Error("Video requirement was lost");
  }
});
