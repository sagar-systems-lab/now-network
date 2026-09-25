import {
  bytesToHex,
  deriveSettlementOperationHashV1,
  uuidBytes,
} from "../src/settlement-identity.ts";

Deno.test("settlement operation hash is stable and binds recipient selection", async () => {
  const base = {
    chainRefreshId: new Uint8Array(32).fill(0x11),
    executionHash: new Uint8Array(32).fill(0x22),
    verificationDigest: new Uint8Array(32).fill(0x33),
    operationId: "550e8400-e29b-41d4-a716-446655440000",
    recipientMask: 0b101,
  };

  const first = await deriveSettlementOperationHashV1(base);
  const second = await deriveSettlementOperationHashV1(base);
  if (first.length !== 32 || bytesToHex(first) !== bytesToHex(second)) {
    throw new Error("settlement operation identity is not deterministic");
  }

  const changed = await deriveSettlementOperationHashV1({
    ...base,
    recipientMask: 0b011,
  });
  if (bytesToHex(first) === bytesToHex(changed)) {
    throw new Error("recipient selection was not bound into operation identity");
  }
});

Deno.test("settlement operation hash binds verification and execution authority", async () => {
  const base = {
    chainRefreshId: new Uint8Array(32).fill(1),
    executionHash: new Uint8Array(32).fill(2),
    verificationDigest: new Uint8Array(32).fill(3),
    operationId: "550e8400-e29b-41d4-a716-446655440000",
    recipientMask: 1,
  };
  const baseline = bytesToHex(await deriveSettlementOperationHashV1(base));
  const executionDrift = bytesToHex(await deriveSettlementOperationHashV1({
    ...base,
    executionHash: new Uint8Array(32).fill(9),
  }));
  const verificationDrift = bytesToHex(await deriveSettlementOperationHashV1({
    ...base,
    verificationDigest: new Uint8Array(32).fill(8),
  }));

  if (baseline === executionDrift || baseline === verificationDrift) {
    throw new Error("settlement operation authority was not fully bound");
  }
});

Deno.test("settlement identity rejects malformed UUIDs and masks", async () => {
  try {
    uuidBytes("not-a-uuid");
    throw new Error("malformed operation ID unexpectedly accepted");
  } catch (error) {
    if (!(error instanceof TypeError)) throw error;
  }

  try {
    await deriveSettlementOperationHashV1({
      chainRefreshId: new Uint8Array(32),
      executionHash: new Uint8Array(32),
      verificationDigest: new Uint8Array(32),
      operationId: "550e8400-e29b-41d4-a716-446655440000",
      recipientMask: 0,
    });
    throw new Error("empty recipient mask unexpectedly accepted");
  } catch (error) {
    if (!(error instanceof TypeError)) throw error;
  }
});
