import bs58 from "npm:bs58@6.0.0";
import { deriveRefreshChainAddresses } from "../src/solana-refresh-addresses.ts";
import {
  NOW_SETTLEMENT_PROGRAM_ID,
  SolanaRpcRefreshChainObserver,
} from "../src/solana-refresh-observer.ts";

const CREATOR = "11111111111111111111111111111111";
const MINT = "So11111111111111111111111111111111111111112";
const SIGNATURE = bs58.encode(new Uint8Array(64).fill(9));
const CHAIN_ID = new Uint8Array(32).fill(0x11);
const STATE_DIGEST = new Uint8Array(32).fill(0x22);
const INTENT_HASH = new Uint8Array(32).fill(0x33);
const EXPIRES = new Date("2026-09-24T10:15:00.000Z");

function writeU16(data: Uint8Array, offset: number, value: number): void {
  new DataView(data.buffer).setUint16(offset, value, true);
}

function writeU64(data: Uint8Array, offset: number, value: bigint): void {
  new DataView(data.buffer).setBigUint64(offset, value, true);
}

function writeI64(data: Uint8Array, offset: number, value: bigint): void {
  new DataView(data.buffer).setBigInt64(offset, value, true);
}

async function anchorDiscriminator(name: string): Promise<Uint8Array> {
  const hash = new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(name)),
  );
  return hash.slice(0, 8);
}

function base64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

async function refreshBytes(
  refreshAddress: string,
  options?: {
    totalFunded?: bigint;
    intentHash?: Uint8Array;
    creator?: string;
  },
): Promise<Uint8Array> {
  const data = new Uint8Array(500);
  data.set(await anchorDiscriminator("account:RefreshEscrow"), 0);
  writeU16(data, 8, 1);
  data.set(CHAIN_ID, 10);
  data.set(STATE_DIGEST, 42);
  data.set(options?.intentHash ?? INTENT_HASH, 74);
  data.set(bs58.decode(options?.creator ?? CREATOR), 106);
  data.set(bs58.decode(MINT), 138);
  data.set(bs58.decode(refreshAddress), 170);
  writeI64(data, 242, BigInt(Math.floor(EXPIRES.getTime() / 1000)));
  data[250] = 0;
  data[251] = 1;
  data[252] = 1;
  data[253] = 0;
  data[254] = 0;
  writeU64(data, 255, options?.totalFunded ?? 1_000_000n);
  return data;
}

async function contributionBytes(
  refreshAddress: string,
  options?: { amount?: bigint; funder?: string },
): Promise<Uint8Array> {
  const data = new Uint8Array(107);
  data.set(await anchorDiscriminator("account:Contribution"), 0);
  writeU16(data, 8, 1);
  data.set(bs58.decode(refreshAddress), 10);
  data.set(bs58.decode(options?.funder ?? CREATOR), 42);
  writeU64(data, 74, options?.amount ?? 1_000_000n);
  return data;
}

type RpcFixture = {
  confirmation?: "processed" | "confirmed" | "finalized";
  signatureError?: unknown;
  transactionKeys?: string[];
  refreshOwner?: string;
  contributionOwner?: string;
  refreshData?: Uint8Array;
  contributionData?: Uint8Array;
};

