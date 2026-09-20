import {
  ERROR_CATEGORIES,
  FRESHNESS_STATUSES,
  PAYMENT_STATUSES,
  REFRESH_OVERLAYS,
  STATE_KINDS,
  VERIFICATION_CLASSES,
  VERIFICATION_RESULTS,
} from "../src/index.ts";

const schemaUrl = new URL("../schema/core-v1.schema.json", import.meta.url);

function assertSameValues(actual: readonly string[], expected: unknown, name: string): void {
  if (!Array.isArray(expected)) {
    throw new Error(`${name} schema enum is missing`);
  }

  const left = [...actual].sort();
  const right = expected.map(String).sort();

  if (JSON.stringify(left) !== JSON.stringify(right)) {
    throw new Error(`${name} TypeScript values do not match JSON Schema`);
  }
}

Deno.test("contract constants match the JSON schema", async () => {
  const schema = JSON.parse(await Deno.readTextFile(schemaUrl)) as {
    $defs: Record<string, { enum?: unknown }>;
  };

  assertSameValues(STATE_KINDS, schema.$defs.StateKind.enum, "StateKind");
  assertSameValues(FRESHNESS_STATUSES, schema.$defs.FreshnessStatus.enum, "FreshnessStatus");
  assertSameValues(REFRESH_OVERLAYS, schema.$defs.RefreshOverlay.enum, "RefreshOverlay");
  assertSameValues(
    VERIFICATION_CLASSES,
    schema.$defs.VerificationClass.enum,
    "VerificationClass",
  );
  assertSameValues(
    VERIFICATION_RESULTS,
    schema.$defs.VerificationResult.enum,
    "VerificationResult",
  );
  assertSameValues(
    PAYMENT_STATUSES,
    schema.$defs.PaymentStatus.enum,
    "PaymentStatus",
  );
  assertSameValues(ERROR_CATEGORIES, schema.$defs.ErrorCategory.enum, "ErrorCategory");
});

Deno.test("contract registries contain no duplicate values", () => {
  const registries = [
    STATE_KINDS,
    FRESHNESS_STATUSES,
    REFRESH_OVERLAYS,
    VERIFICATION_CLASSES,
    VERIFICATION_RESULTS,
    PAYMENT_STATUSES,
    ERROR_CATEGORIES,
  ];

  for (const registry of registries) {
    if (new Set(registry).size !== registry.length) {
      throw new Error("contract registry contains duplicate values");
    }
  }
});
