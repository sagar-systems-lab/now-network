const encoder = new TextEncoder();
const DOMAIN = encoder.encode("NOW_SETTLEMENT_OPERATION_V1\0");
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

function concat(parts: readonly Uint8Array[]): Uint8Array {
  const size = parts.reduce((sum, part) => sum + part.length, 0);
  const out = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function require32(name: string, value: Uint8Array): void {
  if (value.length !== 32) throw new TypeError(`${name} must be 32 bytes`);
}

export function uuidBytes(value: string): Uint8Array {
  if (!UUID_PATTERN.test(value)) throw new TypeError("operation ID must be a canonical UUID");
  const hex = value.replaceAll("-", "");
  const out = new Uint8Array(16);
  for (let index = 0; index < out.length; index += 1) {
    out[index] = Number.parseInt(hex.slice(index * 2, index * 2 + 2), 16);
  }
  return out;
}

export async function deriveSettlementOperationHashV1(input: {
  chainRefreshId: Uint8Array;
  executionHash: Uint8Array;
  verificationDigest: Uint8Array;
  operationId: string;
  recipientMask: number;
}): Promise<Uint8Array> {
  require32("chain refresh ID", input.chainRefreshId);
  require32("execution hash", input.executionHash);
  require32("verification digest", input.verificationDigest);
  if (
    !Number.isSafeInteger(input.recipientMask) ||
    input.recipientMask < 1 ||
    input.recipientMask > 0b111
  ) {
    throw new TypeError("recipient mask must select one to three witness slots");
  }

  const bytes = concat([
    DOMAIN,
    input.chainRefreshId,
    input.executionHash,
    input.verificationDigest,
    uuidBytes(input.operationId),
    Uint8Array.of(input.recipientMask),
  ]);
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

export function bytesToHex(value: Uint8Array): string {
  return [...value].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
