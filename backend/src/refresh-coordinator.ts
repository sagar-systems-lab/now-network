import bs58 from "npm:bs58@6.0.0";
import type { ActorRecord, IdentityRepository } from "./identity-repository.ts";
import { ApiFault } from "./errors.ts";
import type { StateRepository } from "./state-repository.ts";
import {
  type NewRefreshRecord,
  type RefreshRecord,
  type RefreshRepository,
} from "./refresh-repository.ts";
import {
  ASSOCIATED_TOKEN_PROGRAM_ID,
  deriveRefreshChainAddresses,
  SYSTEM_PROGRAM_ID,
  TOKEN_PROGRAM_ID,
} from "./solana-refresh-addresses.ts";
import {
  NOW_SETTLEMENT_PROGRAM_ID,
  type RefreshChainObserver,
} from "./solana-refresh-observer.ts";
import {
  bytesToHex,
  deriveAnswerSchemaDigestV1,
  deriveChainRefreshIdV1,
  deriveLocationScopeDigestV1,
  deriveRefreshIntentCoreHashV1,
  deriveStateIdDigestV1,
  type RefreshPayoutRule,
  sha256Bytes,
} from "../../packages/contracts/src/refresh-intent.ts";
import { canonicalJson, policyDigestHex } from "../../packages/policy/src/template.ts";
import { policyTemplateForKey } from "../../packages/policy/src/registry.ts";
import type { PolicyTemplateV1 } from "../../packages/policy/src/types.ts";
import { transitionRefresh } from "../../packages/domain/src/refresh-machine.ts";

const MAX_U64 = 18_446_744_073_709_551_615n;
const IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{8,128}$/;
const encoder = new TextEncoder();

export type RefreshCoordinatorConfig = {
  cluster: string;
  rewardMint: string;
  refreshLifetimeSeconds: number;
  evidenceLeadSeconds: number;
  programId?: string;
};

function fromHex(value: string): Uint8Array {
  if (!/^[0-9a-f]+$/u.test(value) || value.length % 2 !== 0) {
    throw new TypeError("invalid hex");
  }
  const matches = value.match(/../gu) ?? [];
  return Uint8Array.from(matches, (part) => Number.parseInt(part, 16));
}

async function requestDigest(value: unknown): Promise<Uint8Array> {
  return await sha256Bytes(encoder.encode(canonicalJson(value)));
}

