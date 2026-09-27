import bs58 from "npm:bs58@6.0.0";
import type { RefreshPayoutRule } from "../../packages/contracts/src/refresh-intent.ts";

export const NOW_SETTLEMENT_PROGRAM_ID = "sE74tJL2pCSWMHhEGvBM5hL2DYmFaUUQCDpC1QkHE3T";

type FetchLike = typeof fetch;

export type ChainFundingInspection =
  | {
    kind: "pending";
  }
  | {
    kind: "failed";
  }
  | {
    kind: "confirmed";
    commitment: "confirmed" | "finalized";
    contributionAmountAtomic: bigint;
    totalFundedAtomic: bigint;
    observedAt: Date;
  };

export interface RefreshChainObserver {
  inspectFunding(input: {
    signature: string;
    refreshAddress: string;
    contributionAddress: string;
    expectedCreatorWallet: string;
    expectedRewardMint: string;
    expectedVaultTokenAccount: string;
    expectedChainRefreshId: Uint8Array;
    expectedStateIdDigest: Uint8Array;
    expectedIntentCoreHash: Uint8Array;
    expectedRefreshExpiresAt: Date;
    expectedVerificationClass: "FAST" | "CORROBORATED" | "STRICT";
    expectedRequiredWitnesses: number;
    expectedMaxWitnesses: number;
    expectedPayoutRule: RefreshPayoutRule;
    fundingTargetAtomic: bigint;
  }): Promise<ChainFundingInspection>;
}

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function decodeBase64(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

async function discriminator(name: string): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(name),
  );
  return new Uint8Array(digest).slice(0, 8);
}

function u16(data: Uint8Array, offset: number): number {
  return new DataView(data.buffer, data.byteOffset, data.byteLength)
    .getUint16(offset, true);
}

function u64(data: Uint8Array, offset: number): bigint {
  return new DataView(data.buffer, data.byteOffset, data.byteLength)
    .getBigUint64(offset, true);
}

function i64(data: Uint8Array, offset: number): bigint {
  return new DataView(data.buffer, data.byteOffset, data.byteLength)
    .getBigInt64(offset, true);
}

function accountKeys(transaction: Record<string, unknown>): string[] {
  const tx = transaction.transaction as Record<string, unknown> | undefined;
  const message = tx?.message as Record<string, unknown> | undefined;
  const raw = Array.isArray(message?.accountKeys) ? message.accountKeys : [];
  const keys = raw.flatMap((value) => {
    if (typeof value === "string") return [value];
    if (value && typeof value === "object") {
      const pubkey = (value as Record<string, unknown>).pubkey;
      return typeof pubkey === "string" ? [pubkey] : [];
    }
    return [];
  });

  const meta = transaction.meta as Record<string, unknown> | undefined;
  const loaded = meta?.loadedAddresses as Record<string, unknown> | undefined;
  for (const group of [loaded?.writable, loaded?.readonly]) {
    if (!Array.isArray(group)) continue;
    for (const value of group) {
      if (typeof value === "string") keys.push(value);
    }
  }
  return keys;
}

function invokesFundingProgram(
  transaction: Record<string, unknown>,
  programId: string,
  refreshAddress: string,
  contributionAddress: string,
): boolean {
  const tx = transaction.transaction as Record<string, unknown> | undefined;
  const message = tx?.message as Record<string, unknown> | undefined;
  const keys = accountKeys(transaction);
  const instructions = Array.isArray(message?.instructions) ? message.instructions : [];

  for (const instruction of instructions) {
    if (!instruction || typeof instruction !== "object") continue;
    const record = instruction as Record<string, unknown>;

    if (typeof record.programId === "string") {
      if (record.programId !== programId) continue;
      const accounts = Array.isArray(record.accounts)
        ? record.accounts.filter((value): value is string => typeof value === "string")
        : [];
      if (accounts.includes(refreshAddress) && accounts.includes(contributionAddress)) {
        return true;
      }
      continue;
    }

    if (
      typeof record.programIdIndex !== "number" ||
      keys[record.programIdIndex] !== programId ||
      !Array.isArray(record.accounts)
    ) {
      continue;
    }

    const accountIndexes = record.accounts.filter(
      (value): value is number => typeof value === "number",
    );
    const instructionAccounts = accountIndexes.map((index) => keys[index]);
    if (
      instructionAccounts.includes(refreshAddress) &&
      instructionAccounts.includes(contributionAddress)
    ) {
      return true;
    }
  }

  return false;
}

