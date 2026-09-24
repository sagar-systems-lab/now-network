import { ApiFault } from "../src/errors.ts";
import type {
  ActorRecord,
  ChallengeConsumptionResult,
  IdentityRepository,
  WalletBindingChallengeRecord,
  WalletBindingRecord,
} from "../src/identity-repository.ts";
import {
  RefreshCoordinator,
} from "../src/refresh-coordinator.ts";
import type {
  ConfirmFundingInput,
  ConfirmFundingResult,
  CreateRefreshResult,
  NewRefreshRecord,
  RefreshRecord,
  RefreshRepository,
} from "../src/refresh-repository.ts";
import type {
  ChainFundingInspection,
  RefreshChainObserver,
} from "../src/solana-refresh-observer.ts";
import type {
  NearbyStateRecord,
  StateDetailRecord,
  StateHistoryRecord,
  StateRepository,
} from "../src/state-repository.ts";

const ACTOR_A = "50000000-0000-4000-8000-000000000001";
const ACTOR_B = "50000000-0000-4000-8000-000000000002";
const STATE_ID = "51000000-0000-4000-8000-000000000001";
const BINDING_ID = "52000000-0000-4000-8000-000000000001";
const WALLET = "11111111111111111111111111111111";
const REWARD_MINT = "So11111111111111111111111111111111111111112";

function actor(actorId = ACTOR_A, status: ActorRecord["status"] = "ACTIVE"): ActorRecord {
  return { actorId, status, revision: 1 };
}

class MemoryIdentityRepository implements IdentityRepository {
  readonly bindings: WalletBindingRecord[] = [{
    walletBindingId: BINDING_ID,
    actorId: ACTOR_A,
    walletAddress: WALLET,
    cluster: "devnet",
    status: "ACTIVE",
    revision: 1,
  }];

  resolveActor(): Promise<ActorRecord> {
    return Promise.reject(new Error("not used"));
  }

  listWalletBindings(actorId: string): Promise<WalletBindingRecord[]> {
    return Promise.resolve(
      this.bindings
        .filter((binding) => binding.actorId === actorId)
        .map((binding) => structuredClone(binding)),
    );
  }

  issueWalletBindingChallenge(_challenge: WalletBindingChallengeRecord): Promise<void> {
    return Promise.reject(new Error("not used"));
  }

  getWalletBindingChallenge(): Promise<WalletBindingChallengeRecord | null> {
    return Promise.reject(new Error("not used"));
  }

  consumeWalletBindingChallenge(): Promise<ChallengeConsumptionResult> {
    return Promise.reject(new Error("not used"));
  }
}

class MemoryStateRepository implements StateRepository {
  readonly detail: StateDetailRecord = {
    stateId: STATE_ID,
    version: 1,
    canonicalKey: "parking.available_spaces.v1",
    title: "Parking Lot B",
    question: "Available spaces",
    stateType: "NUMERIC",
    unitCode: "spaces",
    currentValue: { scaled_value: "2", scale: 0, unit: "spaces" },
    observedAt: new Date("2026-09-24T10:00:00.000Z"),
    observationEarliest: null,
    observationLatest: null,
    agingAt: new Date("2026-09-24T10:07:00.000Z"),
    freshUntil: new Date("2026-09-24T10:10:00.000Z"),
    verificationClass: "FAST",
    conflictActive: false,
    revision: 1,
    location: {
      locationId: "53000000-0000-4000-8000-000000000001",
      name: "Parking Lot B",
      locationType: "PARKING",
      displayAddress: "Demo district",
    },
    verification: null,
    activeRefresh: null,
  };

  listNearby(): Promise<NearbyStateRecord[]> {
    return Promise.resolve([]);
  }

  getState(stateId: string): Promise<StateDetailRecord | null> {
    return Promise.resolve(stateId === STATE_ID ? structuredClone(this.detail) : null);
  }

  listHistory(): Promise<StateHistoryRecord[]> {
    return Promise.resolve([]);
  }
}

function fullRecord(refresh: NewRefreshRecord, now: Date): RefreshRecord {
  return {
    ...structuredClone(refresh),
    chainRefreshAddress: null,
    chainStatus: "PENDING_CREATE",
    chainTotalFunded: 0n,
    chainObservedAt: null,
    createdAt: new Date(now),
    updatedAt: new Date(now),
    revision: 1,
  };
}

class MemoryRefreshRepository implements RefreshRepository {
  readonly records = new Map<string, RefreshRecord>();
  readonly idempotency = new Map<string, { hash: Uint8Array; refreshId: string }>();
  readonly signatures = new Map<string, string>();
  now = new Date("2026-09-24T10:00:00.000Z");

