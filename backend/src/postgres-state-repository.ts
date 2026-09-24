import postgres from "npm:postgres@3.4.7";
import type {
  NearbyStateRecord,
  StateActiveRefreshSummary,
  StateDetailRecord,
  StateHistoryRecord,
  StateRepository,
  StateVerificationSummary,
} from "./state-repository.ts";

type DateLike = Date | string;

type NearbyRow = {
  state_id: string;
  title: string;
  question: string;
  state_type: NearbyStateRecord["stateType"];
  unit_code: string | null;
  current_value: unknown | null;
  observed_at: DateLike | null;
  aging_at: DateLike | null;
  fresh_until: DateLike | null;
  verification_class: NearbyStateRecord["verificationClass"];
  refresh_status: NearbyStateRecord["refreshStatus"];
  conflict_active: boolean;
  distance_m: number | string;
  revision: number | string;
};

type DetailRow = {
  state_id: string;
  version: number | string;
  canonical_key: string;
  title: string;
  question: string;
  state_type: StateDetailRecord["stateType"];
  unit_code: string | null;
  answer_schema: unknown;
  current_value: unknown | null;
  observed_at: DateLike | null;
  observation_earliest: DateLike | null;
  observation_latest: DateLike | null;
  aging_at: DateLike | null;
  fresh_until: DateLike | null;
  verification_class: StateDetailRecord["verificationClass"];
  conflict_active: boolean | null;
  revision: number | string;
  location_id: string;
  location_name: string;
  location_type: string;
  display_address: string | null;
  center_ewkb: Uint8Array;
  boundary_ewkb: Uint8Array | null;
  verification_status: StateVerificationSummary["status"] | null;
  verification_reason_codes: string[] | null;
  verification_evidence_count: number | string | null;
  refresh_id: string | null;
  refresh_status: StateActiveRefreshSummary["status"] | null;
  refresh_verification_class: StateActiveRefreshSummary["verificationClass"] | null;
  refresh_expires_at: DateLike | null;
  refresh_revision: number | string | null;
};

type HistoryRow = {
  history_id: string;
  state_id: string;
  state_version: number | string;
  value: unknown | null;
  observed_at: DateLike;
  aging_at: DateLike;
  fresh_until: DateLike;
  verification_class: StateHistoryRecord["verificationClass"];
  verification_status: StateHistoryRecord["verificationStatus"];
};

function date(value: DateLike): Date {
  return value instanceof Date ? value : new Date(value);
}

function dateOrNull(value: DateLike | null): Date | null {
  return value === null ? null : date(value);
}

function nearbyFromRow(row: NearbyRow): NearbyStateRecord {
  return {
    stateId: row.state_id,
    title: row.title,
    question: row.question,
    stateType: row.state_type,
    unitCode: row.unit_code,
    currentValue: row.current_value,
    observedAt: dateOrNull(row.observed_at),
    agingAt: dateOrNull(row.aging_at),
    freshUntil: dateOrNull(row.fresh_until),
    verificationClass: row.verification_class,
    refreshStatus: row.refresh_status,
    conflictActive: row.conflict_active,
    distanceM: Number(row.distance_m),
    revision: Number(row.revision),
  };
}

function detailFromRow(row: DetailRow): StateDetailRecord {
  const verification: StateVerificationSummary | null = row.verification_status === null ? null : {
    status: row.verification_status,
    reasonCodes: row.verification_reason_codes ?? [],
    evidenceCount: Number(row.verification_evidence_count ?? 0),
  };

  const activeRefresh: StateActiveRefreshSummary | null = row.refresh_id === null ||
      row.refresh_status === null ||
      row.refresh_verification_class === null ||
      row.refresh_expires_at === null ||
      row.refresh_revision === null
    ? null
    : {
      refreshId: row.refresh_id,
      status: row.refresh_status,
      verificationClass: row.refresh_verification_class,
      expiresAt: date(row.refresh_expires_at),
      revision: Number(row.refresh_revision),
    };

  return {
    stateId: row.state_id,
    version: Number(row.version),
    canonicalKey: row.canonical_key,
    title: row.title,
    question: row.question,
    stateType: row.state_type,
    unitCode: row.unit_code,
    answerSchema: row.answer_schema,
    currentValue: row.current_value,
    observedAt: dateOrNull(row.observed_at),
    observationEarliest: dateOrNull(row.observation_earliest),
    observationLatest: dateOrNull(row.observation_latest),
    agingAt: dateOrNull(row.aging_at),
    freshUntil: dateOrNull(row.fresh_until),
    verificationClass: row.verification_class,
    conflictActive: row.conflict_active ?? false,
    revision: Number(row.revision),
    location: {
      locationId: row.location_id,
      name: row.location_name,
      locationType: row.location_type,
      displayAddress: row.display_address,
      centerEwkb: new Uint8Array(row.center_ewkb),
      boundaryEwkb: row.boundary_ewkb === null ? null : new Uint8Array(row.boundary_ewkb),
    },
    verification,
    activeRefresh,
  };
}

