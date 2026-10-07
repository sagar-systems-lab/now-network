import bs58 from "npm:bs58@6.0.0";
import { deriveClaimChainAddresses } from "../src/solana-refresh-addresses.ts";
import {
  NOW_SETTLEMENT_PROGRAM_ID,
  SolanaRpcRefreshChainObserver,
} from "../src/solana-refresh-observer.ts";

const CLAIMANT = bs58.encode(new Uint8Array(32).fill(7));
const CREATOR = bs58.encode(new Uint8Array(32).fill(8));
const MINT = "So11111111111111111111111111111111111111112";
const SIGNATURE = bs58.encode(new Uint8Array(64).fill(9));
const CHAIN_ID = new Uint8Array(32).fill(0x11);
const STATE_DIGEST = new Uint8Array(32).fill(0x22);
const INTENT_HASH = new Uint8Array(32).fill(0x33);
const EXPIRES = new Date("2026-09-25T12:15:00.000Z");
const CLAIMED_AT = 1_790_337_620;
const CLAIM_DURATION = 180;

function writeU16(data: Uint8Array, offset: number, value: number): void {
  new DataView(data.buffer).setUint16(offset, value, true);
}
function writeU32(data: Uint8Array, offset: number, value: number): void {
  new DataView(data.buffer).setUint32(offset, value, true);
}
function writeU64(data: Uint8Array, offset: number, value: bigint): void {
  new DataView(data.buffer).setBigUint64(offset, value, true);
}
function writeI64(data: Uint8Array, offset: number, value: bigint): void {
  new DataView(data.buffer).setBigInt64(offset, value, true);
}

async function discriminator(name: string): Promise<Uint8Array> {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(name));
  return new Uint8Array(hash).slice(0, 8);
}

function base64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

async function instructionData(duration = CLAIM_DURATION): Promise<string> {
  const data = new Uint8Array(44);
  data.set(await discriminator("global:claim_witness"), 0);
  data.set(CHAIN_ID, 8);
  writeU32(data, 40, duration);
  return bs58.encode(data);
}

async function refreshBytes(options?: {
  claimant?: string;
  intentHash?: Uint8Array;
}): Promise<Uint8Array> {
  const data = new Uint8Array(500);
  data.set(await discriminator("account:RefreshEscrow"), 0);
  writeU16(data, 8, 1);
  data.set(CHAIN_ID, 10);
  data.set(STATE_DIGEST, 42);
  data.set(options?.intentHash ?? INTENT_HASH, 74);
  data.set(bs58.decode(CREATOR), 106);
  data.set(bs58.decode(MINT), 138);
  writeI64(data, 242, BigInt(Math.floor(EXPIRES.getTime() / 1000)));
  data[251] = 1;
  data[252] = 1;
  data[253] = 0;
  data[254] = 1;
  writeU64(data, 255, 1_000_000n);
  writeU64(data, 263, 1_000_000n);
  data[271] = 1;
  data.set(bs58.decode(options?.claimant ?? CLAIMANT), 272);
  writeI64(data, 368, BigInt(CLAIMED_AT));
  writeI64(data, 392, BigInt(CLAIMED_AT + CLAIM_DURATION));
  data[416] = 1;
  return data;
}

type Fixture = {
  confirmation?: "processed" | "confirmed" | "finalized";
  signatureError?: unknown;
  accounts: string[];
  duration?: number;
  refreshOwner?: string;
  refreshData: Uint8Array;
};

function fakeFetch(fixture: Fixture): typeof fetch {
  return (async (_input: string | URL | Request, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body)) as { method: string; params: unknown[] };
    if (body.method === "getSignaturesForAddress") {
      return Response.json({
        jsonrpc: "2.0",
        id: 1,
        result: [
          { signature: "funding-signature", err: null },
          { signature: SIGNATURE, err: fixture.signatureError ?? null },
        ],
      });
    }
    if (body.method === "getSignatureStatuses") {
      return Response.json({
        jsonrpc: "2.0",
        id: 1,
        result: {
          value: fixture.confirmation
            ? [{
              err: fixture.signatureError ?? null,
              confirmationStatus: fixture.confirmation,
            }]
            : [null],
        },
      });
    }
    if (body.method === "getTransaction") {
      return Response.json({
        jsonrpc: "2.0",
        id: 1,
        result: {
          blockTime: CLAIMED_AT + 2,
          transaction: {
            message: {
              accountKeys: fixture.accounts,
              instructions: body.params[0] === "funding-signature" ? [] : [{
                programId: NOW_SETTLEMENT_PROGRAM_ID,
                accounts: fixture.accounts,
                data: await instructionData(fixture.duration),
              }],
            },
          },
          meta: { err: null, loadedAddresses: { writable: [], readonly: [] } },
        },
      });
    }
    if (body.method === "getAccountInfo") {
      return Response.json({
        jsonrpc: "2.0",
        id: 1,
        result: {
          value: {
            owner: fixture.refreshOwner ?? NOW_SETTLEMENT_PROGRAM_ID,
            data: [base64(fixture.refreshData), "base64"],
          },
        },
      });
    }
    return Response.json({
      jsonrpc: "2.0",
      id: 1,
      error: { code: -32601, message: "unexpected method" },
    });
  }) as typeof fetch;
}

