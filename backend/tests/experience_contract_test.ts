import {
  canonicalPayouts,
  DEFAULT_NOTIFICATION_PREFERENCES,
  notificationPreferences,
  quietNow,
  sanitizeAvatarPng,
} from "../src/experience-contract.ts";

function assert(condition: unknown, message = "assertion failed"): asserts condition {
  if (!condition) throw new Error(message);
}
function throws(block: () => unknown) {
  let failed = false;
  try {
    block();
  } catch {
    failed = true;
  }
  assert(failed, "expected rejection");
}

Deno.test("payout projection follows selected claim slots and assigns the complete remainder once", () => {
  const result = canonicalPayouts(101n, 5, [{ slot: 2, wallet: "last" }, {
    slot: 0,
    wallet: "first",
  }]);
  assert(result[0].wallet === "first" && result[0].amount_atomic === "51");
  assert(result[1].wallet === "last" && result[1].amount_atomic === "50");
  const thirds = canonicalPayouts(101n, 7, [0, 1, 2].map((slot) => ({ slot, wallet: `w${slot}` })));
  assert(thirds.map((r) => r.amount_atomic).join(",") === "35,33,33");
  assert(canonicalPayouts(9n, 4, [{ slot: 2, wallet: "only" }])[0].amount_atomic === "9");
});
Deno.test("payout projection rejects missing, duplicate and out-of-range settlement authority", () => {
  throws(() => canonicalPayouts(100n, 5, [{ slot: 0, wallet: "w" }]));
  throws(() => canonicalPayouts(100n, 3, [{ slot: 0, wallet: "w" }, { slot: 0, wallet: "w" }]));
  throws(() => canonicalPayouts(0n, 1, [{ slot: 0, wallet: "w" }]));
  throws(() => canonicalPayouts(18446744073709551616n, 1, [{ slot: 0, wallet: "w" }]));
});
Deno.test("quiet hours respect overnight intervals, boundaries, timezone and disabled state", () => {
  const prefs = {
    ...DEFAULT_NOTIFICATION_PREFERENCES,
    quiet_enabled: true,
    timezone: "Asia/Kolkata",
  };
  assert(quietNow(prefs, new Date("2026-10-03T17:00:00Z")));
  assert(!quietNow(prefs, new Date("2026-10-03T02:30:00Z")));
  assert(!quietNow({ ...prefs, quiet_enabled: false }, new Date("2026-10-03T17:00:00Z")));
  assert(!quietNow({ ...prefs, quiet_start: "08:00", quiet_end: "08:00" }, new Date()));
});
Deno.test("notification preferences reject invalid timezone, time, category values and spoofed areas", () => {
  assert(notificationPreferences({ ...DEFAULT_NOTIFICATION_PREFERENCES }).payments);
  throws(() =>
    notificationPreferences({ ...DEFAULT_NOTIFICATION_PREFERENCES, timezone: "invalid/timezone" })
  );
  throws(() =>
    notificationPreferences({ ...DEFAULT_NOTIFICATION_PREFERENCES, quiet_start: "29:00" })
  );
  throws(() => notificationPreferences({ ...DEFAULT_NOTIFICATION_PREFERENCES, proof: "true" }));
  throws(() =>
    notificationPreferences({ ...DEFAULT_NOTIFICATION_PREFERENCES, area_ids: ["not-an-id"] })
  );
});
Deno.test("avatar upload rejects non-PNG and malformed structures before private storage", () => {
  throws(() => sanitizeAvatarPng(new Uint8Array([255, 216, 255, 217])));
  throws(() => sanitizeAvatarPng(new Uint8Array(600000)));
  throws(() =>
    sanitizeAvatarPng(new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10, ...new Array(50).fill(255)]))
  );
});
