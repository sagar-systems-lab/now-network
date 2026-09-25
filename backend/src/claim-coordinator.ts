import bs58 from "npm:bs58@6.0.0";
import { ApiFault } from "./errors.ts";
import type {
  ActorRecord,
  IdentityRepository,
  WalletBindingRecord,
} from "./identity-repository.ts";
import { type ClaimChainAddresses, deriveClaimChainAddresses } from "./solana-refresh-addresses.ts";
import {
  type ChainClaimInspection,
  type ClaimChainObserver,
  NOW_SETTLEMENT_PROGRAM_ID,
} from "./solana-refresh-observer.ts";
import type { ClaimRepository, ClaimWithRefresh } from "./claim-repository.ts";
import { bytesToHex, sha256Bytes } from "../../packages/contracts/src/refresh-intent.ts";
import { transitionClaim } from "../../packages/domain/src/claim-machine.ts";
import { canonicalJson } from "../../packages/policy/src/template.ts";

const IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{8,128}$/;
const MAX_U32 = 4_294_967_295;
const encoder = new TextEncoder();

export type ClaimCoordinatorConfig = {
  cluster: string;
  claimDurationSeconds: number;
  programId?: string;
};

async function requestDigest(value: unknown): Promise<Uint8Array> {
  return await sha256Bytes(encoder.encode(canonicalJson(value)));
}

function requireIdempotencyKey(value: string): string {
  if (!IDEMPOTENCY_KEY.test(value)) {
    throw new ApiFault(
      400,
      "IDEMPOTENCY_KEY_REQUIRED",
      "A valid Idempotency-Key header is required.",
    );
  }
  return value;
}

function assertActorActive(actor: ActorRecord): void {
  if (actor.status === "DISABLED") {
    throw new ApiFault(403, "ACTOR_DISABLED", "This actor is disabled.");
  }
  if (actor.status === "RESTRICTED") {
    throw new ApiFault(403, "ACTOR_RESTRICTED", "This actor is restricted.");
  }
}

function assertSolanaKey(value: string, code: string, message: string): void {
  try {
    if (bs58.decode(value).length !== 32) throw new Error("wrong length");
  } catch {
    throw new ApiFault(409, code, message);
  }
}

function assertProgramId(value: string): void {
  try {
    if (bs58.decode(value).length !== 32) throw new Error("wrong length");
  } catch {
    throw new Error("invalid Solana claim coordinator configuration");
  }
}

function assertSignature(value: string): void {
  try {
    if (bs58.decode(value).length !== 64) throw new Error("wrong length");
  } catch {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid Solana signature.");
  }
}

function nextStep(claim: ClaimWithRefresh["claim"]): string | null {
  switch (claim.status) {
    case "WALLET_PENDING":
    case "SUBMITTED":
    case "CONFIRMING":
    case "UNKNOWN":
      return "SIGN_OR_OBSERVE_CLAIM";
    case "CLAIMED":
      return "EVIDENCE_CHALLENGE";
    default:
      return null;
  }
}

function privatePayload(record: ClaimWithRefresh): Record<string, unknown> {
  return {
    acceptance_id: record.claim.acceptanceId,
    refresh_id: record.claim.refreshId,
    status: record.claim.status,
    claim_slot: record.claim.claimSlot,
    claim_duration_seconds: record.claim.claimDurationSeconds,
    claim_deadline: record.claim.claimDeadline?.toISOString() ?? null,
    chain_signature: record.claim.chainSignature,
    chain_status: record.claim.chainStatus,
    refresh_status: record.refresh.status,
    refresh_expires_at: record.refresh.refreshExpiresAt.toISOString(),
    evidence_deadline: record.refresh.evidenceDeadline.toISOString(),
    revision: record.claim.revision,
    next_step: nextStep(record.claim),
  };
}

export class ClaimCoordinator {
  private readonly programId: string;

  constructor(
    private readonly claimRepository: ClaimRepository,
    private readonly identityRepository: IdentityRepository,
    private readonly chainObserver: ClaimChainObserver,
    private readonly config: ClaimCoordinatorConfig,
    private readonly now: () => Date = () => new Date(),
  ) {
    this.programId = config.programId ?? NOW_SETTLEMENT_PROGRAM_ID;
    if (
      !config.cluster ||
      !Number.isSafeInteger(config.claimDurationSeconds) ||
      config.claimDurationSeconds <= 0 ||
      config.claimDurationSeconds > MAX_U32
    ) {
      throw new Error("invalid claim coordinator configuration");
    }
    assertProgramId(this.programId);
  }

