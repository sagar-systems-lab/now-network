export function parseSettlementVerifierSecret(raw: string): Uint8Array {
  let decoded: unknown;
  try {
    decoded = JSON.parse(raw);
  } catch {
    throw new Error(
      "NOW_SETTLEMENT_VERIFIER_KEYPAIR_JSON must be valid JSON",
    );
  }
  if (
    !Array.isArray(decoded) ||
    decoded.length !== 64 ||
    decoded.some((value) =>
      !Number.isSafeInteger(value) ||
      Number(value) < 0 ||
      Number(value) > 255
    )
  ) {
    throw new Error(
      "NOW_SETTLEMENT_VERIFIER_KEYPAIR_JSON must contain exactly 64 byte values",
    );
  }
  return Uint8Array.from(decoded as number[]);
}
