import { ApiFault } from "../src/errors.ts";
import type { ActorRecord } from "../src/identity-repository.ts";
import { OpportunityMatcher } from "../src/opportunity-matcher.ts";
import type { OpportunityRecord, OpportunityRepository } from "../src/opportunity-repository.ts";

const ACTOR_ID = "70000000-0000-4000-8000-000000000001";
const REFRESH_A = "71000000-0000-4000-8000-000000000001";
const REFRESH_B = "71000000-0000-4000-8000-000000000002";

function actor(status: ActorRecord["status"] = "ACTIVE"): ActorRecord {
  return { actorId: ACTOR_ID, status, revision: 1 };
}

function record(
  refreshId: string,
  distanceM: number | null,
  options?: {
    payoutRule?: OpportunityRecord["payoutRule"];
    requiredWitnesses?: number;
    maxWitnesses?: number;
    activeClaims?: number;
    rewardAtomic?: bigint;
  },
): OpportunityRecord {
  const requiredWitnesses = options?.requiredWitnesses ?? 1;
  const maxWitnesses = options?.maxWitnesses ?? requiredWitnesses;
  const activeClaims = options?.activeClaims ?? 0;

  return {
    refreshId,
    stateId: "72000000-0000-4000-8000-000000000001",
    stateVersion: 1,
    title: "Parking Lot B",
    question: "Available spaces",
    stateType: "NUMERIC",
    unitCode: "spaces",
    locationId: "73000000-0000-4000-8000-000000000001",
    locationName: "Parking Lot B",
    locationType: "PARKING",
    displayAddress: "Demo district",
    rewardMint: "So11111111111111111111111111111111111111112",
    rewardAtomic: options?.rewardAtomic ?? 500_000n,
    payoutRule: options?.payoutRule ?? "SINGLE_WINNER_ALL",
    refreshExpiresAt: new Date("2026-09-25T12:15:00.000Z"),
    evidenceDeadline: new Date("2026-09-25T12:13:00.000Z"),
    verificationClass: "FAST",
    requiredWitnesses,
    maxWitnesses,
    activeClaims,
    remainingSlots: maxWitnesses - activeClaims,
    proofPolicySnapshot: {
      template_key: "parking.available_spaces.v1",
      state_type: "NUMERIC",
      fresh_ttl_seconds: 600,
      aging_ratio: 0.7,
      verification_class: "FAST",
      required_witnesses: requiredWitnesses,
      capture: {
        media_required: true,
        location_required: true,
      },
      numeric: {
        scale: 0,
        min: 0,
        conflict_tolerance: 1,
        allow_two_of_three: true,
      },
    },
    distanceM,
    stateRevision: 8,
    refreshRevision: 4,
  };
}

class MemoryOpportunityRepository implements OpportunityRepository {
  readonly rows: OpportunityRecord[] = [
    record(REFRESH_A, 90),
    record(REFRESH_B, 120, {
      payoutRule: "EQUAL_SPLIT_REQUIRED_WITNESSES",
      requiredWitnesses: 2,
      maxWitnesses: 3,
      activeClaims: 1,
      rewardAtomic: 900_000n,
    }),
  ];

  listNearby(
    input: Parameters<OpportunityRepository["listNearby"]>[0],
  ): Promise<OpportunityRecord[]> {
    let rows = this.rows;
    if (input.cursor) {
      rows = rows.filter((row) =>
        row.distanceM !== null &&
        (
          row.distanceM > input.cursor!.distanceM ||
          (
            row.distanceM === input.cursor!.distanceM &&
            row.refreshId > input.cursor!.refreshId
          )
        )
      );
    }
    return Promise.resolve(
      rows.slice(0, input.limit).map((row) => structuredClone(row)),
    );
  }

  getOpportunity(
    input: Parameters<OpportunityRepository["getOpportunity"]>[0],
  ): Promise<OpportunityRecord | null> {
    const row = this.rows.find((candidate) => candidate.refreshId === input.refreshId);
    return Promise.resolve(row ? structuredClone(row) : null);
  }
}

function faultCode(error: unknown): string {
  return error instanceof ApiFault ? error.code : "";
}

Deno.test("nearby opportunities paginate with an opaque deterministic cursor", async () => {
  const matcher = new OpportunityMatcher(new MemoryOpportunityRepository());

  const first = await matcher.nearby(actor(), {
    lat: 29.4,
    lng: 76.9,
    radiusM: 1000,
    limit: 1,
    cursor: null,
  });

  const firstItems = first.items as Record<string, unknown>[];
  if (firstItems.length !== 1 || firstItems[0].refresh_id !== REFRESH_A) {
    throw new Error("first opportunity page is incorrect");
  }
  if (typeof first.next_cursor !== "string") {
    throw new Error("next opportunity cursor missing");
  }

  const second = await matcher.nearby(actor(), {
    lat: 29.4,
    lng: 76.9,
    radiusM: 1000,
    limit: 1,
    cursor: first.next_cursor,
  });
  const secondItems = second.items as Record<string, unknown>[];
  if (secondItems.length !== 1 || secondItems[0].refresh_id !== REFRESH_B) {
    throw new Error("opportunity cursor did not advance");
  }
  if (second.next_cursor !== null) {
    throw new Error("terminal opportunity page returned a cursor");
  }
});

