import bs58 from "npm:bs58@6.0.0";
import { ClaimCoordinator } from "../src/claim-coordinator.ts";
import { ApiFault } from "../src/errors.ts";
import type { ActorRecord, IdentityRepository } from "../src/identity-repository.ts";
import type {
  ClaimObservationResult,
  ClaimRepository,
  ClaimWithRefresh,
  PrepareClaimResult,
} from "../src/claim-repository.ts";
import { deriveClaimChainAddresses } from "../src/solana-refresh-addresses.ts";
import {
  type ChainClaimInspection,
  type ClaimChainObserver,
  NOW_SETTLEMENT_PROGRAM_ID,
} from "../src/solana-refresh-observer.ts";

const ACTOR_ID = "90000000-0000-4000-8000-000000000001";
const REFRESH_ID = "91000000-0000-4000-8000-000000000001";
const ACCEPTANCE_ID = "92000000-0000-4000-8000-000000000001";
const BINDING_ID = "93000000-0000-4000-8000-000000000001";
const CLAIMANT = bs58.encode(new Uint8Array(32).fill(7));
const CREATOR = bs58.encode(new Uint8Array(32).fill(8));
const MINT = "So11111111111111111111111111111111111111112";
const SIGNATURE = bs58.encode(new Uint8Array(64).fill(9));
const CHAIN_ID = new Uint8Array(32).fill(0x11);
const STATE_DIGEST = new Uint8Array(32).fill(0x22);
const INTENT_HASH = new Uint8Array(32).fill(0x33);
const NOW = new Date("2026-09-25T12:00:00.000Z");
const DEADLINE = new Date("2026-09-25T12:03:00.000Z");

function actor(status: ActorRecord["status"] = "ACTIVE"): ActorRecord {
  return { actorId: ACTOR_ID, status, revision: 1 };
}

async function baseRecord(): Promise<ClaimWithRefresh> {
  const addresses = await deriveClaimChainAddresses({
    programId: NOW_SETTLEMENT_PROGRAM_ID,
    rewardMint: MINT,
    claimantWallet: CLAIMANT,
    chainRefreshId: CHAIN_ID,
  });
  return {
    claim: {
      acceptanceId: ACCEPTANCE_ID,
      refreshId: REFRESH_ID,
      actorId: ACTOR_ID,
      walletAddress: CLAIMANT,
      claimSlot: null,
      claimDurationSeconds: 180,
      claimDeadline: null,
      chainSignature: null,
      chainStatus: "INTENT_READY",
      status: "WALLET_PENDING",
      acceptedAt: NOW,
      releasedAt: null,
      revision: 1,
    },
    refresh: {
      refreshId: REFRESH_ID,
      requesterActorId: "90000000-0000-4000-8000-000000000002",
      status: "AVAILABLE",
      requiredWitnesses: 1,
      maxWitnesses: 1,
      refreshExpiresAt: new Date("2026-09-25T12:15:00.000Z"),
      evidenceDeadline: new Date("2026-09-25T12:13:00.000Z"),
      rewardMint: MINT,
      creatorWalletAddress: CREATOR,
      chainRefreshId: CHAIN_ID,
      stateIdDigest: STATE_DIGEST,
      intentCoreHash: INTENT_HASH,
      chainRefreshAddress: addresses.refreshAddress,
      chainTotalFunded: 1_000_000n,
      chainLockedReward: null,
      revision: 4,
    },
  };
}

class MemoryClaimRepository implements ClaimRepository {
  record: ClaimWithRefresh | null = null;
  replay = false;
  prepareKind: PrepareClaimResult["kind"] = "prepared";

  async lookupPrepareReplay(): Promise<
    Awaited<ReturnType<ClaimRepository["lookupPrepareReplay"]>>
  > {
    if (this.replay && this.record) return { kind: "replayed", ...structuredClone(this.record) };
    return { kind: "none" };
  }

