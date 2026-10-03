import type { FreshnessStatus } from "../../packages/contracts/src/core.ts";
import { deriveFreshness } from "../../packages/domain/src/freshness.ts";
import { ApiFault } from "./errors.ts";
import { discoveryFingerprint } from "./discovery-query.ts";
import type {
  NearbyStateCursor,
  NearbyStateRecord,
  StateDetailRecord,
  StateHistoryCursor,
  StateHistoryRecord,
  StateRepository,
} from "./state-repository.ts";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export const DEFAULT_NEARBY_LIMIT = 30;
export const MAX_NEARBY_LIMIT = 50;
export const MAX_NEARBY_RADIUS_M = 50_000;
export const DEFAULT_HISTORY_LIMIT = 30;
export const MAX_HISTORY_LIMIT = 50;

function iso(value: Date | null): string | null {
  return value === null ? null : value.toISOString();
}

function freshnessStatus(
  now: Date,
  record: Pick<NearbyStateRecord, "observedAt" | "agingAt" | "freshUntil">,
): FreshnessStatus | null {
  if (!record.observedAt || !record.agingAt || !record.freshUntil) return null;
  return deriveFreshness(now.getTime(), {
    observedAtMs: record.observedAt.getTime(),
    agingAtMs: record.agingAt.getTime(),
    freshUntilMs: record.freshUntil.getTime(),
  });
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

function decodeNearbyCursor(value: string | null, query: string): NearbyStateCursor | null {
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
    typeof cursor.state_id !== "string" ||
    !UUID_PATTERN.test(cursor.state_id) ||
    cursor.query !== query ||
    typeof cursor.as_of !== "string" || !Number.isFinite(new Date(cursor.as_of).getTime())
  ) {
    throw new ApiFault(400, "INVALID_CURSOR", "Invalid cursor.");
  }

  return {
    distanceM: cursor.distance_m,
    stateId: cursor.state_id,
    asOf: new Date(cursor.as_of as string),
  };
}

function decodeHistoryCursor(value: string | null): StateHistoryCursor | null {
  if (value === null) return null;
  const decoded = decodeBase64Url(value);
  if (!decoded || typeof decoded !== "object" || Array.isArray(decoded)) {
    throw new ApiFault(400, "INVALID_CURSOR", "Invalid cursor.");
  }

  const cursor = decoded as Record<string, unknown>;
  if (
    typeof cursor.observed_at !== "string" ||
    typeof cursor.history_id !== "string" ||
    !UUID_PATTERN.test(cursor.history_id)
  ) {
    throw new ApiFault(400, "INVALID_CURSOR", "Invalid cursor.");
  }

  const observedAt = new Date(cursor.observed_at);
  if (!Number.isFinite(observedAt.getTime())) {
    throw new ApiFault(400, "INVALID_CURSOR", "Invalid cursor.");
  }

  return { observedAt, historyId: cursor.history_id };
}

function nearbyCard(record: NearbyStateRecord, now: Date): Record<string, unknown> {
  return {
    state_id: record.stateId,
    location: record.location
      ? {
        location_id: record.location.locationId,
        name: record.location.name,
        location_type: record.location.locationType,
        display_address: record.location.displayAddress,
        center: record.location.center,
      }
      : null,
    title: record.title,
    question: record.question,
    state_type: record.stateType,
    value: record.currentValue,
    unit_code: record.unitCode,
    freshness_status: freshnessStatus(now, record),
    observed_at: iso(record.observedAt),
    aging_at: iso(record.agingAt),
    fresh_until: iso(record.freshUntil),
    verification_class: record.verificationClass,
    refresh_status: record.refreshStatus,
    conflict_active: record.conflictActive,
    distance_m: record.distanceM,
    revision: record.revision,
  };
}

