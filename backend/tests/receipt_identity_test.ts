import { deriveReceiptDigestV1 } from "../src/receipt-identity.ts";

const BASE = {
  refreshId: "a1000000-0000-4000-8000-000000000001",
  stateId: "a2000000-0000-4000-8000-000000000001",
  verificationResultId: "a3000000-0000-4000-8000-000000000001",
  settlementId: "a4000000-0000-4000-8000-000000000001",
  settlementOperationHash: new Uint8Array(32).fill(1),
  finalValue: { kind: "numeric", scale: 0, scaled_value: "2", unit: "spaces" },
  observedAt: new Date("2026-09-26T06:00:00.000Z"),
  verificationClass: "FAST",
  rewardAmountAtomic: 500_000n,
  rewardMint: "So11111111111111111111111111111111111111112",
  verificationDigest: new Uint8Array(32).fill(2),
  settlementSignature: "settlement-signature",
  chainCommitment: "finalized" as const,
  settlementFinalizedAt: new Date("2026-09-26T06:01:00.000Z"),
};

function hex(value: Uint8Array): string {
  return [...value].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

Deno.test("receipt digest is deterministic across object key order", async () => {
  const first = await deriveReceiptDigestV1(BASE);
  const second = await deriveReceiptDigestV1({
    ...BASE,
    finalValue: {
      unit: "spaces",
      scaled_value: "2",
      scale: 0,
      kind: "numeric",
    },
  });

  if (first.length !== 32 || hex(first) !== hex(second)) {
    throw new Error("receipt digest is not canonical");
  }
});

Deno.test("receipt digest binds finalized settlement authority", async () => {
  const baseline = hex(await deriveReceiptDigestV1(BASE));
  const signatureDrift = hex(
    await deriveReceiptDigestV1({
      ...BASE,
      settlementSignature: "different-signature",
    }),
  );
  const operationDrift = hex(
    await deriveReceiptDigestV1({
      ...BASE,
      settlementOperationHash: new Uint8Array(32).fill(9),
    }),
  );

  if (baseline === signatureDrift || baseline === operationDrift) {
    throw new Error("receipt digest did not bind settlement authority");
  }
});

Deno.test("receipt digest rejects incomplete settlement authority", async () => {
  try {
    await deriveReceiptDigestV1({
      ...BASE,
      verificationDigest: new Uint8Array(31),
    });
    throw new Error("short verification digest unexpectedly accepted");
  } catch (error) {
    if (!(error instanceof TypeError)) throw error;
  }
});
