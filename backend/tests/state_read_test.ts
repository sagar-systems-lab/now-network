import { createApp } from "../src/app.ts";
import type { AuthPrincipal, AuthVerifier } from "../src/auth.ts";
import type {
  ActorRecord,
  ChallengeConsumptionResult,
  IdentityRepository,
  WalletBindingChallengeRecord,
  WalletBindingRecord,
} from "../src/identity-repository.ts";
import type {
  NearbyStateRecord,
  StateDetailRecord,
  StateHistoryRecord,
  StateRepository,
} from "../src/state-repository.ts";

const STATE_ID = "40000000-0000-4000-8000-000000000001";
const HISTORY_A = "41000000-0000-4000-8000-000000000001";
const HISTORY_B = "41000000-0000-4000-8000-000000000002";
const HISTORY_C = "41000000-0000-4000-8000-000000000003";

class RejectingAuthVerifier implements AuthVerifier {
  verify(_request: Request): Promise<AuthPrincipal> {
    return Promise.reject(new Error("public state reads must not invoke auth"));
  }
}

class UnusedIdentityRepository implements IdentityRepository {
  resolveActor(_authUserId: string, _principalType: string): Promise<ActorRecord> {
    return Promise.reject(new Error("identity repository must not be used"));
  }

  listWalletBindings(_actorId: string): Promise<WalletBindingRecord[]> {
    return Promise.reject(new Error("identity repository must not be used"));
  }

  issueWalletBindingChallenge(_challenge: WalletBindingChallengeRecord): Promise<void> {
    return Promise.reject(new Error("identity repository must not be used"));
  }

  getWalletBindingChallenge(
    _challengeId: string,
  ): Promise<WalletBindingChallengeRecord | null> {
    return Promise.reject(new Error("identity repository must not be used"));
  }

  consumeWalletBindingChallenge(
    _challengeId: string,
    _authUserId: string,
    _now: Date,
  ): Promise<ChallengeConsumptionResult> {
    return Promise.reject(new Error("identity repository must not be used"));
  }
}

function nearbyRecord(): NearbyStateRecord {
  return {
    stateId: STATE_ID,
    title: "Parking Lot B",
    question: "Available spaces",
    stateType: "NUMERIC",
    unitCode: "spaces",
    currentValue: { scaled_value: "2", scale: 0, unit: "spaces" },
    observedAt: new Date("2026-09-24T10:00:00.000Z"),
    agingAt: new Date("2026-09-24T10:07:00.000Z"),
    freshUntil: new Date("2026-09-24T10:10:00.000Z"),
    verificationClass: "FAST",
    refreshStatus: null,
    conflictActive: false,
    distanceM: 92,
    revision: 14,
  };
}

function detailRecord(): StateDetailRecord {
  const nearby = nearbyRecord();
  return {
    stateId: nearby.stateId,
    version: 1,
    canonicalKey: "parking.available_spaces.v1",
    title: nearby.title,
    question: nearby.question,
    stateType: nearby.stateType,
    unitCode: nearby.unitCode,
    currentValue: nearby.currentValue,
    observedAt: nearby.observedAt,
    observationEarliest: new Date("2026-09-24T09:59:58.000Z"),
    observationLatest: new Date("2026-09-24T10:00:02.000Z"),
    agingAt: nearby.agingAt,
    freshUntil: nearby.freshUntil,
    verificationClass: nearby.verificationClass,
    conflictActive: nearby.conflictActive,
    revision: nearby.revision,
    location: {
      locationId: "42000000-0000-4000-8000-000000000001",
      name: "Parking Lot B",
      locationType: "PARKING",
      displayAddress: "Demo district",
    },
    verification: {
      status: "VERIFIED",
      reasonCodes: ["NUMERIC_WITHIN_TOLERANCE"],
      evidenceCount: 1,
    },
    activeRefresh: null,
  };
}

