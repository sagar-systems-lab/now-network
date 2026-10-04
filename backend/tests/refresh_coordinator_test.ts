import bs58 from "npm:bs58@6.0.0";
import { ApiFault } from "../src/errors.ts";
import type {
  ActorRecord,
  ChallengeConsumptionResult,
  IdentityRepository,
  WalletBindingChallengeRecord,
  WalletBindingRecord,
} from "../src/identity-repository.ts";
import { RefreshCoordinator } from "../src/refresh-coordinator.ts";
import type {
  ConfirmFundingInput,
  ConfirmFundingResult,
  CreateRefreshResult,
  NewRefreshRecord,
  PrepareFundingInput,
  PrepareFundingResult,
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
const VALID_SIGNATURE = bs58.encode(new Uint8Array(64).fill(7));

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
    policyTemplateKey: "parking.available_spaces.v1",
    title: "Parking Lot B",
    question: "Available spaces",
    stateType: "NUMERIC",
    unitCode: "spaces",
    answerSchema: { type: "integer", minimum: 0 },
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
      centerEwkb: new Uint8Array([1, 2, 3, 4]),
      boundaryEwkb: null,
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
    fundingOperationId: null,
    chainRefreshAddress: null,
    chainStatus: null,
    chainTotalFunded: 0n,
    chainObservedAt: null,
    createdAt: new Date(now),
    updatedAt: new Date(now),
    revision: 1,
  };
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length &&
    left.every((byte, index) => byte === right[index]);
}

class MemoryRefreshRepository implements RefreshRepository {
  readonly records = new Map<string, RefreshRecord>();
  readonly idempotency = new Map<
    string,
    { hash: Uint8Array; refreshId: string; operationId: string }
  >();
  readonly signatures = new Map<string, string>();
  now = new Date("2026-09-24T10:00:00.000Z");

  lookupCreateReplay(
    input: Parameters<RefreshRepository["lookupCreateReplay"]>[0],
  ) {
    const key = `create:${input.actorId}:${input.idempotencyKey}`;
    const existing = this.idempotency.get(key);
    if (!existing) return Promise.resolve({ kind: "none" } as const);
    if (!sameBytes(existing.hash, input.requestHash)) {
      return Promise.resolve({ kind: "idempotency_conflict" } as const);
    }
    return Promise.resolve(
      {
        kind: "replayed",
        refresh: structuredClone(this.records.get(existing.refreshId)!),
      } as const,
    );
  }

  createOrReplay(
    input: Parameters<RefreshRepository["createOrReplay"]>[0],
  ): Promise<CreateRefreshResult> {
    const key = `create:${input.refresh.requesterActorId}:${input.idempotencyKey}`;
    const existing = this.idempotency.get(key);
    if (existing) {
      if (!sameBytes(existing.hash, input.requestHash)) {
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
      operationId: record.refreshId,
    });
    return Promise.resolve({ kind: "created", refresh: structuredClone(record) });
  }

  getRefresh(refreshId: string): Promise<RefreshRecord | null> {
    const record = this.records.get(refreshId);
    return Promise.resolve(record ? structuredClone(record) : null);
  }

  prepareFunding(input: PrepareFundingInput): Promise<PrepareFundingResult> {
    const current = this.records.get(input.refreshId);
    if (!current) return Promise.resolve({ kind: "not_found" });
    if (current.requesterActorId !== input.actorId) {
      return Promise.resolve({ kind: "actor_mismatch" });
    }

    const key = `intent:${input.actorId}:${input.idempotencyKey}`;
    const existing = this.idempotency.get(key);
    if (existing) {
      if (!sameBytes(existing.hash, input.requestHash)) {
        return Promise.resolve({ kind: "idempotency_conflict" });
      }
      return Promise.resolve({
        kind: "replayed",
        refresh: structuredClone(current),
        operationId: existing.operationId,
      });
    }

    if (current.refreshExpiresAt.getTime() <= input.observedAt.getTime()) {
      return Promise.resolve({ kind: "expired" });
    }
    if (current.status === "AWAITING_FUNDING" && current.fundingOperationId) {
      this.idempotency.set(key, {
        hash: new Uint8Array(input.requestHash),
        refreshId: current.refreshId,
        operationId: current.fundingOperationId,
      });
      return Promise.resolve({
        kind: "replayed",
        refresh: structuredClone(current),
        operationId: current.fundingOperationId,
      });
    }
    if (current.status !== "DRAFT") {
      return Promise.resolve({ kind: "not_fundable" });
    }

    const updated: RefreshRecord = {
      ...current,
      status: "AWAITING_FUNDING",
      fundingOperationId: input.operationId,
      chainRefreshAddress: input.addresses.refreshAddress,
      chainStatus: "INTENT_READY",
      updatedAt: new Date(input.observedAt),
      revision: current.revision + 1,
    };
    this.records.set(updated.refreshId, updated);
    this.idempotency.set(key, {
      hash: new Uint8Array(input.requestHash),
      refreshId: updated.refreshId,
      operationId: input.operationId,
    });
    return Promise.resolve({
      kind: "prepared",
      refresh: structuredClone(updated),
      operationId: input.operationId,
    });
  }