  createOrReplay(
    input: Parameters<RefreshRepository["createOrReplay"]>[0],
  ): Promise<CreateRefreshResult> {
    const key = `${input.refresh.requesterActorId}:${input.idempotencyKey}`;
    const existing = this.idempotency.get(key);
    if (existing) {
      if (
        existing.hash.length !== input.requestHash.length ||
        existing.hash.some((byte, index) => byte !== input.requestHash[index])
      ) {
        return Promise.resolve({ kind: "idempotency_conflict" });
      }
      return Promise.resolve({
        kind: "replayed",
        refresh: structuredClone(this.records.get(existing.refreshId)!),
      });
    }

    const record = fullRecord(input.refresh, this.now);
    this.records.set(record.refreshId, record);
    this.idempotency.set(key, {
      hash: new Uint8Array(input.requestHash),
      refreshId: record.refreshId,
    });
    return Promise.resolve({ kind: "created", refresh: structuredClone(record) });
  }

  getRefresh(refreshId: string): Promise<RefreshRecord | null> {
    const record = this.records.get(refreshId);
    return Promise.resolve(record ? structuredClone(record) : null);
  }

  confirmFunding(input: ConfirmFundingInput): Promise<ConfirmFundingResult> {
    const current = this.records.get(input.refreshId);
    if (!current) return Promise.resolve({ kind: "not_found" });
    if (current.requesterActorId !== input.actorId) {
      return Promise.resolve({ kind: "actor_mismatch" });
    }
    if (current.status === "AVAILABLE") {
      return Promise.resolve({ kind: "replayed", refresh: structuredClone(current) });
    }
    if (current.refreshExpiresAt.getTime() <= input.observedAt.getTime()) {
      return Promise.resolve({ kind: "expired" });
    }
    if (
      current.status !== "AWAITING_FUNDING" ||
      input.chainTotalFundedAtomic < current.fundingTargetAtomic
    ) {
      return Promise.resolve({ kind: "not_fundable" });
    }

    const signatureRefresh = this.signatures.get(input.chainSignature);
    if (signatureRefresh && signatureRefresh !== input.refreshId) {
      return Promise.resolve({ kind: "not_fundable" });
    }

    const updated: RefreshRecord = {
      ...current,
      status: "AVAILABLE",
      chainRefreshAddress: input.chainRefreshAddress,
      chainStatus: input.chainCommitment,
      chainTotalFunded: input.chainTotalFundedAtomic,
      chainObservedAt: new Date(input.observedAt),
      updatedAt: new Date(input.observedAt),
      revision: current.revision + 2,
    };
    this.records.set(updated.refreshId, updated);
    this.signatures.set(input.chainSignature, input.refreshId);
    return Promise.resolve({ kind: "confirmed", refresh: structuredClone(updated) });
  }
}

class MemoryChainObserver implements RefreshChainObserver {
  result: ChainFundingInspection = { kind: "pending" };
  lastInput: Parameters<RefreshChainObserver["inspectFunding"]>[0] | null = null;

  inspectFunding(
    input: Parameters<RefreshChainObserver["inspectFunding"]>[0],
  ): Promise<ChainFundingInspection> {
    this.lastInput = structuredClone(input);
    return Promise.resolve(structuredClone(this.result));
  }
}

function coordinator(options?: {
  identity?: MemoryIdentityRepository;
  state?: MemoryStateRepository;
  refresh?: MemoryRefreshRepository;
  chain?: MemoryChainObserver;
  now?: Date;
}): {
  service: RefreshCoordinator;
  identity: MemoryIdentityRepository;
  state: MemoryStateRepository;
  refresh: MemoryRefreshRepository;
  chain: MemoryChainObserver;
} {
  const identity = options?.identity ?? new MemoryIdentityRepository();
  const state = options?.state ?? new MemoryStateRepository();
  const refresh = options?.refresh ?? new MemoryRefreshRepository();
  const chain = options?.chain ?? new MemoryChainObserver();
  const now = options?.now ?? new Date("2026-09-24T10:00:00.000Z");

  return {
    service: new RefreshCoordinator(
      refresh,
      state,
      identity,
      chain,
      {
        cluster: "devnet",
        rewardMint: REWARD_MINT,
        refreshLifetimeSeconds: 900,
        evidenceLeadSeconds: 120,
      },
      () => new Date(now),
    ),
    identity,
    state,
    refresh,
    chain,
  };
}

function faultCode(error: unknown): string {
  return error instanceof ApiFault ? error.code : "";
}

Deno.test("refresh creation freezes server policy and canonical chain intent", async () => {
  const { service } = coordinator();
  const result = await service.create({
    actor: actor(),
    stateId: STATE_ID,
    walletBindingId: BINDING_ID,
    fundingTargetAtomic: "1000000",
    idempotencyKey: "request-0001",
  });

  if (result.status !== 201) throw new Error("new refresh must return 201");
  const data = result.data;
  if (data.status !== "AWAITING_FUNDING") throw new Error("refresh must await funding");
  if (data.verification_class !== "FAST") throw new Error("policy class drift");
  if (data.required_witnesses !== 1 || data.max_witnesses !== 1) {
    throw new Error("FAST witness terms must match the settlement program");
  }
  if (data.payout_rule !== "SINGLE_WINNER_ALL") {
    throw new Error("FAST payout rule mismatch");
  }
  const policy = data.proof_policy as Record<string, unknown>;
  if (policy.template_key !== "parking.available_spaces.v1") {
    throw new Error("canonical policy snapshot was not frozen");
  }
  const intent = data.chain_intent as Record<string, unknown>;
  if (
    intent.program_id !== "7nqsPpBhpUwSahMrpuAPNMupx2vVEGqkU6XXcng7VaAm" ||
    intent.creator_wallet !== WALLET ||
    intent.funding_target_atomic !== "1000000"
  ) {
    throw new Error("chain intent is incomplete");
  }
});

