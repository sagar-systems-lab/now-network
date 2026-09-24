import type { ActorRecord, IdentityRepository } from "./identity-repository.ts";
import { ApiFault } from "./errors.ts";
import type { StateRepository } from "./state-repository.ts";
import {
  type NewRefreshRecord,
  type RefreshPayoutRule,
  type RefreshRecord,
  type RefreshRepository,
} from "./refresh-repository.ts";
import {
  NOW_SETTLEMENT_PROGRAM_ID,
  type RefreshChainObserver,
} from "./solana-refresh-observer.ts";
import {
  canonicalJson,
  policyDigestHex,
} from "../../packages/policy/src/template.ts";
import { policyTemplateForKey } from "../../packages/policy/src/registry.ts";
import type { PolicyTemplateV1 } from "../../packages/policy/src/types.ts";
import { transitionRefresh } from "../../packages/domain/src/refresh-machine.ts";

const MAX_U64 = 18_446_744_073_709_551_615n;
const IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{8,128}$/;

export type RefreshCoordinatorConfig = {
  cluster: string;
  rewardMint: string;
  refreshLifetimeSeconds: number;
  evidenceLeadSeconds: number;
  programId?: string;
};

function hex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function fromHex(value: string): Uint8Array {
  if (!/^[0-9a-f]+$/u.test(value) || value.length % 2 !== 0) {
    throw new TypeError("invalid hex");
  }
  return Uint8Array.from(value.match(/../gu) ?? [], (part) => Number.parseInt(part, 16));
}

async function sha256(value: string): Promise<Uint8Array> {
  return new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
  );
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

function chainIntent(
  record: RefreshRecord,
  cluster: string,
  programId: string,
): Record<string, unknown> {
  return {
    protocol_version: 1,
    cluster,
    program_id: programId,
    refresh_id_hex: hex(record.chainRefreshId),
    state_id_digest_hex: hex(record.stateIdDigest),
    intent_core_hash_hex: hex(record.intentCoreHash),
    refresh_expires_at: Math.floor(record.refreshExpiresAt.getTime() / 1000),
    verification_class: record.verificationClass,
    required_witnesses: record.requiredWitnesses,
    max_witnesses: record.maxWitnesses,
    payout_rule: record.payoutRule,
    reward_mint: record.rewardMint,
    creator_wallet: record.creatorWalletAddress,
    funding_target_atomic: record.fundingTargetAtomic.toString(),
  };
}

function payload(
  record: RefreshRecord,
  cluster: string,
  programId: string,
): Record<string, unknown> {
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
    proof_policy_digest: hex(record.proofPolicyDigest),
    refresh_expires_at: record.refreshExpiresAt.toISOString(),
    evidence_deadline: record.evidenceDeadline.toISOString(),
    funding_target_atomic: record.fundingTargetAtomic.toString(),
    chain_total_funded_atomic: record.chainTotalFunded.toString(),
    chain_refresh_address: record.chainRefreshAddress,
    chain_status: record.chainStatus,
    chain_observed_at: record.chainObservedAt?.toISOString() ?? null,
    revision: record.revision,
    chain_intent: chainIntent(record, cluster, programId),
  };
}

