import { API_ERROR_CODES, VERIFICATION_REASON_CODES } from "../src/reason-codes.ts";

function assertUnique(values: readonly string[], name: string): void {
  if (new Set(values).size !== values.length) {
    throw new Error(`${name} contains duplicate values`);
  }
}

function assertStableFormat(values: readonly string[], name: string): void {
  const pattern = /^[A-Z][A-Z0-9_]*$/;

  for (const value of values) {
    if (!pattern.test(value)) {
      throw new Error(`${name} contains an invalid code: ${value}`);
    }
  }
}

Deno.test("reason and API error registries are unique and machine-safe", () => {
  assertUnique(VERIFICATION_REASON_CODES, "verification reason codes");
  assertUnique(API_ERROR_CODES, "API error codes");
  assertStableFormat(VERIFICATION_REASON_CODES, "verification reason codes");
  assertStableFormat(API_ERROR_CODES, "API error codes");
});
