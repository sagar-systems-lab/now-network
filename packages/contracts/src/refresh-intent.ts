import type { VerificationClass } from "./core.ts";
import type { StateType } from "./lifecycle.ts";

export type RefreshPayoutRule =
  | "SINGLE_WINNER_ALL"
  | "EQUAL_SPLIT_REQUIRED_WITNESSES";

export type RefreshIntentCoreV1 = {
  stateIdDigest: Uint8Array;
  stateDefinitionVersion: number;
  locationScopeDigest: Uint8Array;
  stateType: StateType;
  answerSchemaDigest: Uint8Array;
  freshnessTtlSeconds: number;
  proofPolicyDigest: Uint8Array;
  verificationClass: VerificationClass;
  requiredWitnesses: number;
  maxWitnesses: number;
  payoutRule: RefreshPayoutRule;
  refreshExpiresAtUnix: bigint;
  rewardMint: Uint8Array;
};

const encoder = new TextEncoder();

function concat(parts: readonly Uint8Array[]): Uint8Array {
  const length = parts.reduce((sum, part) => sum + part.length, 0);
  const output = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.length;
  }
  return output;
}

function domain(value: string): Uint8Array {
  return concat([encoder.encode(value), Uint8Array.of(0)]);
}

function u16(value: number): Uint8Array {
  if (!Number.isSafeInteger(value) || value < 0 || value > 0xffff) {
    throw new RangeError("u16 out of range");
  }
  const bytes = new Uint8Array(2);
  new DataView(bytes.buffer).setUint16(0, value, true);
  return bytes;
}

function u32(value: number): Uint8Array {
  if (!Number.isSafeInteger(value) || value < 0 || value > 0xffff_ffff) {
    throw new RangeError("u32 out of range");
  }
  const bytes = new Uint8Array(4);
  new DataView(bytes.buffer).setUint32(0, value, true);
  return bytes;
}

function i64(value: bigint): Uint8Array {
  const min = -(1n << 63n);
  const max = (1n << 63n) - 1n;
  if (value < min || value > max) throw new RangeError("i64 out of range");
  const bytes = new Uint8Array(8);
  new DataView(bytes.buffer).setBigInt64(0, value, true);
  return bytes;
}

function lengthPrefixed(bytes: Uint8Array): Uint8Array {
  return concat([u32(bytes.length), bytes]);
}

function bytes32(value: Uint8Array, name: string): Uint8Array {
  if (value.length !== 32) throw new RangeError(`${name} must be 32 bytes`);
  return value;
}

function uuidBytes(value: string): Uint8Array {
  const hex = value.replaceAll("-", "");
  if (!/^[0-9a-f]{32}$/iu.test(hex)) throw new TypeError("invalid UUID");
  const bytes = new Uint8Array(16);
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = Number.parseInt(hex.slice(index * 2, index * 2 + 2), 16);
  }
  return bytes;
}

function stateTypeCode(value: StateType): number {
  switch (value) {
    case "BINARY":
      return 0;
    case "NUMERIC":
      return 1;
    case "VISUAL":
      return 2;
  }
}

function verificationClassCode(value: VerificationClass): number {
  switch (value) {
    case "FAST":
      return 0;
    case "CORROBORATED":
      return 1;
    case "STRICT":
      return 2;
  }
}

function payoutRuleCode(value: RefreshPayoutRule): number {
  switch (value) {
    case "SINGLE_WINNER_ALL":
      return 0;
    case "EQUAL_SPLIT_REQUIRED_WITNESSES":
      return 1;
  }
}

function canonicalJson(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "string") {
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new TypeError("non-finite JSON number");
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(",")}]`;
  }
  if (typeof value === "object") {
    const record = value as Record<string, unknown>;
    return `{${
      Object.keys(record)
        .sort()
        .map((key) => `${JSON.stringify(key)}:${canonicalJson(record[key])}`)
        .join(",")
    }}`;
  }
  throw new TypeError("value is not canonical JSON");
}

export async function sha256Bytes(value: Uint8Array): Promise<Uint8Array> {
  const bytes = new Uint8Array(value.byteLength);
  bytes.set(value);
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes.buffer));
}

export async function deriveChainRefreshIdV1(refreshId: string): Promise<Uint8Array> {
  return await sha256Bytes(
    concat([domain("NOW_REFRESH_ID_V1"), uuidBytes(refreshId)]),
  );
}

export async function deriveStateIdDigestV1(stateId: string): Promise<Uint8Array> {
  return await sha256Bytes(
    concat([domain("NOW_STATE_ID_V1"), uuidBytes(stateId)]),
  );
}

export async function deriveLocationScopeDigestV1(input: {
  locationId: string;
  centerEwkb: Uint8Array;
  boundaryEwkb: Uint8Array | null;
}): Promise<Uint8Array> {
  return await sha256Bytes(
    concat([
      domain("NOW_LOCATION_SCOPE_V1"),
      uuidBytes(input.locationId),
      lengthPrefixed(input.centerEwkb),
      lengthPrefixed(input.boundaryEwkb ?? new Uint8Array()),
    ]),
  );
}

export async function deriveAnswerSchemaDigestV1(answerSchema: unknown): Promise<Uint8Array> {
  return await sha256Bytes(
    concat([
      domain("NOW_ANSWER_SCHEMA_V1"),
      lengthPrefixed(encoder.encode(canonicalJson(answerSchema))),
    ]),
  );
}

export function encodeRefreshIntentCoreV1(input: RefreshIntentCoreV1): Uint8Array {
  if (
    !Number.isSafeInteger(input.stateDefinitionVersion) ||
    input.stateDefinitionVersion <= 0
  ) {
    throw new RangeError("stateDefinitionVersion must be a positive integer");
  }
  if (
    !Number.isSafeInteger(input.freshnessTtlSeconds) ||
    input.freshnessTtlSeconds <= 0
  ) {
    throw new RangeError("freshnessTtlSeconds must be a positive integer");
  }
  if (
    !Number.isSafeInteger(input.requiredWitnesses) ||
    !Number.isSafeInteger(input.maxWitnesses) ||
    input.requiredWitnesses < 1 ||
    input.maxWitnesses < input.requiredWitnesses ||
    input.maxWitnesses > 3
  ) {
    throw new RangeError("invalid witness terms");
  }

  return concat([
    domain("NOW_INTENT_CORE_V1"),
    u16(1),
    bytes32(input.stateIdDigest, "stateIdDigest"),
    u32(input.stateDefinitionVersion),
    bytes32(input.locationScopeDigest, "locationScopeDigest"),
    Uint8Array.of(stateTypeCode(input.stateType)),
    bytes32(input.answerSchemaDigest, "answerSchemaDigest"),
    u32(input.freshnessTtlSeconds),
    bytes32(input.proofPolicyDigest, "proofPolicyDigest"),
    Uint8Array.of(verificationClassCode(input.verificationClass)),
    Uint8Array.of(input.requiredWitnesses),
    Uint8Array.of(input.maxWitnesses),
    Uint8Array.of(payoutRuleCode(input.payoutRule)),
    i64(input.refreshExpiresAtUnix),
    bytes32(input.rewardMint, "rewardMint"),
  ]);
}

export async function deriveRefreshIntentCoreHashV1(
  input: RefreshIntentCoreV1,
): Promise<Uint8Array> {
  return await sha256Bytes(encodeRefreshIntentCoreV1(input));
}

export function bytesToHex(value: Uint8Array): string {
  return [...value].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
