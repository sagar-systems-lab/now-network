import { healthPayload } from "../../backend/src/health.ts";
import {
  FRESHNESS_STATUSES,
  PAYMENT_STATUSES,
  VERIFICATION_RESULTS,
} from "../../packages/contracts/src/index.ts";

Deno.test("runtime surfaces expose the shared contract vocabulary", () => {
  const health = healthPayload();

  if (health.service !== "now-api" || health.status !== "ok") {
    throw new Error("API health contract is unavailable");
  }

  if (!FRESHNESS_STATUSES.includes("LIVE")) {
    throw new Error("freshness vocabulary is unavailable");
  }

  if (!VERIFICATION_RESULTS.includes("VERIFIED")) {
    throw new Error("verification vocabulary is unavailable");
  }

  if (!PAYMENT_STATUSES.includes("PAID")) {
    throw new Error("payment vocabulary is unavailable");
  }
});