export class RefreshCoordinator {
  private readonly programId: string;

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
  }

  async create(input: {
    actor: ActorRecord;
    stateId: string;
    walletBindingId: string;
    fundingTargetAtomic: string;
    idempotencyKey: string;
  }): Promise<{ data: Record<string, unknown>; status: number }> {
    assertActorActive(input.actor);
    if (!IDEMPOTENCY_KEY.test(input.idempotencyKey)) {
      throw new ApiFault(
        400,
        "IDEMPOTENCY_KEY_REQUIRED",
        "A valid Idempotency-Key header is required.",
      );
    }

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

    const chainRefreshId = await sha256(`NOW Network/refresh/v1\n${refreshId}`);
    const stateIdDigest = await sha256(
      `NOW Network/state/v1\n${state.stateId}\n${state.version}`,
    );
    const proofPolicyDigest = fromHex(await policyDigestHex(policy));
    const intentCore = {
      schema_version: 1,
      refresh_id: refreshId,
      state_id: state.stateId,
      state_version: state.version,
      policy_digest_hex: hex(proofPolicyDigest),
      creator_wallet: binding.walletAddress,
      cluster: this.config.cluster,
      reward_mint: this.config.rewardMint,
      funding_target_atomic: fundingTargetAtomic.toString(),
      refresh_expires_at: Math.floor(refreshExpiresAt.getTime() / 1000),
      verification_class: policy.verification_class,
      required_witnesses: terms.requiredWitnesses,
      max_witnesses: terms.maxWitnesses,
      payout_rule: terms.payoutRule,
    };
    const intentCoreHash = await sha256(canonicalJson(intentCore));
    const requestHash = await sha256(canonicalJson({
      state_id: input.stateId,
      wallet_binding_id: input.walletBindingId,
      funding_target_atomic: fundingTargetAtomic.toString(),
    }));

    const record: NewRefreshRecord = {
      refreshId,
      stateId: state.stateId,
      stateVersion: state.version,
      requesterActorId: input.actor.actorId,
      status: "AWAITING_FUNDING",
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
      idempotencyKey: input.idempotencyKey,
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
      data: payload(result.refresh, this.config.cluster, this.programId),
      status: result.kind === "created" ? 201 : 200,
    };
  }

  async get(actor: ActorRecord, refreshId: string): Promise<Record<string, unknown>> {
    assertActorActive(actor);
    const record = await this.refreshRepository.getRefresh(refreshId);
    if (record === null) {
      throw new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
    }
    if (record.requesterActorId !== actor.actorId) {
      throw new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
    }
    return payload(record, this.config.cluster, this.programId);
  }

  async confirmFunding(input: {
    actor: ActorRecord;
    refreshId: string;
    signature: string;
    refreshAddress: string;
    contributionAddress: string;
  }): Promise<Record<string, unknown>> {
    assertActorActive(input.actor);
    const record = await this.refreshRepository.getRefresh(input.refreshId);
    if (record === null || record.requesterActorId !== input.actor.actorId) {
      throw new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
    }
    if (record.status === "AVAILABLE") {
      return payload(record, this.config.cluster, this.programId);
    }
    if (record.refreshExpiresAt.getTime() <= this.now().getTime()) {
      throw new ApiFault(410, "REFRESH_EXPIRED", "Refresh has expired.");
    }

    transitionRefresh(record.status, "FUNDING_CONFIRMED", { fundingConfirmed: true });
    transitionRefresh("FUNDED", "PUBLISH");

    const observation = await this.chainObserver.inspectFunding({
      signature: input.signature,
      refreshAddress: input.refreshAddress,
      contributionAddress: input.contributionAddress,
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

    const result = await this.refreshRepository.confirmFunding({
      refreshId: record.refreshId,
      actorId: input.actor.actorId,
      chainRefreshAddress: input.refreshAddress,
      chainContributionAddress: input.contributionAddress,
      chainSignature: input.signature,
      chainCommitment: observation.commitment,
      contributionAmountAtomic: observation.contributionAmountAtomic,
      chainTotalFundedAtomic: observation.totalFundedAtomic,
      observedAt: observation.observedAt,
      operationId: crypto.randomUUID(),
    });

    switch (result.kind) {
      case "confirmed":
      case "replayed":
        return payload(result.refresh, this.config.cluster, this.programId);
      case "not_found":
      case "actor_mismatch":
        throw new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
      case "expired":
        throw new ApiFault(410, "REFRESH_EXPIRED", "Refresh has expired.");
      case "not_fundable":
        throw new ApiFault(409, "REFRESH_NOT_FUNDABLE", "Refresh cannot be funded.");
    }
  }
}
