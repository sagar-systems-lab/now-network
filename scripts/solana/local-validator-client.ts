import { Buffer } from "node:buffer";
import { createHash, generateKeyPairSync, type KeyObject, sign } from "node:crypto";

const RPC_URL = Deno.env.get("NOW_LOCALNET_RPC_URL") ??
  "http://127.0.0.1:8899";
const REWARD_MINT = requiredEnv("NOW_LOCALNET_REWARD_MINT");
const STATE_FILE = requiredEnv("NOW_LOCALNET_STATE_FILE");
const MODE = Deno.env.get("NOW_LOCALNET_MODE") ?? "exercise";

const PROGRAM_ID = "6HnAnrNjHWzyJ6RSDZtQ9mPWGwYehSmw1H8T2RKBwWwA";
const TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
const SYSTEM_PROGRAM_ID = "11111111111111111111111111111111";

type RpcError = {
  code: number;
  message: string;
  data?: unknown;
};

type RpcResponse<T> = {
  jsonrpc: "2.0";
  id: number;
  result?: T;
  error?: RpcError;
};

type AccountInfoResult = {
  value: null | {
    data: [string, string];
    executable: boolean;
    lamports: number;
    owner: string;
  };
};

type PersistedState = {
  admin: string;
  config: string;
  rewardMint: string;
  minimumSlot: number;
};

let rpcId = 1;

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`missing environment variable ${name}`);
  return value;
}

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

async function rpcRaw<T>(
  method: string,
  params: unknown[] = [],
): Promise<RpcResponse<T>> {
  const response = await fetch(RPC_URL, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: rpcId++,
      method,
      params,
    }),
  });
  if (!response.ok) {
    throw new Error(`RPC HTTP ${response.status} for ${method}`);
  }
  return await response.json() as RpcResponse<T>;
}

async function rpc<T>(method: string, params: unknown[] = []): Promise<T> {
  const response = await rpcRaw<T>(method, params);
  if (response.error) {
    throw new Error(`${method}: ${JSON.stringify(response.error)}`);
  }
  if (response.result === undefined) {
    throw new Error(`${method}: missing result`);
  }
  return response.result;
}

function concat(parts: readonly Uint8Array[]): Uint8Array<ArrayBuffer> {
  const size = parts.reduce((sum, part) => sum + part.length, 0);
  const out = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function shortVec(value: number): Uint8Array<ArrayBuffer> {
  assert(Number.isSafeInteger(value) && value >= 0, "invalid shortvec value");
  const bytes: number[] = [];
  let remaining = value;
  while (true) {
    let byte = remaining & 0x7f;
    remaining = Math.floor(remaining / 128);
    if (remaining !== 0) byte |= 0x80;
    bytes.push(byte);
    if (remaining === 0) break;
  }
  return Uint8Array.from(bytes);
}

const BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
const BASE58_INDEX = new Map(
  [...BASE58].map((character, index) => [character, BigInt(index)]),
);

function decodeBase58(input: string): Uint8Array<ArrayBuffer> {
  let value = 0n;
  for (const character of input) {
    const digit = BASE58_INDEX.get(character);
    if (digit === undefined) throw new Error(`invalid base58 character ${character}`);
    value = value * 58n + digit;
  }

  const body: number[] = [];
  while (value > 0n) {
    body.push(Number(value & 0xffn));
    value >>= 8n;
  }
  body.reverse();

  let leadingZeroes = 0;
  while (leadingZeroes < input.length && input[leadingZeroes] === "1") {
    leadingZeroes++;
  }

  return Uint8Array.from([
    ...new Array<number>(leadingZeroes).fill(0),
    ...body,
  ]);
}

function encodeBase58(bytes: Uint8Array): string {
  let leadingZeroes = 0;
  while (leadingZeroes < bytes.length && bytes[leadingZeroes] === 0) {
    leadingZeroes++;
  }

  let value = 0n;
  for (const byte of bytes) value = (value << 8n) + BigInt(byte);

  let encoded = "";
  while (value > 0n) {
    const remainder = Number(value % 58n);
    encoded = BASE58[remainder] + encoded;
    value /= 58n;
  }

  return "1".repeat(leadingZeroes) + encoded;
}

function hexToBytes(hex: string): Uint8Array<ArrayBuffer> {
  assert(hex.length % 2 === 0, "hex input must have even length");
  const out = new Uint8Array(hex.length / 2);
  for (let index = 0; index < out.length; index++) {
    out[index] = Number.parseInt(hex.slice(index * 2, index * 2 + 2), 16);
  }
  return out;
}

function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index++) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function initializeDiscriminator(): Uint8Array<ArrayBuffer> {
  const digest = createHash("sha256")
    .update("global:initialize_protocol")
    .digest();
  return Uint8Array.from(digest.subarray(0, 8));
}

async function latestBlockhash(): Promise<Uint8Array<ArrayBuffer>> {
  const response = await rpc<{
    value: { blockhash: string; lastValidBlockHeight: number };
  }>("getLatestBlockhash", [{ commitment: "confirmed" }]);
  const bytes = decodeBase58(response.value.blockhash);
  assert(bytes.length === 32, "latest blockhash must decode to 32 bytes");
  return bytes;
}

