import {
  deriveFreshness,
  type FreshnessWindow,
  validateFreshnessWindow,
} from "../src/freshness.ts";

const window: FreshnessWindow = {
  observedAtMs: 1_000,
  agingAtMs: 4_000,
  freshUntilMs: 7_000,
};

function assertEquals<T>(actual: T, expected: T): void {
  if (actual !== expected) {
    throw new Error(`expected ${expected}, got ${actual}`);
  }
}

Deno.test("freshness is LIVE before the aging boundary", () => {
  assertEquals(deriveFreshness(3_999, window), "LIVE");
});

Deno.test("freshness becomes AGING exactly at the aging boundary", () => {
  assertEquals(deriveFreshness(4_000, window), "AGING");
});

Deno.test("freshness remains AGING before the stale boundary", () => {
  assertEquals(deriveFreshness(6_999, window), "AGING");
});

Deno.test("freshness becomes STALE exactly at freshUntil", () => {
  assertEquals(deriveFreshness(7_000, window), "STALE");
});

Deno.test("freshness remains STALE after expiry", () => {
  assertEquals(deriveFreshness(9_000, window), "STALE");
});

Deno.test("invalid freshness windows are rejected", () => {
  let threw = false;

  try {
    validateFreshnessWindow({
      observedAtMs: 5_000,
      agingAtMs: 4_000,
      freshUntilMs: 7_000,
    });
  } catch (error) {
    threw = error instanceof RangeError;
  }

  if (!threw) {
    throw new Error("expected invalid freshness window to throw RangeError");
  }
});
