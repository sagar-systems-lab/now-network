import { Buffer } from "node:buffer";
import bs58 from "npm:bs58@6.0.0";
import {
  Keypair,
  PublicKey,
  Transaction,
  TransactionInstruction,
} from "npm:@solana/web3.js@1.98.4";
import type { SettlementOperation } from "./settlement-repository.ts";
import { NOW_SETTLEMENT_PROGRAM_ID } from "./solana-refresh-observer.ts";

const TOKEN_PROGRAM_ID = new PublicKey(
  "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
);
const ASSOCIATED_TOKEN_PROGRAM_ID = new PublicKey(
  "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL",
);
const CONFIG_SEED = new TextEncoder().encode("config");

type FetchLike = typeof fetch;

type RpcResponse<T> = {
  jsonrpc?: string;
  id?: number;
  result?: T;
  error?: unknown;
};

export type SettlementSubmission =
  | {
    kind: "submitted";
    signature: string;
    recentBlockhash: string;
    lastValidBlockHeight: number;
  }
  | {
    kind: "ambiguous";
    signature: string;
    recentBlockhash: string;
    lastValidBlockHeight: number;
    errorCode: string;
  };

export type SettlementInspection =
  | { kind: "pending" }
  | {
    kind: "confirmed";
    commitment: "confirmed" | "finalized";
    signature: string;
    observedAt: Date;
  }
  | { kind: "not_settled"; observedAt: Date }
  | { kind: "authority_conflict"; errorCode: string; observedAt: Date };

export interface SettlementChainClient {
  submit(operation: SettlementOperation): Promise<SettlementSubmission>;
  inspect(operation: SettlementOperation): Promise<SettlementInspection>;
}

export class SettlementChainError extends Error {
  constructor(
    readonly code: string,
    readonly retryable: boolean,
    message: string,
  ) {
    super(message);
    this.name = "SettlementChainError";
  }
}

function bytesEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function u16(data: Uint8Array, offset: number): number {
  return new DataView(data.buffer, data.byteOffset, data.byteLength)
    .getUint16(offset, true);
}

function u64(data: Uint8Array, offset: number): bigint {
  return new DataView(data.buffer, data.byteOffset, data.byteLength)
    .getBigUint64(offset, true);
}

async function discriminator(name: string): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(name),
  );
  return new Uint8Array(digest).slice(0, 8);
}

function decodeAccountData(value: unknown): Uint8Array {
  if (!value || typeof value !== "object") {
    throw new SettlementChainError(
      "CHAIN_ACCOUNT_MISSING",
      true,
      "Solana account is not available yet.",
    );
  }
  const account = value as Record<string, unknown>;
  const encoded = account.data;
  if (!Array.isArray(encoded) || typeof encoded[0] !== "string") {
    throw new SettlementChainError(
      "CHAIN_ACCOUNT_ENCODING_INVALID",
      false,
      "Solana account data is malformed.",
    );
  }
  return Uint8Array.from(Buffer.from(encoded[0], "base64"));
}

function ownerOf(value: unknown): string | null {
  if (!value || typeof value !== "object") return null;
  const owner = (value as Record<string, unknown>).owner;
  return typeof owner === "string" ? owner : null;
}

function deriveAssociatedTokenAddress(owner: PublicKey, mint: PublicKey): PublicKey {
  return PublicKey.findProgramAddressSync(
    [owner.toBuffer(), TOKEN_PROGRAM_ID.toBuffer(), mint.toBuffer()],
    ASSOCIATED_TOKEN_PROGRAM_ID,
  )[0];
}

function selectedCount(mask: number): number {
  let count = 0;
  for (let bit = 0; bit < 3; bit += 1) {
    if ((mask & (1 << bit)) !== 0) count += 1;
  }
  return count;
}

export function settlementVerifierFromJson(raw: string): Keypair {
  let decoded: unknown;
  try {
    decoded = JSON.parse(raw);
  } catch {
    throw new Error("NOW_SETTLEMENT_VERIFIER_KEYPAIR_JSON must be valid JSON");
  }
  if (
    !Array.isArray(decoded) ||
    decoded.length !== 64 ||
    decoded.some((value) =>
      !Number.isSafeInteger(value) || Number(value) < 0 || Number(value) > 255
    )
  ) {
    throw new Error(
      "NOW_SETTLEMENT_VERIFIER_KEYPAIR_JSON must contain exactly 64 byte values",
    );
  }
  return Keypair.fromSecretKey(Uint8Array.from(decoded as number[]));
}