  async prepare(input: {
    actor: ActorRecord;
    refreshId: string;
    walletBindingId: string;
    idempotencyKey: string;
  }): Promise<{ data: Record<string, unknown>; status: number }> {
    assertActorActive(input.actor);
    const idempotencyKey = requireIdempotencyKey(input.idempotencyKey);
    const requestHash = await requestDigest({
      refresh_id: input.refreshId,
      wallet_binding_id: input.walletBindingId,
      operation: "CLAIM_PREPARE_V1",
    });

    const replay = await this.claimRepository.lookupPrepareReplay({
      actorId: input.actor.actorId,
      idempotencyKey,
      requestHash,
    });
    if (replay.kind === "idempotency_conflict") {
      throw new ApiFault(
        409,
        "IDEMPOTENCY_CONFLICT",
        "The idempotency key was already used for a different request.",
      );
    }
    if (replay.kind === "replayed") {
      return {
        data: await this.intentPayload(replay),
        status: 200,
      };
    }

    const binding = await this.activeBinding(
      input.actor.actorId,
      input.walletBindingId,
    );
    transitionClaim("EMPTY", "PREPARE");
    transitionClaim("PREPARING", "WALLET_REQUESTED");

    const observedAt = this.now();
    const result = await this.claimRepository.prepareClaim({
      refreshId: input.refreshId,
      actorId: input.actor.actorId,
      walletAddress: binding.walletAddress,
      claimDurationSeconds: this.config.claimDurationSeconds,
      acceptanceId: crypto.randomUUID(),
      idempotencyKey,
      requestHash,
      idempotencyExpiresAt: new Date(observedAt.getTime() + 24 * 60 * 60 * 1000),
      observedAt,
    });

    switch (result.kind) {
      case "idempotency_conflict":
        throw new ApiFault(
          409,
          "IDEMPOTENCY_CONFLICT",
          "The idempotency key was already used for a different request.",
        );
      case "not_found":
        throw new ApiFault(404, "OPPORTUNITY_NOT_FOUND", "Opportunity was not found.");
      case "self_claim":
        throw new ApiFault(409, "CLAIM_NOT_AVAILABLE", "This opportunity cannot be claimed.");
      case "expired":
        throw new ApiFault(410, "CLAIM_EXPIRED", "The claim window has expired.");
      case "capacity_full":
        throw new ApiFault(409, "CLAIM_CAPACITY_FULL", "No claim slots are available.");
      case "not_claimable":
        throw new ApiFault(409, "CLAIM_NOT_AVAILABLE", "This opportunity cannot be claimed.");
      case "replayed":
        return {
          data: await this.intentPayload(result),
          status: 200,
        };
      case "prepared":
        return {
          data: await this.intentPayload(result),
          status: result.created ? 201 : 200,
        };
    }
  }

  async get(actor: ActorRecord, acceptanceId: string): Promise<Record<string, unknown>> {
    assertActorActive(actor);
    const record = await this.claimRepository.getClaim(acceptanceId);
    if (record === null || record.claim.actorId !== actor.actorId) {
      throw new ApiFault(404, "CLAIM_NOT_FOUND", "Claim was not found.");
    }
    return privatePayload(record);
  }