function detailPayload(record: StateDetailRecord, now: Date): Record<string, unknown> {
  return {
    state_id: record.stateId,
    version: record.version,
    canonical_key: record.canonicalKey,
    title: record.title,
    question: record.question,
    state_type: record.stateType,
    value: record.currentValue,
    unit_code: record.unitCode,
    freshness_status: freshnessStatus(now, record),
    observed_at: iso(record.observedAt),
    observation_earliest: iso(record.observationEarliest),
    observation_latest: iso(record.observationLatest),
    aging_at: iso(record.agingAt),
    fresh_until: iso(record.freshUntil),
    verification_class: record.verificationClass,
    conflict_active: record.conflictActive,
    revision: record.revision,
    location: {
      location_id: record.location.locationId,
      name: record.location.name,
      location_type: record.location.locationType,
      display_address: record.location.displayAddress,
      center: record.location.center ?? null,
    },
    verification: record.verification === null ? null : {
      status: record.verification.status,
      reason_codes: record.verification.reasonCodes,
      evidence_count: record.verification.evidenceCount,
    },
    active_refresh: record.activeRefresh === null ? null : {
      refresh_id: record.activeRefresh.refreshId,
      status: record.activeRefresh.status,
      verification_class: record.activeRefresh.verificationClass,
      expires_at: record.activeRefresh.expiresAt.toISOString(),
      revision: record.activeRefresh.revision,
    },
  };
}

function historyItem(record: StateHistoryRecord): Record<string, unknown> {
  return {
    history_id: record.historyId,
    state_id: record.stateId,
    state_version: record.stateVersion,
    value: record.value,
    observed_at: record.observedAt.toISOString(),
    aging_at: record.agingAt.toISOString(),
    fresh_until: record.freshUntil.toISOString(),
    verification_class: record.verificationClass,
    verification_status: record.verificationStatus,
  };
}

export class StateReadService {
  constructor(
    private readonly repository: StateRepository,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async nearby(input: {
    lat: number;
    lng: number;
    radiusM: number;
    limit: number;
    cursor: string | null;
    search?: string;
    freshness?: string;
  }): Promise<Record<string, unknown>> {
    const search = input.search?.trim() ?? "";
    const freshness = input.freshness ?? "all";
    if (search.length > 120 || Array.from(search).some((c) => c.charCodeAt(0) < 32)) {
      throw new ApiFault(400, "INVALID_SEARCH", "Search must contain at most 120 characters.");
    }
    if (!["all", "live", "aging", "stale", "unobserved", "conflict"].includes(freshness)) {
      throw new ApiFault(400, "INVALID_FRESHNESS", "Choose a supported freshness filter.");
    }
    const query = await discoveryFingerprint([input.lat, input.lng, input.radiusM, search, freshness]);
    const cursor = decodeNearbyCursor(input.cursor, query);
    const asOf = cursor?.asOf ?? this.now();
    const rows = await this.repository.listNearby({
      search,
      freshness,
      asOf,
      lat: input.lat,
      lng: input.lng,
      radiusM: input.radiusM,
      limit: input.limit + 1,
      cursor,
    });

    const visible = rows.slice(0, input.limit);
    const hasMore = rows.length > input.limit;
    const last = visible.at(-1);
    const nextCursor = hasMore && last
      ? encodeBase64Url({
        distance_m: last.distanceM,
        state_id: last.stateId,
        query,
        as_of: asOf.toISOString(),
      })
      : null;
    const now = this.now();

    const counts = await this.repository.summarizeNearby?.({
      lat: input.lat,
      lng: input.lng,
      radiusM: input.radiusM,
      limit: input.limit,
      cursor: null,
      search,
      freshness,
      asOf,
    });
    return {
      counts: counts ?? null,
      as_of: asOf.toISOString(),
      items: visible.map((record) => nearbyCard(record, now)),
      next_cursor: nextCursor,
    };
  }

  async detail(stateId: string): Promise<Record<string, unknown>> {
    const record = await this.repository.getState(stateId);
    if (record === null) {
      throw new ApiFault(404, "STATE_NOT_FOUND", "State was not found.");
    }
    return detailPayload(record, this.now());
  }

  async history(input: {
    stateId: string;
    limit: number;
    cursor: string | null;
  }): Promise<Record<string, unknown>> {
    const cursor = decodeHistoryCursor(input.cursor);
    const rows = await this.repository.listHistory({
      stateId: input.stateId,
      limit: input.limit + 1,
      cursor,
    });

    const visible = rows.slice(0, input.limit);
    const hasMore = rows.length > input.limit;
    const last = visible.at(-1);
    const nextCursor = hasMore && last
      ? encodeBase64Url({
        observed_at: last.observedAt.toISOString(),
        history_id: last.historyId,
      })
      : null;

    return {
      items: visible.map(historyItem),
      next_cursor: nextCursor,
    };
  }
}