export class SolanaSettlementClient implements SettlementChainClient {
  private readonly programId: PublicKey;
  private readonly configAddress: PublicKey;

  constructor(
    private readonly rpcUrl: string,
    private readonly verifier: Keypair,
    programId = NOW_SETTLEMENT_PROGRAM_ID,
    private readonly fetchImpl: FetchLike = fetch,
  ) {
    this.programId = new PublicKey(programId);
    this.configAddress = PublicKey.findProgramAddressSync(
      [CONFIG_SEED],
      this.programId,
    )[0];
  }

  private async rpcRaw<T>(
    method: string,
    params: unknown[],
  ): Promise<RpcResponse<T>> {
    let response: Response;
    try {
      response = await this.fetchImpl(this.rpcUrl, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 1,
          method,
          params,
        }),
      });
    } catch {
      throw new SettlementChainError(
        "SOLANA_RPC_NETWORK_ERROR",
        true,
        "Solana RPC network request failed.",
      );
    }
    if (!response.ok) {
      throw new SettlementChainError(
        "SOLANA_RPC_HTTP_ERROR",
        true,
        `Solana RPC HTTP ${response.status}.`,
      );
    }
    return await response.json() as RpcResponse<T>;
  }

  private async rpc<T>(method: string, params: unknown[]): Promise<T> {
    const response = await this.rpcRaw<T>(method, params);
    if (response.error !== undefined) {
      throw new SettlementChainError(
        "SOLANA_RPC_ERROR",
        true,
        `Solana RPC ${method} returned an error.`,
      );
    }
    if (response.result === undefined) {
      throw new SettlementChainError(
        "SOLANA_RPC_RESULT_MISSING",
        true,
        `Solana RPC ${method} returned no result.`,
      );
    }
    return response.result;
  }

  private async accountInfo(
    address: PublicKey,
    commitment: "confirmed" | "finalized",
  ): Promise<unknown> {
    const result = await this.rpc<Record<string, unknown>>("getAccountInfo", [
      address.toBase58(),
      { encoding: "base64", commitment },
    ]);
    return result.value;
  }

  private async assertPinnedAuthority(operation: SettlementOperation): Promise<{
    refresh: PublicKey;
    rewardMint: PublicKey;
    vault: PublicKey;
    recipients: PublicKey[];
  }> {
    let refresh: PublicKey;
    let rewardMint: PublicKey;
    try {
      refresh = new PublicKey(operation.chainRefreshAddress);
      rewardMint = new PublicKey(operation.rewardMint);
    } catch {
      throw new SettlementChainError(
        "SETTLEMENT_ADDRESS_INVALID",
        false,
        "Stored settlement addresses are invalid.",
      );
    }

    if (
      operation.chainRefreshId.length !== 32 ||
      operation.executionHash.length !== 32 ||
      operation.verificationDigest.length !== 32 ||
      operation.operationHash.length !== 32 ||
      selectedCount(operation.recipientMask) !== operation.recipientWallets.length
    ) {
      throw new SettlementChainError(
        "SETTLEMENT_AUTHORITY_INVALID",
        false,
        "Stored settlement authority is malformed.",
      );
    }

    const [configValue, refreshValue] = await Promise.all([
      this.accountInfo(this.configAddress, "confirmed"),
      this.accountInfo(refresh, "confirmed"),
    ]);
    if (
      ownerOf(configValue) !== this.programId.toBase58() ||
      ownerOf(refreshValue) !== this.programId.toBase58()
    ) {
      throw new SettlementChainError(
        "SETTLEMENT_ACCOUNT_OWNER_INVALID",
        false,
        "Settlement program account ownership does not match.",
      );
    }

    const [configData, refreshData] = [
      decodeAccountData(configValue),
      decodeAccountData(refreshValue),
    ];
    const [configDisc, refreshDisc] = await Promise.all([
      discriminator("account:ProtocolConfig"),
      discriminator("account:RefreshEscrow"),
    ]);

    if (
      configData.length !== 142 ||
      !bytesEqual(configData.slice(0, 8), configDisc) ||
      u16(configData, 8) !== 1 ||
      !bytesEqual(
        configData.slice(42, 74),
        this.verifier.publicKey.toBuffer(),
      ) ||
      !bytesEqual(configData.slice(74, 106), rewardMint.toBuffer()) ||
      !bytesEqual(configData.slice(106, 138), TOKEN_PROGRAM_ID.toBuffer()) ||
      configData[138] !== 0
    ) {
      throw new SettlementChainError(
        "SETTLEMENT_VERIFIER_NOT_PINNED",
        false,
        "Configured verifier authority does not match the runtime signer.",
      );
    }

    const vault = deriveAssociatedTokenAddress(refresh, rewardMint);
    if (
      refreshData.length !== 500 ||
      !bytesEqual(refreshData.slice(0, 8), refreshDisc) ||
      u16(refreshData, 8) !== 1 ||
      !bytesEqual(refreshData.slice(10, 42), operation.chainRefreshId) ||
      !bytesEqual(refreshData.slice(138, 170), rewardMint.toBuffer()) ||
      !bytesEqual(refreshData.slice(170, 202), vault.toBuffer()) ||
      !bytesEqual(
        refreshData.slice(202, 234),
        this.verifier.publicKey.toBuffer(),
      ) ||
      refreshData[254] !== 1 ||
      u64(refreshData, 263) !== operation.lockedRewardAtomic ||
      refreshData[271] !== 1 ||
      !refreshData.slice(467, 499).every((byte) => byte === 0)
    ) {
      throw new SettlementChainError(
        "SETTLEMENT_CHAIN_AUTHORITY_DRIFT",
        false,
        "On-chain settlement authority does not match the durable operation.",
      );
    }

    const recipients = operation.recipientWallets.map((wallet) => {
      try {
        return deriveAssociatedTokenAddress(new PublicKey(wallet), rewardMint);
      } catch {
        throw new SettlementChainError(
          "SETTLEMENT_RECIPIENT_INVALID",
          false,
          "Stored settlement recipient wallet is invalid.",
        );
      }
    });

    return { refresh, rewardMint, vault, recipients };
  }

  async submit(operation: SettlementOperation): Promise<SettlementSubmission> {
    const authority = await this.assertPinnedAuthority(operation);
    const latest = await this.rpc<{
      value: { blockhash: string; lastValidBlockHeight: number };
    }>("getLatestBlockhash", [{ commitment: "confirmed" }]);

    if (
      typeof latest.value?.blockhash !== "string" ||
      !Number.isSafeInteger(latest.value?.lastValidBlockHeight) ||
      latest.value.lastValidBlockHeight < 0
    ) {
      throw new SettlementChainError(
        "SOLANA_BLOCKHASH_INVALID",
        true,
        "Solana returned an invalid settlement blockhash.",
      );
    }

    const data = Buffer.concat([
      Buffer.from(await discriminator("global:settle_refresh")),
      Buffer.from(operation.chainRefreshId),
      Buffer.from(operation.operationHash),
      Buffer.from(operation.verificationDigest),
      Buffer.from([operation.recipientMask]),
    ]);
    const instruction = new TransactionInstruction({
      programId: this.programId,
      keys: [
        {
          pubkey: this.verifier.publicKey,
          isSigner: true,
          isWritable: false,
        },
        {
          pubkey: this.configAddress,
          isSigner: false,
          isWritable: false,
        },
        {
          pubkey: authority.refresh,
          isSigner: false,
          isWritable: true,
        },
        {
          pubkey: authority.vault,
          isSigner: false,
          isWritable: true,
        },
        {
          pubkey: authority.rewardMint,
          isSigner: false,
          isWritable: false,
        },
        {
          pubkey: TOKEN_PROGRAM_ID,
          isSigner: false,
          isWritable: false,
        },
        ...authority.recipients.map((pubkey) => ({
          pubkey,
          isSigner: false,
          isWritable: true,
        })),
      ],
      data,
    });

    const transaction = new Transaction({
      feePayer: this.verifier.publicKey,
      recentBlockhash: latest.value.blockhash,
    }).add(instruction);
    transaction.sign(this.verifier);
    if (transaction.signature === null) {
      throw new SettlementChainError(
        "SETTLEMENT_SIGNATURE_MISSING",
        false,
        "Settlement transaction could not be signed.",
      );
    }
    const signature = bs58.encode(transaction.signature);
    const raw = transaction.serialize({
      requireAllSignatures: true,
      verifySignatures: true,
    });

    try {
      const response = await this.rpcRaw<string>("sendTransaction", [
        Buffer.from(raw).toString("base64"),
        {
          encoding: "base64",
          skipPreflight: false,
          preflightCommitment: "confirmed",
          maxRetries: 0,
        },
      ]);
      if (
        response.error !== undefined ||
        typeof response.result !== "string" ||
        response.result !== signature
      ) {
        return {
          kind: "ambiguous",
          signature,
          recentBlockhash: latest.value.blockhash,
          lastValidBlockHeight: latest.value.lastValidBlockHeight,
          errorCode: "SETTLEMENT_SUBMISSION_AMBIGUOUS",
        };
      }
      return {
        kind: "submitted",
        signature,
        recentBlockhash: latest.value.blockhash,
        lastValidBlockHeight: latest.value.lastValidBlockHeight,
      };
    } catch (error) {
      if (error instanceof SettlementChainError && !error.retryable) throw error;
      return {
        kind: "ambiguous",
        signature,
        recentBlockhash: latest.value.blockhash,
        lastValidBlockHeight: latest.value.lastValidBlockHeight,
        errorCode: error instanceof SettlementChainError
          ? error.code
          : "SETTLEMENT_SUBMISSION_AMBIGUOUS",
      };
    }
  }

  async inspect(operation: SettlementOperation): Promise<SettlementInspection> {
    const observedAt = new Date();
    if (
      operation.chainSignature === null ||
      operation.lastValidBlockHeight === null
    ) {
      return {
        kind: "authority_conflict",
        errorCode: "SETTLEMENT_ATTEMPT_IDENTITY_MISSING",
        observedAt,
      };
    }

    let refresh: PublicKey;
    try {
      refresh = new PublicKey(operation.chainRefreshAddress);
    } catch {
      return {
        kind: "authority_conflict",
        errorCode: "SETTLEMENT_ADDRESS_INVALID",
        observedAt,
      };
    }

    const statusResult = await this.rpc<Record<string, unknown>>(
      "getSignatureStatuses",
      [[operation.chainSignature], { searchTransactionHistory: true }],
    );
    const statuses = statusResult.value;
    const signatureStatus = Array.isArray(statuses) ? statuses[0] : null;
    let commitment: "confirmed" | "finalized" | null = null;
    let signatureFailed = false;
    if (signatureStatus && typeof signatureStatus === "object") {
      const status = signatureStatus as Record<string, unknown>;
      signatureFailed = status.err != null;
      if (status.confirmationStatus === "finalized") commitment = "finalized";
      else if (status.confirmationStatus === "confirmed") commitment = "confirmed";
    }

    const accountValue = await this.accountInfo(
      refresh,
      commitment === "finalized" ? "finalized" : "confirmed",
    );
    if (ownerOf(accountValue) !== this.programId.toBase58()) {
      return {
        kind: "authority_conflict",
        errorCode: "SETTLEMENT_ACCOUNT_OWNER_INVALID",
        observedAt,
      };
    }
    const data = decodeAccountData(accountValue);
    const refreshDisc = await discriminator("account:RefreshEscrow");
    if (
      data.length !== 500 ||
      !bytesEqual(data.slice(0, 8), refreshDisc) ||
      u16(data, 8) !== 1 ||
      !bytesEqual(data.slice(10, 42), operation.chainRefreshId)
    ) {
      return {
        kind: "authority_conflict",
        errorCode: "SETTLEMENT_CHAIN_AUTHORITY_DRIFT",
        observedAt,
      };
    }

    const status = data[254];
    if (status === 2) {
      if (
        u64(data, 419) !== operation.lockedRewardAtomic ||
        !bytesEqual(data.slice(435, 467), operation.verificationDigest) ||
        !bytesEqual(data.slice(467, 499), operation.operationHash)
      ) {
        return {
          kind: "authority_conflict",
          errorCode: "SETTLEMENT_CHAIN_RESULT_MISMATCH",
          observedAt,
        };
      }
      return {
        kind: "confirmed",
        commitment: commitment === "finalized" ? "finalized" : "confirmed",
        signature: operation.chainSignature,
        observedAt,
      };
    }
    if (status !== 1) {
      return {
        kind: "authority_conflict",
        errorCode: "SETTLEMENT_CHAIN_STATE_INVALID",
        observedAt,
      };
    }

    if (signatureFailed) {
      return { kind: "not_settled", observedAt };
    }

    const blockHeight = await this.rpc<number>("getBlockHeight", [
      { commitment: "confirmed" },
    ]);
    if (
      Number.isSafeInteger(blockHeight) &&
      blockHeight > operation.lastValidBlockHeight
    ) {
      return { kind: "not_settled", observedAt };
    }
    return { kind: "pending" };
  }
}