  async observe(input: {
    actor: ActorRecord;
    acceptanceId: string;
    signature: string;
  }): Promise<Record<string, unknown>> {
    assertActorActive(input.actor);
    assertSignature(input.signature);

    let record = await this.claimRepository.getClaim(input.acceptanceId);
    if (record === null || record.claim.actorId !== input.actor.actorId) {
      throw new ApiFault(404, "CLAIM_NOT_FOUND", "Claim was not found.");
    }
    if (record.claim.status === "CLAIMED") return privatePayload(record);
    if (
      record.claim.claimDurationSeconds === null ||
      record.refresh.chainRefreshAddress === null ||
      !["WALLET_PENDING", "SUBMITTED", "CONFIRMING", "UNKNOWN"].includes(record.claim.status)
    ) {
      throw new ApiFault(409, "CLAIM_NOT_AVAILABLE", "This claim cannot be observed.");
    }
    if (
      record.claim.chainSignature !== null &&
      record.claim.chainSignature !== input.signature
    ) {
      throw new ApiFault(409, "CLAIM_REJECTED", "A different transaction is already bound.");
    }

    const refreshAddress = record.refresh.chainRefreshAddress;
    const claimDurationSeconds = record.claim.claimDurationSeconds;

    const addresses = await deriveClaimChainAddresses({
      programId: this.programId,
      rewardMint: record.refresh.rewardMint,
      claimantWallet: record.claim.walletAddress,
      chainRefreshId: record.refresh.chainRefreshId,
    });

    if (record.claim.status === "WALLET_PENDING") {
      transitionClaim("WALLET_PENDING", "TRANSACTION_SUBMITTED", {
        transactionSubmitted: true,
      });
      transitionClaim("SUBMITTED", "CONFIRMATION_STARTED");
      record = this.unwrapObservation(
        await this.claimRepository.markClaimPending({
          acceptanceId: input.acceptanceId,
          actorId: input.actor.actorId,
          chainSignature: input.signature,
          chainStatus: "CONFIRMING",
          observedAt: this.now(),
        }),
      );
    } else if (record.claim.status === "SUBMITTED") {
      transitionClaim("SUBMITTED", "CONFIRMATION_STARTED");
      record = this.unwrapObservation(
        await this.claimRepository.markClaimPending({
          acceptanceId: input.acceptanceId,
          actorId: input.actor.actorId,
          chainSignature: input.signature,
          chainStatus: "CONFIRMING",
          observedAt: this.now(),
        }),
      );
    } else if (record.claim.status === "CONFIRMING") {
      record = this.unwrapObservation(
        await this.claimRepository.markClaimPending({
          acceptanceId: input.acceptanceId,
          actorId: input.actor.actorId,
          chainSignature: input.signature,
          chainStatus: "CONFIRMING",
          observedAt: this.now(),
        }),
      );
    }

    let observation: ChainClaimInspection;
    try {
      observation = await this.chainObserver.inspectClaim({
        signature: input.signature,
        refreshAddress,
        configAddress: addresses.configAddress,
        claimantWallet: record.claim.walletAddress,
        claimantRewardTokenAccount: addresses.claimantRewardTokenAccount,
        expectedRewardMint: record.refresh.rewardMint,
        expectedCreatorWallet: record.refresh.creatorWalletAddress,
        expectedChainRefreshId: record.refresh.chainRefreshId,
        expectedStateIdDigest: record.refresh.stateIdDigest,
        expectedIntentCoreHash: record.refresh.intentCoreHash,
        expectedRefreshExpiresAt: record.refresh.refreshExpiresAt,
        expectedMaxWitnesses: record.refresh.maxWitnesses,
        claimDurationSeconds,
      });
    } catch {
      if (record.claim.status !== "UNKNOWN") {
        transitionClaim("CONFIRMING", "OUTCOME_UNKNOWN");
        this.unwrapObservation(
          await this.claimRepository.markClaimPending({
            acceptanceId: input.acceptanceId,
            actorId: input.actor.actorId,
            chainSignature: input.signature,
            chainStatus: "UNKNOWN",
            observedAt: this.now(),
          }),
        );
      }
      throw new ApiFault(
        409,
        "CLAIM_UNKNOWN",
        "Claim outcome could not be confirmed yet.",
        true,
        1_000,
      );
    }

    if (observation.kind === "pending") {
      throw new ApiFault(
        409,
        "CLAIM_UNKNOWN",
        "Claim is not yet confirmed.",
        true,
        1_000,
      );
    }

    if (observation.kind === "failed") {
      if (record.claim.status !== "UNKNOWN") {
        transitionClaim("CONFIRMING", "OUTCOME_UNKNOWN");
      }
      transitionClaim("UNKNOWN", "RECONCILED_ABSENT", {
        claimDefinitivelyAbsent: true,
      });
      this.unwrapObservation(
        await this.claimRepository.resetClaimAbsent({
          acceptanceId: input.acceptanceId,
          actorId: input.actor.actorId,
          chainSignature: input.signature,
          observedAt: this.now(),
        }),
      );
      throw new ApiFault(409, "CLAIM_REJECTED", "Claim transaction was not accepted.");
    }

    if (record.claim.status === "UNKNOWN") {
      transitionClaim("UNKNOWN", "RECONCILED_CLAIMED", {
        chainClaimConfirmed: true,
        refreshExpired: false,
      });
    } else {
      transitionClaim("CONFIRMING", "CLAIM_CONFIRMED", {
        chainClaimConfirmed: true,
        refreshExpired: false,
      });
    }

    const executionHash = await deriveExecutionHashV1({
      intentCoreHash: record.refresh.intentCoreHash,
      lockedRewardAtomic: observation.lockedRewardAtomic,
      refreshAddress,
    });

    const confirmed = this.unwrapObservation(
      await this.claimRepository.confirmClaim({
        acceptanceId: input.acceptanceId,
        actorId: input.actor.actorId,
        walletAddress: record.claim.walletAddress,
        chainSignature: input.signature,
        chainCommitment: observation.commitment,
        claimSlot: observation.claimSlot,
        claimedAt: observation.claimedAt,
        claimDeadline: observation.claimDeadline,
        totalFundedAtomic: observation.totalFundedAtomic,
        lockedRewardAtomic: observation.lockedRewardAtomic,
        executionHash,
        observedAt: observation.observedAt,
      }),
    );

    return privatePayload(confirmed);
  }