export type ChainClaimInspection =
  | { kind: "pending" }
  | { kind: "failed" }
  | {
    kind: "confirmed";
    commitment: "confirmed" | "finalized";
    claimSlot: number;
    claimedAt: Date;
    claimDeadline: Date;
    totalFundedAtomic: bigint;
    lockedRewardAtomic: bigint;
    observedAt: Date;
  };

export interface ClaimChainObserver {
  inspectClaim(input: {
    signature: string;
    refreshAddress: string;
    configAddress: string;
    claimantWallet: string;
    claimantRewardTokenAccount: string;
    expectedRewardMint: string;
    expectedCreatorWallet: string;
    expectedChainRefreshId: Uint8Array;
    expectedStateIdDigest: Uint8Array;
    expectedIntentCoreHash: Uint8Array;
    expectedRefreshExpiresAt: Date;
    expectedMaxWitnesses: number;
    claimDurationSeconds: number;
  }): Promise<ChainClaimInspection>;
}

function u32(data: Uint8Array, offset: number): number {
  return new DataView(data.buffer, data.byteOffset, data.byteLength)
    .getUint32(offset, true);
}

async function invokesClaimInstruction(
  transaction: Record<string, unknown>,
  programId: string,
  input: Parameters<ClaimChainObserver["inspectClaim"]>[0],
): Promise<boolean> {
  const tx = transaction.transaction as Record<string, unknown> | undefined;
  const message = tx?.message as Record<string, unknown> | undefined;
  const keys = accountKeys(transaction);
  const instructions = Array.isArray(message?.instructions) ? message.instructions : [];
  const expectedAccounts = [
    input.claimantWallet,
    input.configAddress,
    input.refreshAddress,
    input.expectedRewardMint,
    input.claimantRewardTokenAccount,
  ];
  const expectedDiscriminator = await discriminator("global:claim_witness");

  for (const instruction of instructions) {
    if (!instruction || typeof instruction !== "object") continue;
    const record = instruction as Record<string, unknown>;

    let instructionProgram: string | undefined;
    let instructionAccounts: string[] = [];
    if (typeof record.programId === "string") {
      instructionProgram = record.programId;
      instructionAccounts = Array.isArray(record.accounts)
        ? record.accounts.filter((value): value is string => typeof value === "string")
        : [];
    } else if (
      typeof record.programIdIndex === "number" &&
      Array.isArray(record.accounts)
    ) {
      instructionProgram = keys[record.programIdIndex];
      instructionAccounts = record.accounts
        .filter((value): value is number => typeof value === "number")
        .map((index) => keys[index]);
    }

    if (instructionProgram !== programId) continue;
    if (
      instructionAccounts.length !== expectedAccounts.length ||
      !expectedAccounts.every((value, index) => instructionAccounts[index] === value)
    ) {
      continue;
    }
    if (typeof record.data !== "string") continue;

    let data: Uint8Array;
    try {
      data = Uint8Array.from(bs58.decode(record.data));
    } catch {
      continue;
    }
    if (data.length !== 44) continue;
    if (!bytesEqual(data.slice(0, 8), expectedDiscriminator)) continue;
    if (!bytesEqual(data.slice(8, 40), input.expectedChainRefreshId)) continue;
    if (u32(data, 40) !== input.claimDurationSeconds) continue;
    return true;
  }

  return false;
}