function fakeFetch(fixture: RpcFixture): typeof fetch {
  return (async (_input: string | URL | Request, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body)) as {
      method: string;
    };

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
          blockTime: 1_790_244_060,
          transaction: {
            message: {
              accountKeys: fixture.transactionKeys ?? [],
            },
          },
          meta: {
            err: null,
            loadedAddresses: { writable: [], readonly: [] },
          },
        },
      });
    }

    if (body.method === "getMultipleAccounts") {
      return Response.json({
        jsonrpc: "2.0",
        id: 1,
        result: {
          value: [
            {
              owner: fixture.refreshOwner ?? NOW_SETTLEMENT_PROGRAM_ID,
              data: [base64(fixture.refreshData!), "base64"],
            },
            {
              owner: fixture.contributionOwner ?? NOW_SETTLEMENT_PROGRAM_ID,
              data: [base64(fixture.contributionData!), "base64"],
            },
          ],
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
  totalFunded?: bigint;
  contributionAmount?: bigint;
  intentHash?: Uint8Array;
  funder?: string;
  refreshOwner?: string;
  contributionOwner?: string;
  confirmation?: "processed" | "confirmed" | "finalized";
  omitTransactionAccount?: string;
}): Promise<{
  observer: SolanaRpcRefreshChainObserver;
  input: Parameters<SolanaRpcRefreshChainObserver["inspectFunding"]>[0];
}> {
  const addresses = deriveRefreshChainAddresses({
    programId: NOW_SETTLEMENT_PROGRAM_ID,
    rewardMint: MINT,
    creatorWallet: CREATOR,
    chainRefreshId: CHAIN_ID,
  });
  const keys = [
    NOW_SETTLEMENT_PROGRAM_ID,
    addresses.refreshAddress,
    addresses.contributionAddress,
    CREATOR,
  ].filter((value) => value !== options?.omitTransactionAccount);

  return {
    observer: new SolanaRpcRefreshChainObserver(
      "https://rpc.invalid",
      NOW_SETTLEMENT_PROGRAM_ID,
      fakeFetch({
        confirmation: options?.confirmation ?? "confirmed",
        transactionKeys: keys,
        refreshOwner: options?.refreshOwner,
        contributionOwner: options?.contributionOwner,
        refreshData: await refreshBytes(addresses.vaultTokenAccount, {
          totalFunded: options?.totalFunded,
          intentHash: options?.intentHash,
        }),
        contributionData: await contributionBytes(addresses.refreshAddress, {
          amount: options?.contributionAmount,
          funder: options?.funder,
        }),
      }),
    ),
    input: {
      signature: SIGNATURE,
      refreshAddress: addresses.refreshAddress,
      contributionAddress: addresses.contributionAddress,
      expectedCreatorWallet: CREATOR,
      expectedRewardMint: MINT,
      expectedChainRefreshId: CHAIN_ID,
      expectedStateIdDigest: STATE_DIGEST,
      expectedIntentCoreHash: INTENT_HASH,
      expectedRefreshExpiresAt: EXPIRES,
      expectedVerificationClass: "FAST",
      expectedRequiredWitnesses: 1,
      expectedMaxWitnesses: 1,
      expectedPayoutRule: "SINGLE_WINNER_ALL",
      fundingTargetAtomic: 1_000_000n,
    },
  };
}

Deno.test("Solana observer accepts exact confirmed refresh funding", async () => {
  const setup = await fixture({});
  const result = await setup.observer.inspectFunding(setup.input);
  if (
    result.kind !== "confirmed" ||
    result.totalFundedAtomic !== 1_000_000n ||
    result.contributionAmountAtomic !== 1_000_000n
  ) {
    throw new Error("valid funding proof was not confirmed");
  }
});

Deno.test("Solana observer keeps unconfirmed signature pending", async () => {
  const setup = await fixture({ confirmation: "processed" });
  const result = await setup.observer.inspectFunding(setup.input);
  if (result.kind !== "pending") throw new Error("processed signature must remain pending");
});

Deno.test("Solana observer rejects wrong account owner and transaction binding", async () => {
  const wrongOwner = await fixture({ refreshOwner: CREATOR });
  if ((await wrongOwner.observer.inspectFunding(wrongOwner.input)).kind !== "failed") {
    throw new Error("wrong program owner was accepted");
  }

  const missingAccount = await fixture({
    omitTransactionAccount: NOW_SETTLEMENT_PROGRAM_ID,
  });
  if ((await missingAccount.observer.inspectFunding(missingAccount.input)).kind !== "failed") {
    throw new Error("unbound transaction account set was accepted");
  }
});

Deno.test("Solana observer rejects mutated intent and wrong funder", async () => {
  const mutatedIntent = await fixture({
    intentHash: new Uint8Array(32).fill(0x44),
  });
  if ((await mutatedIntent.observer.inspectFunding(mutatedIntent.input)).kind !== "failed") {
    throw new Error("mutated intent hash was accepted");
  }

  const otherFunder = bs58.encode(new Uint8Array(32).fill(0x55));
  const wrongFunder = await fixture({ funder: otherFunder });
  if ((await wrongFunder.observer.inspectFunding(wrongFunder.input)).kind !== "failed") {
    throw new Error("wrong contribution funder was accepted");
  }
});

Deno.test("Solana observer does not publish below target funding", async () => {
  const setup = await fixture({
    totalFunded: 999_999n,
    contributionAmount: 999_999n,
  });
  const result = await setup.observer.inspectFunding(setup.input);
  if (result.kind !== "pending") throw new Error("underfunded refresh was published");
});
