import { parseSettlementVerifierSecret } from "../src/settlement-verifier-secret.ts";

Deno.test("settlement verifier secret accepts exactly 64 byte values", () => {
  const source = Array.from({ length: 64 }, (_, index) => index);
  const loaded = parseSettlementVerifierSecret(JSON.stringify(source));
  if (
    loaded.length !== 64 ||
    loaded.some((value, index) => value !== source[index])
  ) {
    throw new Error("settlement verifier secret changed while loading");
  }
});

Deno.test("settlement verifier rejects malformed secret material", () => {
  for (
    const raw of [
      "{}",
      "[1,2,3]",
      JSON.stringify(new Array(64).fill(999)),
    ]
  ) {
    try {
      parseSettlementVerifierSecret(raw);
      throw new Error("malformed verifier keypair unexpectedly loaded");
    } catch (error) {
      if (!(error instanceof Error)) throw error;
    }
  }
});