  confirmFunding(input: ConfirmFundingInput): Promise<ConfirmFundingResult> {
    const current = this.records.get(input.refreshId);
    if (!current) return Promise.resolve({ kind: "not_found" });
    if (current.requesterActorId !== input.actorId) {
      return Promise.resolve({ kind: "actor_mismatch" });
    }

    const key = `observe:${input.actorId}:${input.idempotencyKey}`;
    const existing = this.idempotency.get(key);
    if (existing) {
      if (!sameBytes(existing.hash, input.requestHash)) {
        return Promise.resolve({ kind: "idempotency_conflict" });
      }
      return Promise.resolve({ kind: "replayed", refresh: structuredClone(current) });
    }

    if (current.status === "AVAILABLE") {
      return Promise.resolve({ kind: "replayed", refresh: structuredClone(current) });
    }
    if (current.refreshExpiresAt.getTime() <= input.observedAt.getTime()) {
      return Promise.resolve({ kind: "expired" });
    }
    if (
      current.status !== "AWAITING_FUNDING" ||
      current.fundingOperationId === null ||
      current.chainRefreshAddress !== input.chainRefreshAddress ||
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
      chainStatus: input.chainCommitment,
      chainTotalFunded: input.chainTotalFundedAtomic,
      chainObservedAt: new Date(input.observedAt),
      updatedAt: new Date(input.observedAt),
      revision: current.revision + 2,
    };
    this.records.set(updated.refreshId, updated);
    this.signatures.set(input.chainSignature, input.refreshId);
    this.idempotency.set(key, {
      hash: new Uint8Array(input.requestHash),
      refreshId: updated.refreshId,
      operationId: current.fundingOperationId,
    });
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

async function createDraft(
  service: RefreshCoordinator,
  key: string,
): Promise<Record<string, unknown>> {
  return (await service.create({
    actor: actor(),
    stateId: STATE_ID,
    walletBindingId: BINDING_ID,
    fundingTargetAtomic: "1000000",
    idempotencyKey: key,
  })).data;
}

Deno.test("refresh creation freezes policy and remains DRAFT before wallet funding", async () => {
  const { service } = coordinator();
  const result = await service.create({
    actor: actor(),
    stateId: STATE_ID,
    walletBindingId: BINDING_ID,
    fundingTargetAtomic: "1000000",
    idempotencyKey: "request-0001",
  });

  if (result.status !== 201) throw new Error("new refresh must return 201");
  if (result.data.status !== "DRAFT" || result.data.next_step !== "FUNDING_INTENT") {
    throw new Error("refresh must remain DRAFT until funding intent is created");
  }
  if (result.data.verification_class !== "FAST") throw new Error("policy class drift");
  const policy = result.data.proof_policy as Record<string, unknown>;
  if (policy.template_key !== "parking.available_spaces.v1") {
    throw new Error("canonical policy snapshot was not frozen");
  }
  if (typeof result.data.intent_core_hash !== "string") {
    throw new Error("intent hash missing");
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

Deno.test("refresh create replay survives later wallet revocation", async () => {
  const setup = coordinator();
  const request = {
    actor: actor(),
    stateId: STATE_ID,
    walletBindingId: BINDING_ID,
    fundingTargetAtomic: "1000000",
    idempotencyKey: "request-replay-stable",
  };

  const first = await setup.service.create(request);
  setup.identity.bindings.splice(0, setup.identity.bindings.length);
  const replay = await setup.service.create(request);

  if (
    replay.status !== 200 ||
    replay.data.refresh_id !== first.data.refresh_id ||
    replay.data.status !== "DRAFT"
  ) {
    throw new Error("idempotent replay depended on mutable wallet state");
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

Deno.test("funding intent is stable and advances DRAFT to AWAITING_FUNDING once", async () => {
  const { service } = coordinator();
  const draft = await createDraft(service, "request-0004");

  const first = await service.fundingIntent({
    actor: actor(),
    refreshId: draft.refresh_id as string,
    idempotencyKey: "funding-0004",
  });
  const replay = await service.fundingIntent({
    actor: actor(),
    refreshId: draft.refresh_id as string,
    idempotencyKey: "funding-0004",
  });

  if (first.status !== "AWAITING_FUNDING" || first.operation_id !== replay.operation_id) {
    throw new Error("funding intent must be stable");
  }
  const accounts = first.accounts as Record<string, unknown>;
  for (
    const field of [
      "config",
      "refresh",
      "contribution",
      "source_token_account",
      "vault_token_account",
    ]
  ) {
    if (typeof accounts[field] !== "string") throw new Error(`missing ${field}`);
  }
  const plan = first.instruction_plan as unknown[];
  if (plan.join(",") !== "create_refresh,contribute") {
    throw new Error("funding instruction plan drift");
  }
});

Deno.test("funding observation remains unknown until Solana confirms", async () => {
  const setup = coordinator();
  const draft = await createDraft(setup.service, "request-0005");
  await setup.service.fundingIntent({
    actor: actor(),
    refreshId: draft.refresh_id as string,
    idempotencyKey: "funding-0005",
  });

  try {
    await setup.service.observeFunding({
      actor: actor(),
      refreshId: draft.refresh_id as string,
      signature: VALID_SIGNATURE,
      idempotencyKey: "observe-0005",
    });
    throw new Error("pending funding was accepted");
  } catch (error) {
    if (faultCode(error) !== "FUNDING_UNKNOWN") throw error;
  }
});

Deno.test("confirmed chain funding publishes refresh at revision four", async () => {
  const setup = coordinator();
  const draft = await createDraft(setup.service, "request-0006");
  await setup.service.fundingIntent({
    actor: actor(),
    refreshId: draft.refresh_id as string,
    idempotencyKey: "funding-0006",
  });
  setup.chain.result = {
    kind: "confirmed",
    commitment: "confirmed",
    contributionAmountAtomic: 1_000_000n,
    totalFundedAtomic: 1_000_000n,
    observedAt: new Date("2026-09-24T10:01:00.000Z"),
  };

  const result = await setup.service.observeFunding({
    actor: actor(),
    refreshId: draft.refresh_id as string,
    signature: VALID_SIGNATURE,
    idempotencyKey: "observe-0006",
  });

  if (result.status !== "AVAILABLE" || result.revision !== 4) {
    throw new Error("funded refresh must publish at revision four");
  }
  if (result.chain_total_funded_atomic !== "1000000") {
    throw new Error("chain funding total was not persisted");
  }
  if (setup.chain.lastInput?.expectedCreatorWallet !== WALLET) {
    throw new Error("chain observer did not receive the bound creator wallet");
  }
});

Deno.test("funding observe rejects malformed signature before RPC", async () => {
  const setup = coordinator();
  const draft = await createDraft(setup.service, "request-0007");
  await setup.service.fundingIntent({
    actor: actor(),
    refreshId: draft.refresh_id as string,
    idempotencyKey: "funding-0007",
  });

  try {
    await setup.service.observeFunding({
      actor: actor(),
      refreshId: draft.refresh_id as string,
      signature: "not-a-signature",
      idempotencyKey: "observe-0007",
    });
    throw new Error("malformed signature was accepted");
  } catch (error) {
    if (faultCode(error) !== "INVALID_REQUEST") throw error;
  }
  if (setup.chain.lastInput !== null) throw new Error("RPC was called for malformed signature");
});

Deno.test("refresh ownership is hidden from other actors", async () => {
  const setup = coordinator();
  const draft = await createDraft(setup.service, "request-0008");

  try {
    await setup.service.get(actor(ACTOR_B), draft.refresh_id as string);
    throw new Error("other actor read private refresh coordination state");
  } catch (error) {
    if (faultCode(error) !== "REFRESH_NOT_FOUND") throw error;
  }
});

Deno.test("dynamic place keys use the registered template when freezing funding policy", async () => {
  const { service, state } = coordinator();
  state.detail.canonicalKey = `parking.available_spaces.v1:${state.detail.location.locationId}`;
  const result = await createDraft(service, "dynamic-place-0001");
  if (
    (result.proof_policy as Record<string, unknown>).template_key !== "parking.available_spaces.v1"
  ) {
    throw new Error("place identity was used as a policy key");
  }
});