  private async activeBinding(
    actorId: string,
    walletBindingId: string,
  ): Promise<WalletBindingRecord> {
    const bindings = await this.identityRepository.listWalletBindings(actorId);
    const binding = bindings.find((candidate) =>
      candidate.walletBindingId === walletBindingId &&
      candidate.status === "ACTIVE" &&
      candidate.cluster === this.config.cluster
    );
    if (!binding) {
      throw new ApiFault(
        409,
        "WALLET_BINDING_REQUIRED",
        "An active wallet binding for the configured cluster is required.",
      );
    }
    assertSolanaKey(binding.walletAddress, "WALLET_BINDING_REQUIRED", "Wallet binding is invalid.");
    return binding;
  }

  private async intentPayload(record: ClaimWithRefresh): Promise<Record<string, unknown>> {
    if (
      record.claim.claimDurationSeconds === null ||
      record.refresh.chainRefreshAddress === null
    ) {
      throw new Error("prepared claim is missing chain intent state");
    }
    const addresses = await deriveClaimChainAddresses({
      programId: this.programId,
      rewardMint: record.refresh.rewardMint,
      claimantWallet: record.claim.walletAddress,
      chainRefreshId: record.refresh.chainRefreshId,
    });
    if (addresses.refreshAddress !== record.refresh.chainRefreshAddress) {
      throw new Error("stored refresh address drift");
    }

    return {
      ...privatePayload(record),
      cluster: this.config.cluster,
      program_id: this.programId,
      wallet_address: record.claim.walletAddress,
      reward_mint: record.refresh.rewardMint,
      chain_refresh_id_hex: bytesToHex(record.refresh.chainRefreshId),
      accounts: this.claimAccounts(
        addresses,
        record.claim.walletAddress,
        record.refresh.rewardMint,
      ),
      instruction: {
        name: "claim_witness",
        refresh_id_hex: bytesToHex(record.refresh.chainRefreshId),
        claim_duration_seconds: record.claim.claimDurationSeconds,
      },
    };
  }

  private claimAccounts(
    addresses: ClaimChainAddresses,
    claimantWallet: string,
    rewardMint: string,
  ): Record<string, string> {
    return {
      claimant: claimantWallet,
      config: addresses.configAddress,
      refresh: addresses.refreshAddress,
      reward_mint: rewardMint,
      claimant_reward_token_account: addresses.claimantRewardTokenAccount,
    };
  }

  private unwrapObservation(
    result: Awaited<ReturnType<ClaimRepository["markClaimPending"]>>,
  ): ClaimWithRefresh {
    switch (result.kind) {
      case "updated":
      case "replayed":
        return result;
      case "not_found":
      case "actor_mismatch":
        throw new ApiFault(404, "CLAIM_NOT_FOUND", "Claim was not found.");
      case "signature_conflict":
        throw new ApiFault(409, "CLAIM_REJECTED", "A different transaction is already bound.");
      case "authority_conflict":
        throw new ApiFault(409, "CLAIM_NOT_AVAILABLE", "Claim state does not allow this action.");
    }
  }
}
