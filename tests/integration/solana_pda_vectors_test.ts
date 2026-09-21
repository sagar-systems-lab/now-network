type Vector = {
  schema_version: number;
  program_id_hex: string;
  seeds: {
    config_utf8: string;
    config_hex: string;
    refresh_utf8: string;
    refresh_hex: string;
    contribution_utf8: string;
    contribution_hex: string;
  };
  config: { pda_hex: string; bump: number };
  refresh: { refresh_id_hex: string; pda_hex: string; bump: number };
  contribution: {
    funder_pubkey_hex: string;
    pda_hex: string;
    bump: number;
  };
};

const FIELD_PRIME = (1n << 255n) - 19n;
const CURVE_D = mod(-121665n * modPow(121666n, FIELD_PRIME - 2n));
const SQRT_MINUS_ONE = modPow(2n, (FIELD_PRIME - 1n) / 4n);
const PDA_MARKER = new TextEncoder().encode("ProgramDerivedAddress");

function mod(value: bigint): bigint {
  const result = value % FIELD_PRIME;
  return result >= 0n ? result : result + FIELD_PRIME;
}

function modPow(base: bigint, exponent: bigint): bigint {
  let result = 1n;
  let factor = mod(base);
  let power = exponent;

  while (power > 0n) {
    if ((power & 1n) === 1n) result = mod(result * factor);
    factor = mod(factor * factor);
    power >>= 1n;
  }

  return result;
}

function littleEndianToBigInt(bytes: Uint8Array): bigint {
  let value = 0n;
  for (let index = bytes.length - 1; index >= 0; index--) {
    value = (value << 8n) + BigInt(bytes[index]);
  }
  return value;
}

function isEd25519Point(bytes: Uint8Array): boolean {
  if (bytes.length !== 32) return false;

  const encodedY = Uint8Array.from(bytes);
  const sign = encodedY[31] >> 7;
  encodedY[31] &= 0x7f;

  const y = littleEndianToBigInt(encodedY);
  if (y >= FIELD_PRIME) return false;

  const ySquared = mod(y * y);
  const numerator = mod(ySquared - 1n);
  const denominator = mod(CURVE_D * ySquared + 1n);
  const xSquared = mod(numerator * modPow(denominator, FIELD_PRIME - 2n));

  let x = modPow(xSquared, (FIELD_PRIME + 3n) / 8n);
  if (mod(x * x - xSquared) !== 0n) x = mod(x * SQRT_MINUS_ONE);
  if (mod(x * x - xSquared) !== 0n) return false;
  if (x === 0n && sign === 1) return false;

  return true;
}

function hexToBytes(hex: string): Uint8Array<ArrayBuffer> {
  if (hex.length % 2 !== 0) throw new Error("hex string must have even length");
  const bytes = new Uint8Array(hex.length / 2);
  for (let index = 0; index < bytes.length; index++) {
    bytes[index] = Number.parseInt(hex.slice(index * 2, index * 2 + 2), 16);
  }
  return bytes;
}

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function concat(parts: readonly Uint8Array[]): Uint8Array<ArrayBuffer> {
  const size = parts.reduce((total, part) => total + part.length, 0);
  const out = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

async function sha256(
  parts: readonly Uint8Array[],
): Promise<Uint8Array<ArrayBuffer>> {
  const input = concat(parts);
  return new Uint8Array(await crypto.subtle.digest("SHA-256", input));
}

async function findProgramAddress(
  seeds: readonly Uint8Array[],
  programId: Uint8Array,
): Promise<{ address: Uint8Array; bump: number }> {
  for (let bump = 255; bump >= 0; bump--) {
    const candidate = await sha256([
      ...seeds,
      Uint8Array.of(bump),
      programId,
      PDA_MARKER,
    ]);
    if (!isEd25519Point(candidate)) return { address: candidate, bump };
  }
  throw new Error("no viable PDA bump");
}

function assertEqual(actual: unknown, expected: unknown, label: string): void {
  if (actual !== expected) {
    throw new Error(`${label}: expected ${expected}, got ${actual}`);
  }
}

Deno.test("Solana PDA vectors match independent derivation", async () => {
  const url = new URL("../../test-vectors/solana-pda-v1.json", import.meta.url);
  const vector = JSON.parse(await Deno.readTextFile(url)) as Vector;
  const encoder = new TextEncoder();

  assertEqual(vector.schema_version, 1, "schema version");
  assertEqual(
    bytesToHex(encoder.encode(vector.seeds.config_utf8)),
    vector.seeds.config_hex,
    "config seed bytes",
  );
  assertEqual(
    bytesToHex(encoder.encode(vector.seeds.refresh_utf8)),
    vector.seeds.refresh_hex,
    "refresh seed bytes",
  );
  assertEqual(
    bytesToHex(encoder.encode(vector.seeds.contribution_utf8)),
    vector.seeds.contribution_hex,
    "contribution seed bytes",
  );

  const programId = hexToBytes(vector.program_id_hex);

  const config = await findProgramAddress(
    [encoder.encode(vector.seeds.config_utf8)],
    programId,
  );
  assertEqual(bytesToHex(config.address), vector.config.pda_hex, "config PDA");
  assertEqual(config.bump, vector.config.bump, "config bump");

  const refresh = await findProgramAddress(
    [
      encoder.encode(vector.seeds.refresh_utf8),
      hexToBytes(vector.refresh.refresh_id_hex),
    ],
    programId,
  );
  assertEqual(bytesToHex(refresh.address), vector.refresh.pda_hex, "refresh PDA");
  assertEqual(refresh.bump, vector.refresh.bump, "refresh bump");

  const contribution = await findProgramAddress(
    [
      encoder.encode(vector.seeds.contribution_utf8),
      refresh.address,
      hexToBytes(vector.contribution.funder_pubkey_hex),
    ],
    programId,
  );
  assertEqual(
    bytesToHex(contribution.address),
    vector.contribution.pda_hex,
    "contribution PDA",
  );
  assertEqual(
    contribution.bump,
    vector.contribution.bump,
    "contribution bump",
  );
});