function buildInitializeMessage(
  admin: Uint8Array,
  config: Uint8Array,
  mint: Uint8Array,
  verifier: Uint8Array,
  blockhash: Uint8Array,
): Uint8Array<ArrayBuffer> {
  const tokenProgram = decodeBase58(TOKEN_PROGRAM_ID);
  const systemProgram = decodeBase58(SYSTEM_PROGRAM_ID);
  const programId = decodeBase58(PROGRAM_ID);

  for (
    const [name, key] of [
      ["admin", admin],
      ["config", config],
      ["mint", mint],
      ["token program", tokenProgram],
      ["system program", systemProgram],
      ["program", programId],
    ] as const
  ) {
    assert(key.length === 32, `${name} must be 32 bytes`);
  }

  const accountKeys = [admin, config, mint, tokenProgram, systemProgram, programId];
  const instructionData = concat([initializeDiscriminator(), verifier]);

  return concat([
    Uint8Array.of(
      1, // required signatures
      0, // readonly signed accounts
      4, // readonly unsigned accounts: mint, token, system, program
    ),
    shortVec(accountKeys.length),
    ...accountKeys,
    blockhash,
    shortVec(1),
    Uint8Array.of(5), // program id index
    shortVec(5),
    Uint8Array.of(0, 1, 2, 3, 4),
    shortVec(instructionData.length),
    instructionData,
  ]);
}

function signTransaction(
  message: Uint8Array,
  privateKey: KeyObject,
): Uint8Array<ArrayBuffer> {
  const signature = sign(null, Buffer.from(message), privateKey);
  assert(signature.length === 64, "Ed25519 signature must be 64 bytes");
  return concat([shortVec(1), Uint8Array.from(signature), message]);
}

async function submitInitialize(
  admin: Uint8Array,
  privateKey: KeyObject,
  config: Uint8Array,
  mint: Uint8Array,
  verifier: Uint8Array,
): Promise<RpcResponse<string>> {
  const message = buildInitializeMessage(
    admin,
    config,
    mint,
    verifier,
    await latestBlockhash(),
  );
  const transaction = signTransaction(message, privateKey);
  return await rpcRaw<string>("sendTransaction", [
    Buffer.from(transaction).toString("base64"),
    {
      encoding: "base64",
      skipPreflight: false,
      preflightCommitment: "confirmed",
    },
  ]);
}