function verificationCode(value: "FAST" | "CORROBORATED" | "STRICT"): number {
  switch (value) {
    case "FAST":
      return 0;
    case "CORROBORATED":
      return 1;
    case "STRICT":
      return 2;
  }
}

function payoutCode(value: RefreshPayoutRule): number {
  return value === "SINGLE_WINNER_ALL" ? 0 : 1;
}

export class SolanaRpcRefreshChainObserver implements RefreshChainObserver, ClaimChainObserver {
  constructor(
    private readonly rpcUrl: string,
    private readonly programId = NOW_SETTLEMENT_PROGRAM_ID,
    private readonly fetchImpl: FetchLike = fetch,
  ) {}

  private async rpc(method: string, params: unknown[]): Promise<unknown> {
    const response = await this.fetchImpl(this.rpcUrl, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method,
        params,
      }),
    });
    if (!response.ok) throw new Error(`Solana RPC HTTP ${response.status}`);

    const payload = await response.json() as Record<string, unknown>;
    if (payload.error) throw new Error("Solana RPC returned an error");
    return payload.result;
  }

  async inspectFunding(
    input: Parameters<RefreshChainObserver["inspectFunding"]>[0],
  ): Promise<ChainFundingInspection> {
    const statusResult = await this.rpc("getSignatureStatuses", [
      [input.signature],
      { searchTransactionHistory: true },
    ]) as Record<string, unknown>;
    const statusValues = statusResult.value;
    const status = Array.isArray(statusValues) ? statusValues[0] : null;
    if (!status || typeof status !== "object") return { kind: "pending" };

    const statusRecord = status as Record<string, unknown>;
    if (statusRecord.err != null) return { kind: "failed" };

    const confirmation = statusRecord.confirmationStatus;
    if (confirmation !== "confirmed" && confirmation !== "finalized") {
      return { kind: "pending" };
    }

    const transaction = await this.rpc("getTransaction", [
      input.signature,
      {
        encoding: "json",
        commitment: confirmation,
        maxSupportedTransactionVersion: 0,
      },
    ]);
    if (!transaction || typeof transaction !== "object") return { kind: "pending" };

    const transactionRecord = transaction as Record<string, unknown>;
    const meta = transactionRecord.meta as Record<string, unknown> | undefined;
    if (!meta || meta.err != null) return { kind: "failed" };

    const keys = new Set(accountKeys(transactionRecord));
    for (
      const expected of [
        this.programId,
        input.refreshAddress,
        input.contributionAddress,
        input.expectedCreatorWallet,
      ]
    ) {
      if (!keys.has(expected)) return { kind: "failed" };
    }
    if (
      !invokesFundingProgram(
        transactionRecord,
        this.programId,
        input.refreshAddress,
        input.contributionAddress,
      )
    ) {
      return { kind: "failed" };
    }

    const accountResult = await this.rpc("getMultipleAccounts", [
      [input.refreshAddress, input.contributionAddress],
      { encoding: "base64", commitment: confirmation },
    ]) as Record<string, unknown>;
    const values = accountResult.value;
    if (!Array.isArray(values) || values.length !== 2 || !values[0] || !values[1]) {
      return { kind: "pending" };
    }

    const refreshInfo = values[0] as Record<string, unknown>;
    const contributionInfo = values[1] as Record<string, unknown>;
    if (refreshInfo.owner !== this.programId || contributionInfo.owner !== this.programId) {
      return { kind: "failed" };
    }

    const refreshEncoded = refreshInfo.data;
    const contributionEncoded = contributionInfo.data;
    if (
      !Array.isArray(refreshEncoded) ||
      typeof refreshEncoded[0] !== "string" ||
      !Array.isArray(contributionEncoded) ||
      typeof contributionEncoded[0] !== "string"
    ) {
      return { kind: "failed" };
    }

    const refresh = decodeBase64(refreshEncoded[0]);
    const contribution = decodeBase64(contributionEncoded[0]);
    if (refresh.length !== 500 || contribution.length !== 107) {
      return { kind: "failed" };
    }

    const [refreshDiscriminator, contributionDiscriminator] = await Promise.all([
      discriminator("account:RefreshEscrow"),
      discriminator("account:Contribution"),
    ]);
    if (
      !bytesEqual(refresh.slice(0, 8), refreshDiscriminator) ||
      !bytesEqual(contribution.slice(0, 8), contributionDiscriminator)
    ) {
      return { kind: "failed" };
    }

    if (
      u16(refresh, 8) !== 1 ||
      !bytesEqual(refresh.slice(10, 42), input.expectedChainRefreshId) ||
      !bytesEqual(refresh.slice(42, 74), input.expectedStateIdDigest) ||
      !bytesEqual(refresh.slice(74, 106), input.expectedIntentCoreHash) ||
      !bytesEqual(refresh.slice(106, 138), bs58.decode(input.expectedCreatorWallet)) ||
      !bytesEqual(refresh.slice(138, 170), bs58.decode(input.expectedRewardMint)) ||
      !bytesEqual(refresh.slice(170, 202), bs58.decode(input.expectedVaultTokenAccount)) ||
      i64(refresh, 242) !== BigInt(Math.floor(input.expectedRefreshExpiresAt.getTime() / 1000)) ||
      refresh[250] !== verificationCode(input.expectedVerificationClass) ||
      refresh[251] !== input.expectedRequiredWitnesses ||
      refresh[252] !== input.expectedMaxWitnesses ||
      refresh[253] !== payoutCode(input.expectedPayoutRule) ||
      refresh[254] !== 0
    ) {
      return { kind: "failed" };
    }

    const refreshAddressBytes = bs58.decode(input.refreshAddress);
    if (
      u16(contribution, 8) !== 1 ||
      !bytesEqual(contribution.slice(10, 42), refreshAddressBytes) ||
      !bytesEqual(contribution.slice(42, 74), bs58.decode(input.expectedCreatorWallet))
    ) {
      return { kind: "failed" };
    }

    const totalFundedAtomic = u64(refresh, 255);
    const contributionAmountAtomic = u64(contribution, 74);
    if (
      totalFundedAtomic < input.fundingTargetAtomic ||
      contributionAmountAtomic < input.fundingTargetAtomic
    ) {
      return { kind: "pending" };
    }

    return {
      kind: "confirmed",
      commitment: confirmation,
      contributionAmountAtomic,
      totalFundedAtomic,
      observedAt: new Date(),
    };
  }

  async inspectClaim(
    input: Parameters<ClaimChainObserver["inspectClaim"]>[0],
  ): Promise<ChainClaimInspection> {
    if (
      !Number.isSafeInteger(input.claimDurationSeconds) ||
      input.claimDurationSeconds <= 0 ||
      input.claimDurationSeconds > 4_294_967_295 ||
      !Number.isSafeInteger(input.expectedMaxWitnesses) ||
      input.expectedMaxWitnesses < 1 ||
      input.expectedMaxWitnesses > 3
    ) {
      return { kind: "failed" };
    }

    const statusResult = await this.rpc("getSignatureStatuses", [
      [input.signature],
      { searchTransactionHistory: true },
    ]) as Record<string, unknown>;
    const statusValues = statusResult.value;
    const status = Array.isArray(statusValues) ? statusValues[0] : null;
    if (!status || typeof status !== "object") return { kind: "pending" };

    const statusRecord = status as Record<string, unknown>;
    if (statusRecord.err != null) return { kind: "failed" };
    const confirmation = statusRecord.confirmationStatus;
    if (confirmation !== "confirmed" && confirmation !== "finalized") {
      return { kind: "pending" };
    }

    const transaction = await this.rpc("getTransaction", [
      input.signature,
      {
        encoding: "json",
        commitment: confirmation,
        maxSupportedTransactionVersion: 0,
      },
    ]);
    if (!transaction || typeof transaction !== "object") return { kind: "pending" };
    const transactionRecord = transaction as Record<string, unknown>;
    const meta = transactionRecord.meta as Record<string, unknown> | undefined;
    if (!meta || meta.err != null) return { kind: "failed" };
    if (!await invokesClaimInstruction(transactionRecord, this.programId, input)) {
      return { kind: "failed" };
    }

    const accountResult = await this.rpc("getAccountInfo", [
      input.refreshAddress,
      { encoding: "base64", commitment: confirmation },
    ]) as Record<string, unknown>;
    const value = accountResult.value;
    if (!value || typeof value !== "object") return { kind: "pending" };
    const account = value as Record<string, unknown>;
    if (account.owner !== this.programId) return { kind: "failed" };
    const encoded = account.data;
    if (!Array.isArray(encoded) || typeof encoded[0] !== "string") {
      return { kind: "failed" };
    }

    const refresh = decodeBase64(encoded[0]);
    if (refresh.length !== 500) return { kind: "failed" };
    const refreshDiscriminator = await discriminator("account:RefreshEscrow");
    if (!bytesEqual(refresh.slice(0, 8), refreshDiscriminator)) {
      return { kind: "failed" };
    }
    if (
      u16(refresh, 8) !== 1 ||
      !bytesEqual(refresh.slice(10, 42), input.expectedChainRefreshId) ||
      !bytesEqual(refresh.slice(42, 74), input.expectedStateIdDigest) ||
      !bytesEqual(refresh.slice(74, 106), input.expectedIntentCoreHash) ||
      !bytesEqual(refresh.slice(106, 138), bs58.decode(input.expectedCreatorWallet)) ||
      !bytesEqual(refresh.slice(138, 170), bs58.decode(input.expectedRewardMint)) ||
      i64(refresh, 242) !== BigInt(Math.floor(input.expectedRefreshExpiresAt.getTime() / 1000)) ||
      refresh[252] !== input.expectedMaxWitnesses ||
      refresh[254] !== 1 ||
      refresh[271] !== 1
    ) {
      return { kind: "failed" };
    }

    const totalFundedAtomic = u64(refresh, 255);
    const lockedRewardAtomic = u64(refresh, 263);
    if (totalFundedAtomic === 0n || lockedRewardAtomic !== totalFundedAtomic) {
      return { kind: "failed" };
    }

    const claimant = Uint8Array.from(bs58.decode(input.claimantWallet));
    let claimSlot = -1;
    for (let slot = 0; slot < input.expectedMaxWitnesses; slot += 1) {
      const claimantOffset = 272 + slot * 32;
      const statusOffset = 416 + slot;
      if (
        refresh[statusOffset] === 1 &&
        bytesEqual(refresh.slice(claimantOffset, claimantOffset + 32), claimant)
      ) {
        if (claimSlot !== -1) return { kind: "failed" };
        claimSlot = slot;
      }
    }
    if (claimSlot === -1) return { kind: "failed" };

    const claimedAtUnix = i64(refresh, 368 + claimSlot * 8);
    const claimDeadlineUnix = i64(refresh, 392 + claimSlot * 8);
    if (
      claimedAtUnix <= 0n ||
      claimDeadlineUnix <= claimedAtUnix ||
      claimDeadlineUnix - claimedAtUnix !== BigInt(input.claimDurationSeconds) ||
      claimDeadlineUnix > BigInt(Math.floor(input.expectedRefreshExpiresAt.getTime() / 1000))
    ) {
      return { kind: "failed" };
    }

    const blockTime = transactionRecord.blockTime;
    const observedAt = typeof blockTime === "number" && Number.isFinite(blockTime)
      ? new Date(blockTime * 1000)
      : new Date();

    return {
      kind: "confirmed",
      commitment: confirmation,
      claimSlot,
      claimedAt: new Date(Number(claimedAtUnix) * 1000),
      claimDeadline: new Date(Number(claimDeadlineUnix) * 1000),
      totalFundedAtomic,
      lockedRewardAtomic,
      observedAt,
    };
  }
}
