import type { ActorRecord } from "./identity-repository.ts";
import { ApiFault } from "./errors.ts";
import { discoveryFingerprint } from "./discovery-query.ts";
import type {
  OpportunityCursor,
  OpportunityRecord,
  OpportunityRepository,
  OpportunitySort,
} from "./opportunity-repository.ts";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export const DEFAULT_OPPORTUNITY_LIMIT = 30;
export const MAX_OPPORTUNITY_LIMIT = 50;
export const MAX_OPPORTUNITY_RADIUS_M = 50_000;

function assertActorActive(actor: ActorRecord): void {
  if (actor.status === "DISABLED") {
    throw new ApiFault(403, "ACTOR_DISABLED", "This actor is disabled.");
  }
  if (actor.status === "RESTRICTED") {
    throw new ApiFault(403, "ACTOR_RESTRICTED", "This actor is restricted.");
  }
}

function encodeBase64Url(value: unknown): string {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

function decodeBase64Url(value: string): unknown {
  if (value.length === 0 || value.length > 512) {
    throw new ApiFault(400, "INVALID_CURSOR", "Invalid cursor.");
  }

  try {
    const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
    const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) {
      bytes[index] = binary.charCodeAt(index);
    }
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new ApiFault(400, "INVALID_CURSOR", "Invalid cursor.");
  }
}

function decodeCursor(value: string | null, query: string): OpportunityCursor | null {
  if (value === null) return null;
  const decoded = decodeBase64Url(value);
  if (!decoded || typeof decoded !== "object" || Array.isArray(decoded)) {
    throw new ApiFault(400, "INVALID_CURSOR", "Invalid cursor.");
  }

  const cursor = decoded as Record<string, unknown>;
  if (
    typeof cursor.distance_m !== "number" ||
    !Number.isFinite(cursor.distance_m) ||
    cursor.distance_m < 0 ||
    typeof cursor.refresh_id !== "string" ||
    !UUID_PATTERN.test(cursor.refresh_id) ||
    cursor.query !== query ||
    typeof cursor.expires_at !== "string" ||
    !Number.isFinite(new Date(cursor.expires_at).getTime()) ||
    typeof cursor.reward_mint !== "string" || cursor.reward_mint.length > 80 ||
    typeof cursor.estimated_atomic !== "string" || !/^[0-9]{1,20}$/.test(cursor.estimated_atomic)
  ) {
    throw new ApiFault(400, "INVALID_CURSOR", "Invalid cursor.");
  }

  return {
    distanceM: cursor.distance_m,
    refreshId: cursor.refresh_id,
    expiresAt: new Date(cursor.expires_at as string),
    rewardMint: cursor.reward_mint as string,
    estimatedAtomic: cursor.estimated_atomic as string,
  };
}

function payload(record: OpportunityRecord): Record<string, unknown> {
  return {
    refresh_id: record.refreshId,
    state_id: record.stateId,
    state_version: record.stateVersion,
    title: record.title,
    question: record.question,
    state_type: record.stateType,
    unit_code: record.unitCode,
    location: {
      location_id: record.locationId,
      name: record.locationName,
      location_type: record.locationType,
      display_address: record.displayAddress,
      center: record.center ?? null,
    },
    reward: {
      mint: record.rewardMint,
      pool_atomic: record.rewardAtomic.toString(),
      payout_rule: record.payoutRule,
    },
    distance_m: record.distanceM,
    expires_at: record.refreshExpiresAt.toISOString(),
    evidence_deadline: record.evidenceDeadline.toISOString(),
    verification_class: record.verificationClass,
    evidence_summary: {
      template_key: record.proofPolicySnapshot.template_key,
      media_required: record.proofPolicySnapshot.capture.media_required,
      video_required: record.proofPolicySnapshot.capture.video_required === true,
      location_required: record.proofPolicySnapshot.capture.location_required,
      required_witnesses: record.requiredWitnesses,
      max_witnesses: record.maxWitnesses,
    },
    availability: {
      claimable: record.remainingSlots > 0 && record.actorHasActiveClaim !== true,
      active_claims: record.activeClaims,
      remaining_slots: record.remainingSlots,
    },
    state_revision: record.stateRevision,
    revision: record.refreshRevision,
  };
}

export class OpportunityMatcher {
  constructor(private readonly repository: OpportunityRepository) {}

  async nearby(
    actor: ActorRecord,
    input: {
      lat: number;
      lng: number;
      radiusM: number;
      limit: number;
      cursor: string | null;
      sort?: string;
      category?: string | null;
    },
  ): Promise<Record<string, unknown>> {
    assertActorActive(actor);
    const sort = input.sort ?? "nearest";
    const category = input.category?.trim() || null;
    if (!["nearest", "payout", "ending"].includes(sort)) {
      throw new ApiFault(400, "INVALID_SORT", "Choose a supported opportunity sort.");
    }
    if (
      category && (category.length > 80 || Array.from(category).some((c) => c.charCodeAt(0) < 32))
    ) {
      throw new ApiFault(400, "INVALID_CATEGORY", "Choose a valid category.");
    }
    const query = await discoveryFingerprint([
      actor.actorId,
      input.lat,
      input.lng,
      input.radiusM,
      sort,
      category,
    ]);
    const cursor = decodeCursor(input.cursor, query);
    const asOf = new Date();
    const rows = await this.repository.listNearby({
      sort: sort as OpportunitySort,
      category,
      asOf,
      actorId: actor.actorId,
      lat: input.lat,
      lng: input.lng,
      radiusM: input.radiusM,
      limit: input.limit + 1,
      cursor,
    });

    const visible = rows.slice(0, input.limit);
    const hasMore = rows.length > input.limit;
    const last = visible.at(-1);
    const nextCursor = hasMore && last && last.distanceM !== null
      ? encodeBase64Url({
        distance_m: last.distanceM,
        refresh_id: last.refreshId,
        query,
        expires_at: last.refreshExpiresAt.toISOString(),
        reward_mint: last.rewardMint,
        estimated_atomic: (last.payoutRule === "EQUAL_SPLIT_REQUIRED_WITNESSES"
          ? last.rewardAtomic / BigInt(last.requiredWitnesses)
          : last.rewardAtomic).toString(),
      })
      : null;

    const summary = await this.repository.summarizeNearby?.({
      actorId: actor.actorId,
      lat: input.lat,
      lng: input.lng,
      radiusM: input.radiusM,
      limit: input.limit,
      cursor: null,
      sort: sort as OpportunitySort,
      category,
      asOf,
    });
    return {
      total: summary?.total ?? null,
      categories: summary?.categories ?? [],
      sort,
      category,
      items: visible.map(payload),
      next_cursor: nextCursor,
    };
  }

  async detail(actor: ActorRecord, refreshId: string): Promise<Record<string, unknown>> {
    assertActorActive(actor);
    const record = await this.repository.getOpportunity({
      actorId: actor.actorId,
      refreshId,
    });
    if (record === null) {
      throw new ApiFault(404, "OPPORTUNITY_NOT_FOUND", "Opportunity was not found.");
    }
    return payload(record);
  }
}