  async prepareClaim(): Promise<PrepareClaimResult> {
    if (this.prepareKind === "self_claim") return { kind: "self_claim" };
    if (this.prepareKind === "capacity_full") return { kind: "capacity_full" };
    if (this.prepareKind === "expired") return { kind: "expired" };
    if (this.prepareKind === "not_claimable") return { kind: "not_claimable" };
    if (!this.record) this.record = await baseRecord();
    return { kind: "prepared", created: true, ...structuredClone(this.record) };
  }

  getClaim(): Promise<ClaimWithRefresh | null> {
    return Promise.resolve(this.record ? structuredClone(this.record) : null);
  }

  markClaimPending(
    input: Parameters<ClaimRepository["markClaimPending"]>[0],
  ): Promise<ClaimObservationResult> {
    if (!this.record) return Promise.resolve({ kind: "not_found" });
    this.record.claim.status = input.chainStatus;
    this.record.claim.chainSignature = input.chainSignature;
    this.record.claim.chainStatus = input.chainStatus === "UNKNOWN" ? "UNKNOWN" : "PENDING";
    this.record.claim.revision += 1;
    return Promise.resolve({ kind: "updated", ...structuredClone(this.record) });
  }

  resetClaimAbsent(): Promise<ClaimObservationResult> {
    if (!this.record) return Promise.resolve({ kind: "not_found" });
    this.record.claim.status = "EMPTY";
    this.record.claim.claimDurationSeconds = null;
    this.record.claim.chainSignature = null;
    this.record.claim.chainStatus = "ABSENT";
    this.record.claim.revision += 1;
    return Promise.resolve({ kind: "updated", ...structuredClone(this.record) });
  }

  confirmClaim(
    input: Parameters<ClaimRepository["confirmClaim"]>[0],
  ): Promise<ClaimObservationResult> {
    if (!this.record) return Promise.resolve({ kind: "not_found" });
    this.record.claim.status = "CLAIMED";
    this.record.claim.claimSlot = input.claimSlot;
    this.record.claim.claimDeadline = input.claimDeadline;
    this.record.claim.chainSignature = input.chainSignature;
    this.record.claim.chainStatus = input.chainCommitment;
    this.record.claim.revision += 1;
    this.record.refresh.status = "CLAIMED";
    return Promise.resolve({ kind: "updated", ...structuredClone(this.record) });
  }
}

class MemoryObserver implements ClaimChainObserver {
  constructor(public result: ChainClaimInspection) {}
  inspectClaim(): Promise<ChainClaimInspection> {
    return Promise.resolve(this.result);
  }
}

function identity(bindings = true): IdentityRepository {
  return {
    listWalletBindings: () =>
      Promise.resolve(
        bindings
          ? [{
            walletBindingId: BINDING_ID,
            actorId: ACTOR_ID,
            walletAddress: CLAIMANT,
            cluster: "devnet",
            status: "ACTIVE" as const,
            revision: 1,
          }]
          : [],
      ),
  } as unknown as IdentityRepository;
}

function faultCode(error: unknown): string {
  return error instanceof ApiFault ? error.code : "";
}

Deno.test("claim prepare returns the exact wallet instruction and confirmed observation advances to claimed", async () => {
  const repository = new MemoryClaimRepository();
  repository.record = await baseRecord();
  const observer = new MemoryObserver({
    kind: "confirmed",
    commitment: "confirmed",
    claimSlot: 0,
    claimedAt: NOW,
    claimDeadline: DEADLINE,
    totalFundedAtomic: 1_000_000n,
    lockedRewardAtomic: 1_000_000n,
    observedAt: NOW,
  });
  const coordinator = new ClaimCoordinator(
    repository,
    identity(),
    observer,
    { cluster: "devnet", claimDurationSeconds: 180 },
    () => NOW,
  );

  const prepared = await coordinator.prepare({
    actor: actor(),
    refreshId: REFRESH_ID,
    walletBindingId: BINDING_ID,
    idempotencyKey: "claim-test-0001",
  });
  const instruction = prepared.data.instruction as Record<string, unknown>;
  const accounts = prepared.data.accounts as Record<string, unknown>;
  if (
    prepared.status !== 201 ||
    instruction.name !== "claim_witness" ||
    instruction.claim_duration_seconds !== 180 ||
    accounts.claimant !== CLAIMANT
  ) {
    throw new Error("claim intent is incomplete");
  }

  const confirmed = await coordinator.observe({
    actor: actor(),
    acceptanceId: ACCEPTANCE_ID,
    signature: SIGNATURE,
  });
  if (
    confirmed.status !== "CLAIMED" ||
    confirmed.claim_slot !== 0 ||
    confirmed.next_step !== "EVIDENCE_CHALLENGE"
  ) {
    throw new Error("confirmed claim did not advance exactly once");
  }
});