async function fixture(options?: {
  confirmation?: "processed" | "confirmed" | "finalized";
  signatureError?: unknown;
  duration?: number;
  claimant?: string;
  intentHash?: Uint8Array;
  refreshOwner?: string;
  omitAccount?: string;
}) {
  const addresses = await deriveClaimChainAddresses({
    programId: NOW_SETTLEMENT_PROGRAM_ID,
    rewardMint: MINT,
    claimantWallet: CLAIMANT,
    chainRefreshId: CHAIN_ID,
  });
  const accounts = [
    CLAIMANT,
    addresses.configAddress,
    addresses.refreshAddress,
    MINT,
    addresses.claimantRewardTokenAccount,
  ].filter((value) => value !== options?.omitAccount);
  const observer = new SolanaRpcRefreshChainObserver(
    "https://rpc.invalid",
    NOW_SETTLEMENT_PROGRAM_ID,
    fakeFetch({
      confirmation: options?.confirmation ?? "confirmed",
      signatureError: options?.signatureError,
      accounts,
      duration: options?.duration,
      refreshOwner: options?.refreshOwner,
      refreshData: await refreshBytes({
        claimant: options?.claimant,
        intentHash: options?.intentHash,
      }),
    }),
  );
  return {
    observer,
    input: {
      signature: SIGNATURE,
      refreshAddress: addresses.refreshAddress,
      configAddress: addresses.configAddress,
      claimantWallet: CLAIMANT,
      claimantRewardTokenAccount: addresses.claimantRewardTokenAccount,
      expectedRewardMint: MINT,
      expectedCreatorWallet: CREATOR,
      expectedChainRefreshId: CHAIN_ID,
      expectedStateIdDigest: STATE_DIGEST,
      expectedIntentCoreHash: INTENT_HASH,
      expectedRefreshExpiresAt: EXPIRES,
      expectedMaxWitnesses: 1,
      claimDurationSeconds: CLAIM_DURATION,
    },
  };
}

Deno.test("Solana observer confirms only the exact witness claim", async () => {
  const state = await fixture();
  const result = await state.observer.inspectClaim(state.input);
  if (
    result.kind !== "confirmed" ||
    result.claimSlot !== 0 ||
    result.totalFundedAtomic !== 1_000_000n ||
    result.lockedRewardAtomic !== 1_000_000n ||
    result.claimDeadline.getTime() - result.claimedAt.getTime() !== CLAIM_DURATION * 1000
  ) {
    throw new Error("valid witness claim was not confirmed exactly");
  }
});

Deno.test("callback recovery skips funding signatures and requires the exact claimant", async () => {
  const valid = await fixture();
  if (await valid.observer.findClaimSignature(valid.input) !== SIGNATURE) {
    throw new Error("confirmed claim signature was not recovered");
  }
  const wrongWallet = await fixture({ claimant: CREATOR });
  if (await wrongWallet.observer.findClaimSignature(wrongWallet.input) !== null) {
    throw new Error("a different claimant was recovered");
  }
  const failed = await fixture({ signatureError: "failed" });
  if (await failed.observer.findClaimSignature(failed.input) !== null) {
    throw new Error("a failed signature was recovered");
  }
});

Deno.test("Solana observer never treats processed or failed signatures as confirmed", async () => {
  const pending = await fixture({ confirmation: "processed" });
  if ((await pending.observer.inspectClaim(pending.input)).kind !== "pending") {
    throw new Error("processed signature must remain pending");
  }
  const failed = await fixture({ signatureError: { InstructionError: [0, "Custom"] } });
  if ((await failed.observer.inspectClaim(failed.input)).kind !== "failed") {
    throw new Error("failed signature was accepted");
  }
});

Deno.test("Solana observer binds claim duration and exact account set", async () => {
  const wrongDuration = await fixture({ duration: CLAIM_DURATION + 1 });
  if ((await wrongDuration.observer.inspectClaim(wrongDuration.input)).kind !== "failed") {
    throw new Error("mutated claim duration was accepted");
  }
  const addresses = await deriveClaimChainAddresses({
    programId: NOW_SETTLEMENT_PROGRAM_ID,
    rewardMint: MINT,
    claimantWallet: CLAIMANT,
    chainRefreshId: CHAIN_ID,
  });
  const missingAta = await fixture({ omitAccount: addresses.claimantRewardTokenAccount });
  if ((await missingAta.observer.inspectClaim(missingAta.input)).kind !== "failed") {
    throw new Error("incomplete claim account set was accepted");
  }
});

Deno.test("Solana observer rejects claimant, intent, and owner drift", async () => {
  const wrongClaimant = await fixture({
    claimant: bs58.encode(new Uint8Array(32).fill(0x55)),
  });
  if ((await wrongClaimant.observer.inspectClaim(wrongClaimant.input)).kind !== "failed") {
    throw new Error("different on-chain claimant was accepted");
  }
  const wrongIntent = await fixture({ intentHash: new Uint8Array(32).fill(0x44) });
  if ((await wrongIntent.observer.inspectClaim(wrongIntent.input)).kind !== "failed") {
    throw new Error("mutated refresh intent was accepted");
  }
  const wrongOwner = await fixture({ refreshOwner: CREATOR });
  if ((await wrongOwner.observer.inspectClaim(wrongOwner.input)).kind !== "failed") {
    throw new Error("wrong refresh account owner was accepted");
  }
});