async function waitForSignature(signature: string): Promise<void> {
  for (let attempt = 0; attempt < 80; attempt++) {
    const response = await rpc<{
      value: Array<
        null | {
          confirmationStatus: null | "processed" | "confirmed" | "finalized";
          err: unknown;
        }
      >;
    }>("getSignatureStatuses", [[signature], { searchTransactionHistory: true }]);

    const status = response.value[0];
    if (status) {
      if (status.err !== null) {
        throw new Error(`transaction ${signature} failed: ${JSON.stringify(status.err)}`);
      }
      if (
        status.confirmationStatus === "confirmed" ||
        status.confirmationStatus === "finalized"
      ) {
        return;
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`transaction ${signature} was not confirmed`);
}

async function requestAirdrop(pubkey: string): Promise<void> {
  const signature = await rpc<string>("requestAirdrop", [
    pubkey,
    5_000_000_000,
    { commitment: "confirmed" },
  ]);
  await waitForSignature(signature);
}

async function accountInfo(address: string): Promise<AccountInfoResult["value"]> {
  const response = await rpc<AccountInfoResult>("getAccountInfo", [
    address,
    { encoding: "base64", commitment: "confirmed" },
  ]);
  return response.value;
}

async function currentSlot(): Promise<number> {
  return await rpc<number>("getSlot", [{ commitment: "confirmed" }]);
}

async function waitForMinimumSlot(minimumSlot: number): Promise<void> {
  for (let attempt = 0; attempt < 120; attempt++) {
    if (await currentSlot() >= minimumSlot) return;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`validator did not replay through slot ${minimumSlot}`);
}

async function waitForAccount(
  address: string,
): Promise<NonNullable<AccountInfoResult["value"]>> {
  for (let attempt = 0; attempt < 120; attempt++) {
    const info = await accountInfo(address);
    if (info !== null) return info;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`account ${address} did not become available after replay`);
}

function decodeAccountData(
  info: NonNullable<AccountInfoResult["value"]>,
): Uint8Array<ArrayBuffer> {
  assert(info.data[1] === "base64", "expected base64 account data");
  return Uint8Array.from(Buffer.from(info.data[0], "base64"));
}

function verifyConfigData(
  data: Uint8Array<ArrayBuffer>,
  expectedAdmin: Uint8Array,
  expectedMint: Uint8Array,
): void {
  assert(data.length === 142, `unexpected config account size ${data.length}`);

  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  assert(view.getUint16(8, true) === 1, "config version mismatch");
  assert(
    equalBytes(data.subarray(10, 42), expectedAdmin),
    "config admin authority mismatch",
  );
  assert(
    equalBytes(data.subarray(42, 74), expectedAdmin),
    "config verifier authority mismatch",
  );
  assert(
    equalBytes(data.subarray(74, 106), expectedMint),
    "config reward mint mismatch",
  );
  assert(
    equalBytes(data.subarray(106, 138), decodeBase58(TOKEN_PROGRAM_ID)),
    "config token program mismatch",
  );
  assert(data[138] === 0, "config unexpectedly paused");
  assert(view.getUint16(139, true) === 1, "intent schema version mismatch");
}

async function verifyRuntimeState(
  state: PersistedState,
  configHex: string,
): Promise<void> {
  const program = await waitForAccount(PROGRAM_ID);
  assert(program.executable, "program account is not executable");

  const config = await waitForAccount(state.config);
  assert(config.owner === PROGRAM_ID, "config owner mismatch");

  const configBytes = decodeAccountData(config);
  verifyConfigData(
    configBytes,
    decodeBase58(state.admin),
    decodeBase58(state.rewardMint),
  );
  assert(
    equalBytes(decodeBase58(state.config), hexToBytes(configHex)),
    "config PDA does not match repository vector",
  );
}

async function exercise(): Promise<void> {
  const vector = JSON.parse(
    await Deno.readTextFile("test-vectors/solana-pda-v1.json"),
  ) as {
    program_id: string;
    config: { pda: string; pda_hex: string };
  };
  assert(vector.program_id === PROGRAM_ID, "program ID vector mismatch");

  const mint = decodeBase58(REWARD_MINT);
  assert(mint.length === 32, "reward mint must decode to 32 bytes");
  const config = hexToBytes(vector.config.pda_hex);
  assert(config.length === 32, "config PDA vector must be 32 bytes");

  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const publicDer = publicKey.export({ type: "spki", format: "der" });
  const admin = Uint8Array.from(
    publicDer.subarray(publicDer.length - 32),
  );
  assert(admin.length === 32, "generated Ed25519 public key must be 32 bytes");
  const adminAddress = encodeBase58(admin);

  await requestAirdrop(adminAddress);

  const invalid = await submitInitialize(
    admin,
    privateKey,
    config,
    mint,
    new Uint8Array(32),
  );
  assert(invalid.error, "zero verifier initialize unexpectedly succeeded");
  const invalidText = JSON.stringify(invalid.error);
  assert(
    invalidText.includes("InvalidVerifierAuthority") ||
      invalidText.includes("Verifier authority is invalid"),
    `unexpected invalid-verifier error: ${invalidText}`,
  );
  assert(
    await accountInfo(vector.config.pda) === null,
    "failed initialize left config state behind",
  );
  console.log("LOCALNET_INVALID_VERIFIER_ROLLBACK=PASS");

  const valid = await submitInitialize(
    admin,
    privateKey,
    config,
    mint,
    admin,
  );
  if (valid.error || !valid.result) {
    throw new Error(`valid initialize failed: ${JSON.stringify(valid.error)}`);
  }
  await waitForSignature(valid.result);
  console.log(`LOCALNET_INITIALIZE_SIGNATURE=${valid.result}`);

  const configInfo = await accountInfo(vector.config.pda);
  assert(configInfo !== null, "config account missing after initialize");
  assert(configInfo.owner === PROGRAM_ID, "config account owner mismatch");
  verifyConfigData(decodeAccountData(configInfo), admin, mint);
  console.log("LOCALNET_CONFIG_STATE=PASS");

  const snapshot = configInfo.data[0];
  const duplicate = await submitInitialize(
    admin,
    privateKey,
    config,
    mint,
    admin,
  );
  assert(duplicate.error, "duplicate initialize unexpectedly succeeded");

  const afterDuplicate = await accountInfo(vector.config.pda);
  assert(afterDuplicate !== null, "config disappeared after duplicate attempt");
  assert(
    afterDuplicate.data[0] === snapshot,
    "duplicate initialize mutated config state",
  );
  console.log("LOCALNET_DUPLICATE_INITIALIZE_REJECT=PASS");

  const state: PersistedState = {
    admin: adminAddress,
    config: vector.config.pda,
    rewardMint: REWARD_MINT,
    minimumSlot: await currentSlot(),
  };
  await Deno.writeTextFile(STATE_FILE, JSON.stringify(state));
  await verifyRuntimeState(state, vector.config.pda_hex);
  console.log("LOCALNET_RUNTIME_STATE=PASS");
}

async function verifyAfterRestart(): Promise<void> {
  const vector = JSON.parse(
    await Deno.readTextFile("test-vectors/solana-pda-v1.json"),
  ) as {
    config: { pda_hex: string };
  };
  const state = JSON.parse(
    await Deno.readTextFile(STATE_FILE),
  ) as PersistedState;
  await waitForMinimumSlot(state.minimumSlot);
  await verifyRuntimeState(state, vector.config.pda_hex);
  console.log("LOCALNET_RESTART_STATE=PASS");
}

if (MODE === "exercise") {
  await exercise();
} else if (MODE === "verify") {
  await verifyAfterRestart();
} else {
  throw new Error(`unsupported NOW_LOCALNET_MODE ${MODE}`);
}
