import bs58 from "npm:bs58@6.0.0";

export const TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
export const ASSOCIATED_TOKEN_PROGRAM_ID = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL";
export const SYSTEM_PROGRAM_ID = "11111111111111111111111111111111";

const FIELD_PRIME = (1n << 255n) - 19n;
const CURVE_D = mod(-121665n * modPow(121666n, FIELD_PRIME - 2n));
const SQRT_MINUS_ONE = modPow(2n, (FIELD_PRIME - 1n) / 4n);
const PDA_MARKER = new TextEncoder().encode("ProgramDerivedAddress");
const textEncoder = new TextEncoder();

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

function concat(parts: readonly Uint8Array[]): Uint8Array {
  const size = parts.reduce((total, part) => total + part.length, 0);
  const output = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.length;
  }
  return output;
}

async function sha256(parts: readonly Uint8Array[]): Promise<Uint8Array> {
  const input = concat(parts);
  const bytes = new Uint8Array(input.byteLength);
  bytes.set(input);
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes.buffer));
}

function decodePublicKey(value: string, label: string): Uint8Array {
  try {
    const bytes = Uint8Array.from(bs58.decode(value));
    if (bytes.length !== 32) throw new Error("wrong length");
    return bytes;
  } catch {
    throw new TypeError(`${label} must be a valid 32-byte Solana public key`);
  }
}

async function findProgramAddress(
  seeds: readonly Uint8Array[],
  programId: Uint8Array,
): Promise<Uint8Array> {
  if (programId.length !== 32) throw new RangeError("programId must be 32 bytes");
  if (seeds.length > 16) throw new RangeError("too many PDA seeds");
  for (const seed of seeds) {
    if (seed.length > 32) throw new RangeError("PDA seed exceeds 32 bytes");
  }

  for (let bump = 255; bump >= 0; bump--) {
    const candidate = await sha256([
      ...seeds,
      Uint8Array.of(bump),
      programId,
      PDA_MARKER,
    ]);
    if (!isEd25519Point(candidate)) return candidate;
  }
  throw new Error("no viable PDA bump");
}

export type RefreshChainAddresses = {
  configAddress: string;
  refreshAddress: string;
  contributionAddress: string;
  vaultTokenAccount: string;
};

export async function deriveRefreshChainAddresses(input: {
  programId: string;
  rewardMint: string;
  creatorWallet: string;
  chainRefreshId: Uint8Array;
}): Promise<RefreshChainAddresses> {
  if (input.chainRefreshId.length !== 32) {
    throw new RangeError("chainRefreshId must be 32 bytes");
  }

  const program = decodePublicKey(input.programId, "programId");
  const rewardMint = decodePublicKey(input.rewardMint, "rewardMint");
  const creator = decodePublicKey(input.creatorWallet, "creatorWallet");
  const tokenProgram = decodePublicKey(TOKEN_PROGRAM_ID, "tokenProgram");
  const associatedTokenProgram = decodePublicKey(
    ASSOCIATED_TOKEN_PROGRAM_ID,
    "associatedTokenProgram",
  );

  const config = await findProgramAddress(
    [textEncoder.encode("config")],
    program,
  );
  const refresh = await findProgramAddress(
    [textEncoder.encode("refresh"), input.chainRefreshId],
    program,
  );
  const contribution = await findProgramAddress(
    [textEncoder.encode("contribution"), refresh, creator],
    program,
  );
  const vault = await findProgramAddress(
    [refresh, tokenProgram, rewardMint],
    associatedTokenProgram,
  );

  return {
    configAddress: bs58.encode(config),
    refreshAddress: bs58.encode(refresh),
    contributionAddress: bs58.encode(contribution),
    vaultTokenAccount: bs58.encode(vault),
  };
}