function atomicAmount(value: string): bigint {
  if (!/^[1-9][0-9]{0,19}$/u.test(value)) {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid funding_target_atomic.");
  }
  const amount = BigInt(value);
  if (amount > MAX_U64) {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid funding_target_atomic.");
  }
  return amount;
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

function witnessTerms(policy: PolicyTemplateV1): {
  requiredWitnesses: number;
  maxWitnesses: number;
  payoutRule: RefreshPayoutRule;
} {
  if (policy.verification_class === "FAST") {
    if (policy.required_witnesses !== 1) {
      throw new Error("FAST policy must require exactly one witness");
    }
    return {
      requiredWitnesses: 1,
      maxWitnesses: 1,
      payoutRule: "SINGLE_WINNER_ALL",
    };
  }

  if (policy.verification_class === "CORROBORATED") {
    if (policy.required_witnesses !== 2) {
      throw new Error("CORROBORATED policy must require two witnesses");
    }
    return {
      requiredWitnesses: 2,
      maxWitnesses: policy.numeric?.allow_two_of_three === true ? 3 : 2,
      payoutRule: "EQUAL_SPLIT_REQUIRED_WITNESSES",
    };
  }

  if (policy.required_witnesses < 2 || policy.required_witnesses > 3) {
    throw new Error("STRICT policy witness count is incompatible with the settlement program");
  }
  return {
    requiredWitnesses: policy.required_witnesses,
    maxWitnesses: policy.required_witnesses,
    payoutRule: "EQUAL_SPLIT_REQUIRED_WITNESSES",
  };
}

function assertActorActive(actor: ActorRecord): void {
  if (actor.status === "DISABLED") {
    throw new ApiFault(403, "ACTOR_DISABLED", "This actor is disabled.");
  }
  if (actor.status === "RESTRICTED") {
    throw new ApiFault(403, "ACTOR_RESTRICTED", "This actor is restricted.");
  }
}

function assertSignature(value: string): void {
  try {
    if (bs58.decode(value).length !== 64) throw new Error("wrong length");
  } catch {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid Solana signature.");
  }
}

function privatePayload(record: RefreshRecord): Record<string, unknown> {
  return {
    refresh_id: record.refreshId,
    state_id: record.stateId,
    state_version: record.stateVersion,
    status: record.status,
    verification_class: record.verificationClass,
    required_witnesses: record.requiredWitnesses,
    max_witnesses: record.maxWitnesses,
    payout_rule: record.payoutRule,
    proof_policy: record.proofPolicySnapshot,
    proof_policy_digest: bytesToHex(record.proofPolicyDigest),
    intent_core_hash: bytesToHex(record.intentCoreHash),
    refresh_expires_at: record.refreshExpiresAt.toISOString(),
    evidence_deadline: record.evidenceDeadline.toISOString(),
    reward_mint: record.rewardMint,
    funding_target_atomic: record.fundingTargetAtomic.toString(),
    funding_operation_id: record.fundingOperationId,
    chain_total_funded_atomic: record.chainTotalFunded.toString(),
    chain_refresh_address: record.chainRefreshAddress,
    chain_status: record.chainStatus,
    chain_observed_at: record.chainObservedAt?.toISOString() ?? null,
    revision: record.revision,
  };
}

export class RefreshCoordinator {
  private readonly programId: string;
  private readonly rewardMintBytes: Uint8Array;

  constructor(
    private readonly refreshRepository: RefreshRepository,
    private readonly stateRepository: StateRepository,
    private readonly identityRepository: IdentityRepository,
    private readonly chainObserver: RefreshChainObserver,
    private readonly config: RefreshCoordinatorConfig,
    private readonly now: () => Date = () => new Date(),
  ) {
    this.programId = config.programId ?? NOW_SETTLEMENT_PROGRAM_ID;
    if (
      !config.cluster ||
      !config.rewardMint ||
      !Number.isSafeInteger(config.refreshLifetimeSeconds) ||
      !Number.isSafeInteger(config.evidenceLeadSeconds) ||
      config.refreshLifetimeSeconds <= 0 ||
      config.evidenceLeadSeconds <= 0 ||
      config.evidenceLeadSeconds >= config.refreshLifetimeSeconds
    ) {
      throw new Error("invalid refresh coordinator configuration");
    }

    try {
      this.rewardMintBytes = Uint8Array.from(bs58.decode(config.rewardMint));
      if (this.rewardMintBytes.length !== 32) throw new Error("wrong mint length");
      deriveRefreshChainAddresses({
        programId: this.programId,
        rewardMint: config.rewardMint,
        creatorWallet: "11111111111111111111111111111111",
        chainRefreshId: new Uint8Array(32),
      });
    } catch {
      throw new Error("invalid Solana refresh coordinator configuration");
    }
  }

  async create(input: {
    actor: ActorRecord;
    stateId: string;
    walletBindingId: string;
    fundingTargetAtomic: string;
    idempotencyKey: string;
  }): Promise<{ data: Record<string, unknown>; status: number }> {
    assertActorActive(input.actor);
    const idempotencyKey = requireIdempotencyKey(input.idempotencyKey);

    const state = await this.stateRepository.getState(input.stateId);
    if (state === null) {
      throw new ApiFault(404, "STATE_NOT_FOUND", "State was not found.");
    }

    const policy = policyTemplateForKey(state.canonicalKey);
    if (policy === null || policy.state_type !== state.stateType) {
      throw new ApiFault(
        409,
        "REFRESH_POLICY_UNAVAILABLE",
        "This state does not have an active refresh policy.",
      );
    }

    const bindings = await this.identityRepository.listWalletBindings(input.actor.actorId);
    const binding = bindings.find((candidate) =>
      candidate.walletBindingId === input.walletBindingId &&
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

    try {
      if (bs58.decode(binding.walletAddress).length !== 32) throw new Error("wrong length");
    } catch {
      throw new ApiFault(409, "WALLET_BINDING_REQUIRED", "Wallet binding is invalid.");
    }

    const fundingTargetAtomic = atomicAmount(input.fundingTargetAtomic);
    const terms = witnessTerms(policy);
    const refreshId = crypto.randomUUID();
    const current = this.now();
    const refreshExpiresAt = new Date(
      current.getTime() + this.config.refreshLifetimeSeconds * 1000,
    );
    const evidenceDeadline = new Date(
      refreshExpiresAt.getTime() - this.config.evidenceLeadSeconds * 1000,
    );

    const [
      chainRefreshId,
      stateIdDigest,
      locationScopeDigest,
      answerSchemaDigest,
    ] = await Promise.all([
      deriveChainRefreshIdV1(refreshId),
      deriveStateIdDigestV1(state.stateId),
      deriveLocationScopeDigestV1({
        locationId: state.location.locationId,
        centerEwkb: state.location.centerEwkb,
        boundaryEwkb: state.location.boundaryEwkb,
      }),
      deriveAnswerSchemaDigestV1(state.answerSchema),
    ]);

    const proofPolicyDigest = fromHex(await policyDigestHex(policy));
    const intentCoreHash = await deriveRefreshIntentCoreHashV1({
      stateIdDigest,
      stateDefinitionVersion: state.version,
      locationScopeDigest,
      stateType: state.stateType,
      answerSchemaDigest,
      freshnessTtlSeconds: policy.fresh_ttl_seconds,
      proofPolicyDigest,
      verificationClass: policy.verification_class,
      requiredWitnesses: terms.requiredWitnesses,
      maxWitnesses: terms.maxWitnesses,
      payoutRule: terms.payoutRule,
      refreshExpiresAtUnix: BigInt(Math.floor(refreshExpiresAt.getTime() / 1000)),
      rewardMint: this.rewardMintBytes,
    });

    const requestHash = await requestDigest({
      state_id: input.stateId,
      wallet_binding_id: input.walletBindingId,
      funding_target_atomic: fundingTargetAtomic.toString(),
    });

    const record: NewRefreshRecord = {
      refreshId,
      stateId: state.stateId,
      stateVersion: state.version,
      requesterActorId: input.actor.actorId,
      status: "DRAFT",
      verificationClass: policy.verification_class,
      requiredWitnesses: terms.requiredWitnesses,
      maxWitnesses: terms.maxWitnesses,
      proofPolicySnapshot: policy,
      proofPolicyDigest,
      intentCoreHash,
      refreshExpiresAt,
      evidenceDeadline,
      rewardMint: this.config.rewardMint,
      creatorWalletAddress: binding.walletAddress,
      fundingTargetAtomic,
      payoutRule: terms.payoutRule,
      chainRefreshId,
      stateIdDigest,
    };

    const result = await this.refreshRepository.createOrReplay({
      idempotencyKey,
      requestHash,
      idempotencyExpiresAt: new Date(current.getTime() + 24 * 60 * 60 * 1000),
      refresh: record,
    });
    if (result.kind === "idempotency_conflict") {
      throw new ApiFault(
        409,
        "IDEMPOTENCY_CONFLICT",
        "The idempotency key was already used for a different request.",
      );
    }

    return {
      data: {
        ...privatePayload(result.refresh),
        next_step: "FUNDING_INTENT",
      },
      status: result.kind === "created" ? 201 : 200,
    };
  }

  async get(actor: ActorRecord, refreshId: string): Promise<Record<string, unknown>> {
    assertActorActive(actor);
    const record = await this.ownedRefresh(actor.actorId, refreshId);
    return privatePayload(record);
  }

  async fundingIntent(input: {
    actor: ActorRecord;
    refreshId: string;
    idempotencyKey: string;
  }): Promise<Record<string, unknown>> {
    assertActorActive(input.actor);
    const idempotencyKey = requireIdempotencyKey(input.idempotencyKey);
    const record = await this.ownedRefresh(input.actor.actorId, input.refreshId);

    if (record.refreshExpiresAt.getTime() <= this.now().getTime()) {
      throw new ApiFault(410, "REFRESH_EXPIRED", "Refresh has expired.");
    }
    if (record.status !== "DRAFT" && record.status !== "AWAITING_FUNDING") {
      throw new ApiFault(409, "REFRESH_NOT_FUNDABLE", "Refresh cannot accept funding.");
    }
    if (record.status === "DRAFT") {
      transitionRefresh("DRAFT", "FUND_START");
    }

    const addresses = deriveRefreshChainAddresses({
      programId: this.programId,
      rewardMint: record.rewardMint,
      creatorWallet: record.creatorWalletAddress,
      chainRefreshId: record.chainRefreshId,
    });
    const requestHash = await requestDigest({
      refresh_id: record.refreshId,
      operation: "FUNDING_INTENT_V1",
    });
    const operationId = crypto.randomUUID();
    const result = await this.refreshRepository.prepareFunding({
      refreshId: record.refreshId,
      actorId: input.actor.actorId,
      idempotencyKey,
      requestHash,
      idempotencyExpiresAt: new Date(this.now().getTime() + 24 * 60 * 60 * 1000),
      operationId,
      addresses,
    });

    switch (result.kind) {
      case "idempotency_conflict":
        throw new ApiFault(
          409,
          "IDEMPOTENCY_CONFLICT",
          "The idempotency key was already used for a different request.",
        );
      case "not_found":
      case "actor_mismatch":
        throw new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
      case "expired":
        throw new ApiFault(410, "REFRESH_EXPIRED", "Refresh has expired.");
      case "not_fundable":
        throw new ApiFault(409, "REFRESH_NOT_FUNDABLE", "Refresh cannot accept funding.");
      case "prepared":
      case "replayed": {
        const prepared = result.refresh;
        return {
          operation_id: result.operationId,
          refresh_id: prepared.refreshId,
          status: prepared.status,
          cluster: this.config.cluster,
          program_id: this.programId,
          reward_mint: prepared.rewardMint,
          amount_atomic: prepared.fundingTargetAtomic.toString(),
          intent_core_hash: bytesToHex(prepared.intentCoreHash),
          chain_refresh_id_hex: bytesToHex(prepared.chainRefreshId),
          state_id_digest_hex: bytesToHex(prepared.stateIdDigest),
          refresh_expires_at: Math.floor(prepared.refreshExpiresAt.getTime() / 1000),
          verification_class: prepared.verificationClass,
          required_witnesses: prepared.requiredWitnesses,
          max_witnesses: prepared.maxWitnesses,
          payout_rule: prepared.payoutRule,
          creator_wallet: prepared.creatorWalletAddress,
          accounts: {
            config: addresses.configAddress,
            refresh: addresses.refreshAddress,
            contribution: addresses.contributionAddress,
            vault_token_account: addresses.vaultTokenAccount,
            token_program: TOKEN_PROGRAM_ID,
            associated_token_program: ASSOCIATED_TOKEN_PROGRAM_ID,
            system_program: SYSTEM_PROGRAM_ID,
          },
          instruction_plan: ["create_refresh", "contribute"],
        };
      }
    }
  }

  async observeFunding(input: {
    actor: ActorRecord;
    refreshId: string;
    signature: string;
    idempotencyKey: string;
  }): Promise<Record<string, unknown>> {
    assertActorActive(input.actor);
    const idempotencyKey = requireIdempotencyKey(input.idempotencyKey);
    assertSignature(input.signature);

    const record = await this.ownedRefresh(input.actor.actorId, input.refreshId);
    if (record.status === "AVAILABLE") return privatePayload(record);
    if (record.refreshExpiresAt.getTime() <= this.now().getTime()) {
      throw new ApiFault(410, "REFRESH_EXPIRED", "Refresh has expired.");
    }
    if (
      record.status !== "AWAITING_FUNDING" ||
      record.fundingOperationId === null ||
      record.chainRefreshAddress === null
    ) {
      throw new ApiFault(409, "REFRESH_NOT_FUNDABLE", "Funding intent is not active.");
    }

    transitionRefresh("AWAITING_FUNDING", "FUNDING_CONFIRMED", {
      fundingConfirmed: true,
    });
    transitionRefresh("FUNDED", "PUBLISH");

    const addresses = deriveRefreshChainAddresses({
      programId: this.programId,
      rewardMint: record.rewardMint,
      creatorWallet: record.creatorWalletAddress,
      chainRefreshId: record.chainRefreshId,
    });
    if (addresses.refreshAddress !== record.chainRefreshAddress) {
      throw new Error("stored refresh address drift");
    }

    let observation;
    try {
      observation = await this.chainObserver.inspectFunding({
        signature: input.signature,
        refreshAddress: addresses.refreshAddress,
        contributionAddress: addresses.contributionAddress,
        expectedCreatorWallet: record.creatorWalletAddress,
        expectedRewardMint: record.rewardMint,
        expectedChainRefreshId: record.chainRefreshId,
        expectedStateIdDigest: record.stateIdDigest,
        expectedIntentCoreHash: record.intentCoreHash,
        expectedRefreshExpiresAt: record.refreshExpiresAt,
        expectedVerificationClass: record.verificationClass,
        expectedRequiredWitnesses: record.requiredWitnesses,
        expectedMaxWitnesses: record.maxWitnesses,
        expectedPayoutRule: record.payoutRule,
        fundingTargetAtomic: record.fundingTargetAtomic,
      });
    } catch {
      throw new ApiFault(
        409,
        "FUNDING_UNKNOWN",
        "Funding could not be confirmed yet.",
        true,
        1_000,
      );
    }

    if (observation.kind === "pending") {
      throw new ApiFault(
        409,
        "FUNDING_UNKNOWN",
        "Funding is not yet confirmed.",
        true,
        1_000,
      );
    }
    if (observation.kind === "failed") {
      throw new ApiFault(
        409,
        "REFRESH_NOT_FUNDABLE",
        "Funding does not match this refresh.",
      );
    }

    const requestHash = await requestDigest({
      refresh_id: record.refreshId,
      operation_id: record.fundingOperationId,
      signature: input.signature,
    });
    const result = await this.refreshRepository.confirmFunding({
      refreshId: record.refreshId,
      actorId: input.actor.actorId,
      idempotencyKey,
      requestHash,
      idempotencyExpiresAt: new Date(this.now().getTime() + 24 * 60 * 60 * 1000),
      chainRefreshAddress: addresses.refreshAddress,
      chainContributionAddress: addresses.contributionAddress,
      chainSignature: input.signature,
      chainCommitment: observation.commitment,
      contributionAmountAtomic: observation.contributionAmountAtomic,
      chainTotalFundedAtomic: observation.totalFundedAtomic,
      observedAt: observation.observedAt,
    });

    switch (result.kind) {
      case "confirmed":
      case "replayed":
        return privatePayload(result.refresh);
      case "idempotency_conflict":
        throw new ApiFault(
          409,
          "IDEMPOTENCY_CONFLICT",
          "The idempotency key was already used for a different request.",
        );
      case "not_found":
      case "actor_mismatch":
        throw new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
      case "expired":
        throw new ApiFault(410, "REFRESH_EXPIRED", "Refresh has expired.");
      case "not_fundable":
        throw new ApiFault(409, "REFRESH_NOT_FUNDABLE", "Refresh cannot be funded.");
    }
  }

  private async ownedRefresh(actorId: string, refreshId: string): Promise<RefreshRecord> {
    const record = await this.refreshRepository.getRefresh(refreshId);
    if (record === null || record.requesterActorId !== actorId) {
      throw new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
    }
    return record;
  }
}