function historyRecord(
  historyId: string,
  observedAt: string,
  value: number,
): StateHistoryRecord {
  const observed = new Date(observedAt);
  return {
    historyId,
    stateId: STATE_ID,
    stateVersion: 1,
    value: { scaled_value: String(value), scale: 0, unit: "spaces" },
    observedAt: observed,
    agingAt: new Date(observed.getTime() + 7 * 60_000),
    freshUntil: new Date(observed.getTime() + 10 * 60_000),
    verificationClass: "FAST",
    verificationStatus: "VERIFIED",
  };
}

class MemoryStateRepository implements StateRepository {
  nearbyRows: NearbyStateRecord[] = [nearbyRecord()];
  detail: StateDetailRecord | null = detailRecord();
  historyRows: StateHistoryRecord[] = [
    historyRecord(HISTORY_A, "2026-09-24T10:00:00.000Z", 4),
    historyRecord(HISTORY_B, "2026-09-24T09:50:00.000Z", 3),
    historyRecord(HISTORY_C, "2026-09-24T09:40:00.000Z", 2),
  ];
  lastNearbyInput: Parameters<StateRepository["listNearby"]>[0] | null = null;
  lastHistoryInput: Parameters<StateRepository["listHistory"]>[0] | null = null;

  listNearby(
    input: Parameters<StateRepository["listNearby"]>[0],
  ): Promise<NearbyStateRecord[]> {
    this.lastNearbyInput = structuredClone(input);
    return Promise.resolve(structuredClone(this.nearbyRows));
  }

  getState(_stateId: string): Promise<StateDetailRecord | null> {
    return Promise.resolve(this.detail ? structuredClone(this.detail) : null);
  }

  listHistory(
    input: Parameters<StateRepository["listHistory"]>[0],
  ): Promise<StateHistoryRecord[]> {
    this.lastHistoryInput = structuredClone(input);
    return Promise.resolve(structuredClone(this.historyRows));
  }
}

function createTestApp(
  stateRepository: StateRepository,
  now = new Date("2026-09-24T10:05:00.000Z"),
): (request: Request) => Promise<Response> {
  return createApp({
    authVerifier: new RejectingAuthVerifier(),
    identityRepository: new UnusedIdentityRepository(),
    stateRepository,
    now: () => new Date(now),
  });
}

async function body(response: Response): Promise<Record<string, unknown>> {
  return await response.json() as Record<string, unknown>;
}

function errorCode(payload: Record<string, unknown>): string {
  return ((payload.error as Record<string, unknown>)?.code ?? "") as string;
}

Deno.test("nearby state read is public, bounded, and derives freshness", async () => {
  const repository = new MemoryStateRepository();
  const app = createTestApp(repository);
  const response = await app(
    new Request(
      "http://localhost/v1/states/nearby?lat=29.4&lng=76.9&radius_m=1000&limit=20",
    ),
  );

  if (response.status !== 200) throw new Error(`expected 200, got ${response.status}`);
  const envelope = await body(response);
  const data = envelope.data as Record<string, unknown>;
  const items = data.items as Record<string, unknown>[];
  if (items.length !== 1 || items[0].freshness_status !== "LIVE") {
    throw new Error("nearby state freshness was not derived from authoritative timestamps");
  }
  if (repository.lastNearbyInput?.limit !== 21) {
    throw new Error("repository must receive one extra row for cursor pagination");
  }
  if (response.headers.get("cache-control") !== "no-store") {
    throw new Error("state read response must not be cached implicitly");
  }
});

