import { Keypair } from "npm:@solana/web3.js@1.98.4";
import { settlementVerifierFromJson } from "../src/solana-settlement-client.ts";

Deno.test("settlement verifier keypair loads only from an exact 64-byte secret", () => {
  const keypair = Keypair.generate();
  const loaded = settlementVerifierFromJson(
    JSON.stringify([...keypair.secretKey]),
  );
  if (!loaded.publicKey.equals(keypair.publicKey)) {
    throw new Error("settlement verifier identity changed while loading");
  }
});

Deno.test("settlement verifier rejects malformed secret material", () => {
  for (const raw of ["{}", "[1,2,3]", JSON.stringify(new Array(64).fill(999))]) {
    try {
      settlementVerifierFromJson(raw);
      throw new Error("malformed verifier keypair unexpectedly loaded");
    } catch (error) {
      if (!(error instanceof Error)) throw error;
    }
  }
});