Deno.test("refresh creation is idempotent and rejects key reuse with different input", async () => {
  const { service } = coordinator();
  const request = {
    actor: actor(),
    stateId: STATE_ID,
    walletBindingId: BINDING_ID,
    fundingTargetAtomic: "1000000",
    idempotencyKey: "request-0002",
  };

  const first = await service.create(request);
  const second = await service.create(request);
  if (second.status !== 200 || first.data.refresh_id !== second.data.refresh_id) {
    throw new Error("idempotent replay must return the original refresh");
  }

  try {
    await service.create({ ...request, fundingTargetAtomic: "1000001" });
    throw new Error("expected idempotency conflict");
  } catch (error) {
    if (faultCode(error) !== "IDEMPOTENCY_CONFLICT") throw error;
  }
});

Deno.test("refresh creation requires an active wallet on the configured cluster", async () => {
  const identity = new MemoryIdentityRepository();
  identity.bindings[0] = { ...identity.bindings[0], cluster: "mainnet-beta" };
  const { service } = coordinator({ identity });

  try {
    await service.create({
      actor: actor(),
      stateId: STATE_ID,
      walletBindingId: BINDING_ID,
      fundingTargetAtomic: "1000000",
      idempotencyKey: "request-0003",
    });
    throw new Error("expected wallet binding failure");
  } catch (error) {
    if (faultCode(error) !== "WALLET_BINDING_REQUIRED") throw error;
  }
});

Deno.test("disabled and restricted actors cannot create refresh authority", async () => {
  for (const status of ["DISABLED", "RESTRICTED"] as const) {
    const { service } = coordinator();
    try {
      await service.create({
        actor: actor(ACTOR_A, status),
        stateId: STATE_ID,
        walletBindingId: BINDING_ID,
        fundingTargetAtomic: "1000000",
        idempotencyKey: `blocked-${status}`,
      });
      throw new Error("blocked actor created a refresh");
    } catch (error) {
      const expected = status === "DISABLED" ? "ACTOR_DISABLED" : "ACTOR_RESTRICTED";
      if (faultCode(error) !== expected) throw error;
    }
  }
});

Deno.test("funding confirmation remains unknown until chain proof is confirmed", async () => {
  const setup = coordinator();
  const created = await setup.service.create({
    actor: actor(),
    stateId: STATE_ID,
    walletBindingId: BINDING_ID,
    fundingTargetAtomic: "1000000",
    idempotencyKey: "request-0004",
  });

  try {
    await setup.service.confirmFunding({
      actor: actor(),
      refreshId: created.data.refresh_id as string,
      signature: "signature-a",
      refreshAddress: "refresh-address-a",
      contributionAddress: "contribution-address-a",
    });
    throw new Error("pending funding was accepted");
  } catch (error) {
    if (faultCode(error) !== "FUNDING_UNKNOWN") throw error;
  }
});

Deno.test("confirmed chain funding publishes refresh and advances two revisions", async () => {
  const setup = coordinator();
  const created = await setup.service.create({
    actor: actor(),
    stateId: STATE_ID,
    walletBindingId: BINDING_ID,
    fundingTargetAtomic: "1000000",
    idempotencyKey: "request-0005",
  });
  setup.chain.result = {
    kind: "confirmed",
    commitment: "confirmed",
    contributionAmountAtomic: 1_000_000n,
    totalFundedAtomic: 1_000_000n,
    observedAt: new Date("2026-09-24T10:01:00.000Z"),
  };

  const result = await setup.service.confirmFunding({
    actor: actor(),
    refreshId: created.data.refresh_id as string,
    signature: "signature-b",
    refreshAddress: "refresh-address-b",
    contributionAddress: "contribution-address-b",
  });

  if (result.status !== "AVAILABLE" || result.revision !== 3) {
    throw new Error("confirmed funding must publish the refresh at revision 3");
  }
  if (result.chain_total_funded_atomic !== "1000000") {
    throw new Error("chain funding total was not persisted");
  }
  if (setup.chain.lastInput?.expectedCreatorWallet !== WALLET) {
    throw new Error("chain observer did not receive the bound creator wallet");
  }
});

Deno.test("refresh ownership is hidden from other actors", async () => {
  const setup = coordinator();
  const created = await setup.service.create({
    actor: actor(),
    stateId: STATE_ID,
    walletBindingId: BINDING_ID,
    fundingTargetAtomic: "1000000",
    idempotencyKey: "request-0006",
  });

  try {
    await setup.service.get(actor(ACTOR_B), created.data.refresh_id as string);
    throw new Error("other actor read private refresh coordination state");
  } catch (error) {
    if (faultCode(error) !== "REFRESH_NOT_FOUND") throw error;
  }
});