Deno.test("claim prepare replays before mutable wallet binding validation", async () => {
  const repository = new MemoryClaimRepository();
  repository.record = await baseRecord();
  repository.replay = true;
  const coordinator = new ClaimCoordinator(
    repository,
    identity(false),
    new MemoryObserver({ kind: "pending" }),
    { cluster: "devnet", claimDurationSeconds: 180 },
    () => NOW,
  );
  const result = await coordinator.prepare({
    actor: actor(),
    refreshId: REFRESH_ID,
    walletBindingId: BINDING_ID,
    idempotencyKey: "claim-test-0002",
  });
  if (result.status !== 200 || result.data.acceptance_id !== ACCEPTANCE_ID) {
    throw new Error("idempotent claim replay was not stable");
  }
});

Deno.test("pending and ambiguous claim outcomes never guess success", async () => {
  const repository = new MemoryClaimRepository();
  repository.record = await baseRecord();
  const coordinator = new ClaimCoordinator(
    repository,
    identity(),
    new MemoryObserver({ kind: "pending" }),
    { cluster: "devnet", claimDurationSeconds: 180 },
    () => NOW,
  );
  try {
    await coordinator.observe({
      actor: actor(),
      acceptanceId: ACCEPTANCE_ID,
      signature: SIGNATURE,
    });
    throw new Error("pending claim was accepted");
  } catch (error) {
    if (faultCode(error) !== "CLAIM_UNKNOWN") throw error;
  }
  if (repository.record?.claim.status !== "CONFIRMING") {
    throw new Error("pending claim did not preserve confirming state");
  }
});

Deno.test("definitively failed claim releases the local reservation", async () => {
  const repository = new MemoryClaimRepository();
  repository.record = await baseRecord();
  const coordinator = new ClaimCoordinator(
    repository,
    identity(),
    new MemoryObserver({ kind: "failed" }),
    { cluster: "devnet", claimDurationSeconds: 180 },
    () => NOW,
  );
  try {
    await coordinator.observe({
      actor: actor(),
      acceptanceId: ACCEPTANCE_ID,
      signature: SIGNATURE,
    });
    throw new Error("failed claim was accepted");
  } catch (error) {
    if (faultCode(error) !== "CLAIM_REJECTED") throw error;
  }
  if (repository.record?.claim.status !== "EMPTY") {
    throw new Error("failed claim did not release reservation");
  }
});

Deno.test("claim prepare maps capacity and self-claim failures without leaking authority", async () => {
  for (
    const [kind, expected] of [
      ["self_claim", "CLAIM_NOT_AVAILABLE"],
      ["capacity_full", "CLAIM_CAPACITY_FULL"],
    ] as const
  ) {
    const repository = new MemoryClaimRepository();
    repository.prepareKind = kind;
    const coordinator = new ClaimCoordinator(
      repository,
      identity(),
      new MemoryObserver({ kind: "pending" }),
      { cluster: "devnet", claimDurationSeconds: 180 },
      () => NOW,
    );
    try {
      await coordinator.prepare({
        actor: actor(),
        refreshId: REFRESH_ID,
        walletBindingId: BINDING_ID,
        idempotencyKey: `claim-test-${kind}`,
      });
      throw new Error("claim rejection path unexpectedly succeeded");
    } catch (error) {
      if (faultCode(error) !== expected) throw error;
    }
  }
});