function historyFromRow(row: HistoryRow): StateHistoryRecord {
  return {
    historyId: row.history_id,
    stateId: row.state_id,
    stateVersion: Number(row.state_version),
    value: row.value,
    observedAt: date(row.observed_at),
    agingAt: date(row.aging_at),
    freshUntil: date(row.fresh_until),
    verificationClass: row.verification_class,
    verificationStatus: row.verification_status,
  };
}

export class PostgresStateRepository implements StateRepository {
  private readonly sql: ReturnType<typeof postgres>;

  constructor(connectionString: string) {
    this.sql = postgres(connectionString, {
      max: 1,
      prepare: false,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }

  async listNearby(
    input: Parameters<StateRepository["listNearby"]>[0],
  ): Promise<NearbyStateRecord[]> {
    const rows = await this.sql`
      select
        state_id,
        title,
        question,
        state_type,
        unit_code,
        current_value,
        observed_at,
        aging_at,
        fresh_until,
        verification_class,
        refresh_status,
        conflict_active,
        distance_m,
        revision
      from app.query_nearby_states_v1(
        ${input.lat},
        ${input.lng},
        ${input.radiusM},
        ${input.limit},
        ${input.cursor?.distanceM ?? null},
        ${input.cursor?.stateId ?? null}::uuid
      )
    `;
    return rows.map((row) => nearbyFromRow(row as NearbyRow));
  }

  async getState(stateId: string): Promise<StateDetailRecord | null> {
    const rows = await this.sql`
      select
        sd.state_id,
        sd.version,
        sd.canonical_key,
        sd.title,
        sd.question,
        sd.state_type,
        sd.unit_code,
        sd.answer_schema,
        ls.current_value,
        ls.observed_at,
        ls.observation_earliest,
        ls.observation_latest,
        ls.aging_at,
        ls.fresh_until,
        ls.verification_class,
        ls.conflict_active,
        coalesce(ls.revision, sd.revision) as revision,
        l.location_id,
        l.name as location_name,
        l.location_type,
        l.display_address,
        extensions.st_asewkb(l.center::geometry) as center_ewkb,
        case
          when l.boundary is null then null
          else extensions.st_asewkb(l.boundary::geometry)
        end as boundary_ewkb,
        vr.status as verification_status,
        vr.reason_codes as verification_reason_codes,
        cardinality(vr.evidence_ids) as verification_evidence_count,
        ar.refresh_id,
        ar.status as refresh_status,
        ar.verification_class as refresh_verification_class,
        ar.refresh_expires_at,
        ar.revision as refresh_revision
      from app.state_definitions sd
      join app.locations l on l.location_id = sd.location_id
      left join app.live_states ls on ls.state_id = sd.state_id
      left join app.verification_results vr
        on vr.verification_result_id = ls.latest_verification_result_id
      left join lateral (
        select
          rr.refresh_id,
          rr.status,
          rr.verification_class,
          rr.refresh_expires_at,
          rr.revision
        from app.refresh_requests rr
        where rr.state_id = sd.state_id
          and rr.state_version = sd.version
          and rr.status not in ('COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED')
        order by rr.created_at desc, rr.refresh_id desc
        limit 1
      ) ar on true
      where sd.state_id = ${stateId}::uuid
        and sd.status = 'ACTIVE'
      limit 1
    `;
    return rows[0] ? detailFromRow(rows[0] as DetailRow) : null;
  }

  async listHistory(
    input: Parameters<StateRepository["listHistory"]>[0],
  ): Promise<StateHistoryRecord[]> {
    const rows = input.cursor === null
      ? await this.sql`
        select
          sh.history_id,
          sh.state_id,
          sh.state_version,
          sh.value,
          sh.observed_at,
          sh.aging_at,
          sh.fresh_until,
          sh.verification_class,
          vr.status as verification_status
        from app.state_history sh
        join app.state_definitions sd on sd.state_id = sh.state_id
        left join app.verification_results vr
          on vr.verification_result_id = sh.verification_result_id
        where sh.state_id = ${input.stateId}::uuid
          and sd.status = 'ACTIVE'
        order by sh.observed_at desc, sh.history_id desc
        limit ${input.limit}
      `
      : await this.sql`
        select
          sh.history_id,
          sh.state_id,
          sh.state_version,
          sh.value,
          sh.observed_at,
          sh.aging_at,
          sh.fresh_until,
          sh.verification_class,
          vr.status as verification_status
        from app.state_history sh
        join app.state_definitions sd on sd.state_id = sh.state_id
        left join app.verification_results vr
          on vr.verification_result_id = sh.verification_result_id
        where sh.state_id = ${input.stateId}::uuid
          and sd.status = 'ACTIVE'
          and (
            sh.observed_at < ${input.cursor.observedAt}
            or (
              sh.observed_at = ${input.cursor.observedAt}
              and sh.history_id < ${input.cursor.historyId}::uuid
            )
          )
        order by sh.observed_at desc, sh.history_id desc
        limit ${input.limit}
      `;

    return rows.map((row) => historyFromRow(row as HistoryRow));
  }
}
