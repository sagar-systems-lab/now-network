import { StateReadService } from "../src/state-read.ts";
import { ApiFault } from "../src/errors.ts";
import type { NearbyStateRecord, StateRepository } from "../src/state-repository.ts";

Deno.test("state paging preserves freshness time and rejects a changed search", async () => {
  const now = new Date("2026-10-03T12:00:00Z");
  const record: NearbyStateRecord = {
    stateId: "71000000-0000-4000-8000-000000000001",
    title: "Parking",
    question: "Available spaces?",
    stateType: "NUMERIC",
    unitCode: "spaces",
    currentValue: 2,
    observedAt: new Date(now.getTime() - 60_000),
    agingAt: new Date(now.getTime() + 60_000),
    freshUntil: new Date(now.getTime() + 120_000),
    verificationClass: "FAST",
    refreshStatus: null,
    conflictActive: false,
    distanceM: 12,
    revision: 1,
  };
  const requested: Parameters<StateRepository["listNearby"]>[0][] = [];
  const repository: StateRepository = {
    listNearby: (input) => {
      requested.push(input);
      return Promise.resolve([record, { ...record, stateId: "71000000-0000-4000-8000-000000000002" }]);
    },
    getState: () => Promise.resolve(null),
    listHistory: () => Promise.resolve([]),
    summarizeNearby: () => Promise.resolve({ total: 24, live: 14, aging: 5, stale: 5 }),
  };
  const service = new StateReadService(repository, () => new Date(now));
  const query = { lat: 29.4, lng: 76.9, radiusM: 1000, limit: 1, cursor: null, search: "park" };
  const page = await service.nearby(query);
  if ((page.counts as { total: number }).total !== 24) throw new Error("counts must span the query");
  now.setMinutes(now.getMinutes() + 2);
  await service.nearby({ ...query, cursor: page.next_cursor as string });
  if (requested[0].asOf?.getTime() !== requested[1].asOf?.getTime()) {
    throw new Error("page boundary changed its freshness time");
  }
  for (const change of [{ search: "gate" }, { freshness: "stale" }, { radiusM: 2000 }]) {
    try {
      await service.nearby({ ...query, cursor: page.next_cursor as string, ...change });
      throw new Error("changed query accepted the old cursor");
    } catch (error) {
      if (!(error instanceof ApiFault) || error.code !== "INVALID_CURSOR") throw error;
    }
  }
});