Deno.test("opportunity payload exposes reward, evidence summary, and capacity without private fields", async () => {
  const matcher = new OpportunityMatcher(new MemoryOpportunityRepository());
  const data = await matcher.detail(actor(), REFRESH_B);

  const reward = data.reward as Record<string, unknown>;
  if (
    reward.pool_atomic !== "900000" ||
    reward.payout_rule !== "EQUAL_SPLIT_REQUIRED_WITNESSES"
  ) {
    throw new Error("opportunity reward projection is incorrect");
  }

  const evidence = data.evidence_summary as Record<string, unknown>;
  if (
    evidence.media_required !== true ||
    evidence.location_required !== true ||
    evidence.required_witnesses !== 2
  ) {
    throw new Error("evidence summary is incomplete");
  }

  const availability = data.availability as Record<string, unknown>;
  if (
    availability.claimable !== true ||
    availability.active_claims !== 1 ||
    availability.remaining_slots !== 2
  ) {
    throw new Error("opportunity capacity is incorrect");
  }

  const serialized = JSON.stringify(data);
  for (
    const forbidden of [
      "requester_actor_id",
      "creator_wallet_address",
      "proof_policy_snapshot",
      "intent_core_hash",
    ]
  ) {
    if (serialized.includes(forbidden)) {
      throw new Error(`private/internal field leaked: ${forbidden}`);
    }
  }
});

Deno.test("tampered opportunity cursor fails closed", async () => {
  const matcher = new OpportunityMatcher(new MemoryOpportunityRepository());

  try {
    await matcher.nearby(actor(), {
      lat: 29.4,
      lng: 76.9,
      radiusM: 1000,
      limit: 10,
      cursor: "not-a-valid-cursor",
    });
    throw new Error("tampered cursor was accepted");
  } catch (error) {
    if (faultCode(error) !== "INVALID_CURSOR") throw error;
  }
});

Deno.test("disabled and restricted actors cannot discover opportunities", async () => {
  const matcher = new OpportunityMatcher(new MemoryOpportunityRepository());

  for (const status of ["DISABLED", "RESTRICTED"] as const) {
    try {
      await matcher.nearby(actor(status), {
        lat: 29.4,
        lng: 76.9,
        radiusM: 1000,
        limit: 10,
        cursor: null,
      });
      throw new Error("blocked actor discovered opportunities");
    } catch (error) {
      const expected = status === "DISABLED" ? "ACTOR_DISABLED" : "ACTOR_RESTRICTED";
      if (faultCode(error) !== expected) throw error;
    }
  }
});

Deno.test("missing opportunity returns stable not-found error", async () => {
  const matcher = new OpportunityMatcher(new MemoryOpportunityRepository());

  try {
    await matcher.detail(actor(), "71000000-0000-4000-8000-000000000099");
    throw new Error("missing opportunity was returned");
  } catch (error) {
    if (faultCode(error) !== "OPPORTUNITY_NOT_FOUND") throw error;
  }
});

Deno.test("opportunity cursors cannot cross filters, locations or actors", async () => {
  const repository: OpportunityRepository = new MemoryOpportunityRepository();
  repository.summarizeNearby = () =>
    Promise.resolve({ total: 17, categories: ["PARKING", "SHOP"] });
  const matcher = new OpportunityMatcher(repository);
  const query = { lat: 29.4, lng: 76.9, radiusM: 1000, limit: 1, cursor: null };
  const first = await matcher.nearby(actor(), query);
  if (first.total !== 17 || !Array.isArray(first.categories) || first.categories.length !== 2) {
    throw new Error("area totals were replaced by the visible page length");
  }
  const cursor = first.next_cursor as string;
  for (const change of [{ sort: "payout" }, { category: "SHOP" }, { lat: 29.5 }]) {
    try {
      await matcher.nearby(actor(), { ...query, cursor, ...change });
      throw new Error("cursor crossed its query boundary");
    } catch (error) {
      if (faultCode(error) !== "INVALID_CURSOR") throw error;
    }
  }
  try {
    await matcher.nearby({ ...actor(), actorId: REFRESH_B }, { ...query, cursor });
    throw new Error("cursor crossed its actor boundary");
  } catch (error) {
    if (faultCode(error) !== "INVALID_CURSOR") throw error;
  }
});

Deno.test("claimed task details retain capacity while preventing another claim by the same actor", async () => {
  const repository = new MemoryOpportunityRepository();
  repository.rows[1].actorHasActiveClaim = true;
  const data = await new OpportunityMatcher(repository).detail(actor(), REFRESH_B);
  const availability = data.availability as { claimable: boolean; remaining_slots: number };
  if (availability.claimable || availability.remaining_slots !== 2) {
    throw new Error("owned task confused remaining witness capacity with actor eligibility");
  }
});