Deno.test("nearby state input rejects unbounded or malformed geography", async () => {
  const app = createTestApp(new MemoryStateRepository());
  for (
    const path of [
      "/v1/states/nearby?lat=91&lng=76.9&radius_m=1000",
      "/v1/states/nearby?lat=29.4&lng=181&radius_m=1000",
      "/v1/states/nearby?lat=29.4&lng=76.9&radius_m=50001",
      "/v1/states/nearby?lat=29.4&lng=76.9&radius_m=1000&limit=51",
    ]
  ) {
    const response = await app(new Request(`http://localhost${path}`));
    if (response.status !== 400 || errorCode(await body(response)) !== "INVALID_REQUEST") {
      throw new Error(`invalid nearby query was accepted: ${path}`);
    }
  }
});

Deno.test("detail freshness changes only at timestamp boundaries", async () => {
  const repository = new MemoryStateRepository();

  const aging = await body(
    await createTestApp(repository, new Date("2026-09-24T10:07:00.000Z"))(
      new Request(`http://localhost/v1/states/${STATE_ID}`),
    ),
  );
  if ((aging.data as Record<string, unknown>).freshness_status !== "AGING") {
    throw new Error("aging boundary must derive AGING");
  }

  const stale = await body(
    await createTestApp(repository, new Date("2026-09-24T10:10:00.000Z"))(
      new Request(`http://localhost/v1/states/${STATE_ID}`),
    ),
  );
  if ((stale.data as Record<string, unknown>).freshness_status !== "STALE") {
    throw new Error("fresh-until boundary must derive STALE");
  }
});

Deno.test("state detail returns a sanitized public projection", async () => {
  const repository = new MemoryStateRepository();
  const response = await createTestApp(repository)(
    new Request(`http://localhost/v1/states/${STATE_ID}`),
  );
  const envelope = await body(response);
  const serialized = JSON.stringify(envelope);

  for (
    const forbidden of ["actor_id", "wallet_address", "media_object_key", "verification_trace"]
  ) {
    if (serialized.includes(forbidden)) {
      throw new Error(`public state detail leaked private field ${forbidden}`);
    }
  }

  const data = envelope.data as Record<string, unknown>;
  const verification = data.verification as Record<string, unknown>;
  if (verification.evidence_count !== 1 || verification.status !== "VERIFIED") {
    throw new Error("verification summary is missing");
  }
});

Deno.test("missing state returns stable not-found error", async () => {
  const repository = new MemoryStateRepository();
  repository.detail = null;
  const response = await createTestApp(repository)(
    new Request(`http://localhost/v1/states/${STATE_ID}`),
  );
  if (response.status !== 404 || errorCode(await body(response)) !== "STATE_NOT_FOUND") {
    throw new Error("missing state must return STATE_NOT_FOUND");
  }
});

Deno.test("history uses an opaque keyset cursor", async () => {
  const repository = new MemoryStateRepository();
  const app = createTestApp(repository);
  const first = await body(
    await app(new Request(`http://localhost/v1/states/${STATE_ID}/history?limit=2`)),
  );
  const firstData = first.data as Record<string, unknown>;
  const items = firstData.items as Record<string, unknown>[];
  const cursor = firstData.next_cursor;

  if (items.length !== 2 || typeof cursor !== "string") {
    throw new Error("history page must return two observations and a cursor");
  }

  await app(
    new Request(
      `http://localhost/v1/states/${STATE_ID}/history?limit=2&cursor=${cursor}`,
    ),
  );

  if (
    repository.lastHistoryInput?.cursor?.historyId !== HISTORY_B ||
    repository.lastHistoryInput.cursor.observedAt.toISOString() !==
      "2026-09-24T09:50:00.000Z"
  ) {
    throw new Error("history cursor must resume after the last visible observation");
  }
});

Deno.test("tampered cursor fails closed", async () => {
  const app = createTestApp(new MemoryStateRepository());
  const response = await app(
    new Request(
      "http://localhost/v1/states/40000000-0000-4000-8000-000000000001/history?cursor=bad",
    ),
  );
  if (response.status !== 400 || errorCode(await body(response)) !== "INVALID_CURSOR") {
    throw new Error("malformed cursor must return INVALID_CURSOR");
  }
});
