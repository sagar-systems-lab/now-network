import {
  BACKEND_OUTBOX_STATUSES,
  EVENT_VISIBILITIES,
  EVIDENCE_STATUSES,
  RECEIPT_STATUSES,
  REFRESH_STATUSES,
  REFUND_STATUSES,
  SETTLEMENT_STATUSES,
  STATE_TYPES,
  VERIFICATION_STATUSES,
} from "../../packages/contracts/src/lifecycle.ts";

const migrationsDir = new URL("../../supabase/migrations/", import.meta.url);

const expectedMigrations = [
  "20260920_001_identity.sql",
  "20260920_002_state_catalog.sql",
  "20260920_003_state_projection.sql",
  "20260920_004_refresh_requests.sql",
  "20260920_005_refresh_contributions.sql",
  "20260920_006_refresh_acceptances.sql",
  "20260920_007_evidence_challenges.sql",
  "20260920_008_evidence.sql",
  "20260920_009_verification.sql",
  "20260920_010_settlement.sql",
  "20260920_011_receipts.sql",
  "20260920_012_idempotency.sql",
  "20260920_013_audit.sql",
  "20260920_014_security.sql",
  "20260920_015_outbox.sql",
  "20260920_016_financial_replay_guards.sql",
  "20260922_017_wallet_binding_challenges.sql",
  "20260924_018_state_read_queries.sql",
  "20260924_019_refresh_coordination.sql",
  "20260925_020_opportunity_queries.sql",
  "20260925_021_claim_orchestration.sql",
  "20260925_022_evidence_challenge_hardening.sql",
  "20260925_023_evidence_signed_upload.sql",
  "20260925_024_evidence_commit.sql",
  "20260925_025_verification_hardening.sql",
  "20260925_026_state_projection_hardening.sql",
  "20260925_027_settlement_orchestration.sql",
  "20260926_028_receipts_realtime.sql",
  "20260926_029_runtime_health.sql",
  "20260930_030_function_search_path_hardening.sql",
  "20261003152110_native_experience.sql",
  "20261003161302_experience_notification_events.sql",
  "20261004164414_dynamic_ask_coverage.sql",
] as const;

async function readMigration(name: string): Promise<string> {
  return await Deno.readTextFile(new URL(name, migrationsDir));
}

async function allSql(): Promise<string> {
  return (await Promise.all(expectedMigrations.map(readMigration))).join("\n");
}

function assertIncludes(haystack: string, needle: string): void {
  if (!haystack.toLowerCase().includes(needle.toLowerCase())) {
    throw new Error(`missing schema invariant: ${needle}`);
  }
}

function parseEnum(sql: string, typeName: string): string[] {
  const pattern = new RegExp(
    `create\\s+type\\s+app\\.${typeName}\\s+as\\s+enum\\s*\\(([^;]+?)\\)`,
    "is",
  );
  const match = sql.match(pattern);
  if (!match) throw new Error(`missing enum app.${typeName}`);
  return [...match[1].matchAll(/'([^']+)'/g)].map((item) => item[1]);
}

function assertArrayEquals(
  actual: readonly string[],
  expected: readonly string[],
  name: string,
): void {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${name} drift: ${JSON.stringify(actual)} != ${JSON.stringify(expected)}`);
  }
}

Deno.test("database migration sequence is explicit and complete", async () => {
  const names = [];
  for await (const entry of Deno.readDir(migrationsDir)) {
    if (entry.isFile && entry.name.endsWith(".sql")) names.push(entry.name);
  }
  names.sort();
  assertArrayEquals(names, expectedMigrations, "migration order");
});

Deno.test("database lifecycle enums match shared contracts", async () => {
  const sql = await allSql();
  assertArrayEquals(parseEnum(sql, "state_type"), STATE_TYPES, "state_type");
  assertArrayEquals(parseEnum(sql, "refresh_status"), REFRESH_STATUSES, "refresh_status");
  assertArrayEquals(parseEnum(sql, "evidence_status"), EVIDENCE_STATUSES, "evidence_status");
  assertArrayEquals(
    parseEnum(sql, "verification_status"),
    VERIFICATION_STATUSES,
    "verification_status",
  );
  assertArrayEquals(parseEnum(sql, "settlement_status"), SETTLEMENT_STATUSES, "settlement_status");
  assertArrayEquals(parseEnum(sql, "refund_status"), REFUND_STATUSES, "refund_status");
  assertArrayEquals(parseEnum(sql, "receipt_status"), RECEIPT_STATUSES, "receipt_status");
  assertArrayEquals(parseEnum(sql, "event_visibility"), EVENT_VISIBILITIES, "event_visibility");

  for (const status of BACKEND_OUTBOX_STATUSES) {
    assertIncludes(sql, `'${status}'`);
  }
});

Deno.test("authoritative tables enable row-level security immediately", async () => {
  const sql = await allSql();
  const tables = [
    "app.contributor_presence",
    "app.ask_rate_limits",
    "app.actors",
    "app.actor_auth_principals",
    "app.wallet_bindings",
    "app.wallet_binding_challenges",
    "app.locations",
    "app.state_definitions",
    "app.live_states",
    "app.state_history",
    "app.refresh_requests",
    "app.refresh_contributions",
    "app.refresh_acceptances",
    "app.evidence_challenges",
    "app.evidence_packets",
    "app.evidence_location_samples",
    "app.verification_results",
    "app.settlement_operations",
    "app.refund_operations",
    "app.receipts",
    "app.receipt_annotations",
    "app.idempotency_records",
    "app.domain_events",
    "app.security_events",
    "app.outbox_events",
    "app.runtime_health",
    "app.actor_profiles",
    "app.actor_preferences",
    "app.installations",
    "app.notifications",
    "app.notification_deliveries",
    "public.realtime_events_v1",
  ];

  for (const table of tables) {
    assertIncludes(sql, `alter table ${table} enable row level security`);
  }
});

Deno.test("database constraints encode critical correctness boundaries", async () => {
  const sql = await allSql();
  for (
    const invariant of [
      "unique (cluster, wallet_address)",
      "purpose = 'wallet_binding'",
      "domain = 'NOW Network'",
      "octet_length(message_sha256) = 32",
      "octet_length(nonce_hash) = 32",
      "unique (canonical_key, version)",
      "unique (refresh_id, actor_id)",
      "challenge_id uuid not null unique",
      "unique (refresh_id, evidence_set_revision, policy_version)",
      "operation_id uuid not null unique",
      "unique (refresh_id, operation_id)",
      "settlement_operations_refresh_once_uk",
      "refund_operations_contribution_once_uk",
      "refresh_id uuid not null unique",
      "check (revision > 0)",
      "evidence_deadline < refresh_expires_at",
      "refresh_requests_coordinator_v1_shape",
      "evidence_challenges_nonce_hash_sha256",
      "evidence_challenges_status_timestamps",
      "evidence_challenges_upload_reservation_shape",
      "evidence_challenges_reserved_evidence_shape",
      "evidence_packets_digest_lengths",
      "verification_results_digest_lengths",
      "verification_results_terminal_timestamps",
      "live_states_value_digest_length",
      "state_history_value_digest_length",
      "settlement_operations_execution_hash_32_ck",
      "settlement_operations_recipient_mask_ck",
      "settlement_operations_recipient_wallets_ck",
      "settlement_operations_chain_refresh_id_32_ck",
      "settlement_operations_locked_reward_ck",
      "settlement_operations_attempt_shape_ck",
      "receipts_verification_digest_32_ck",
      "receipts_operation_hash_32_ck",
      "receipts_receipt_digest_32_ck",
      "receipts_final_shape_ck",
      "outbox_dedupe_key_nonempty_ck",
      "outbox_audience_shape_ck",
      "funding_target_atomic > 0",
      "funding_target_atomic <= 18446744073709551615",
      "octet_length(intent_core_hash) = 32",
    ]
  ) {
    assertIncludes(sql, invariant);
  }
});

Deno.test("database indexes cover geospatial and recovery paths", async () => {
  const sql = await allSql();
  for (
    const indexName of [
      "locations_center_gist",
      "locations_boundary_gist",
      "refresh_requests_active_idx",
      "evidence_challenges_one_issued_per_acceptance",
      "evidence_packets_media_sha256_idx",
      "settlement_operations_active_idx",
      "settlement_operations_operation_hash_uk",
      "domain_events_entity_sequence_idx",
      "domain_events_entity_time_idx",
      "outbox_events_pending_idx",
      "wallet_binding_challenges_one_issued_per_principal",
      "wallet_binding_challenges_expiry_idx",
      "refresh_requests_chain_refresh_id_idx",
      "refresh_requests_chain_refresh_address_idx",
      "refresh_requests_funding_operation_idx",
      "refresh_contributions_chain_signature_idx",
      "refresh_requests_requester_status_idx",
      "refresh_acceptances_claim_duration_seconds_bounds",
      "refresh_acceptances_chain_signature_uq",
      "refresh_acceptances_active_reservation_idx",
      "evidence_challenges_nonce_hash_uq",
      "evidence_challenges_expiry_idx",
      "evidence_challenges_upload_object_key_uq",
      "evidence_challenges_upload_issued_idx",
      "evidence_challenges_reserved_evidence_id_uq",
      "evidence_packets_commit_request_actor_uq",
      "verification_results_canonical_digest_uq",
      "state_history_verification_result_uq",
      "settlement_operations_verification_result_uq",
      "settlement_operations_reconcile_idx",
      "settlement_operations_chain_signature_uq",
      "receipts_settlement_uq",
      "receipts_verification_result_uq",
      "outbox_events_dedupe_key_uq",
      "realtime_events_source_outbox_uq",
      "runtime_health_seen_idx",
    ]
  ) {
    assertIncludes(sql, indexName);
  }
});

Deno.test("runtime health exposes bounded backlog diagnostics", async () => {
  const sql = await allSql();
  for (
    const invariant of [
      "runtime_backlog_snapshot_v1",
      "pending_verification_count",
      "pending_settlement_count",
      "pending_outbox_count",
    ]
  ) {
    assertIncludes(sql, invariant);
  }
});

Deno.test("nearby state reads are PostGIS-backed and bounded", async () => {
  const sql = await allSql();
  for (
    const invariant of [
      "query_nearby_states_v1",
      "extensions.st_dwithin",
      "extensions.st_distance",
      "p_radius_m between 1 and 50000",
      "p_limit between 1 and 51",
    ]
  ) {
    assertIncludes(sql, invariant);
  }
});

Deno.test("nearby opportunity reads are PostGIS-backed and capacity-bounded", async () => {
  const sql = await allSql();
  for (
    const invariant of [
      "query_nearby_opportunities_v1",
      "rr.status = 'AVAILABLE'",
      "rr.status in ('AVAILABLE', 'ADDITIONAL_VERIFICATION')",
      "rr.refresh_expires_at > now()",
      "rr.requester_actor_id <> p_actor_id",
      "coalesce(claims.active_claims, 0) < rr.max_witnesses",
      "extensions.st_dwithin",
      "extensions.st_distance",
      "p_radius_m between 1 and 50000",
      "p_limit between 1 and 51",
    ]
  ) {
    assertIncludes(sql, invariant);
  }
});

Deno.test("migrations remain forward-only and fail closed", async () => {
  const sql = (await allSql()).toLowerCase();
  for (
    const forbidden of [
      "drop table",
      "drop schema",
      "truncate ",
      "disable row level security",
      "fallbacktodestructivemigration",
    ]
  ) {
    if (sql.includes(forbidden)) {
      throw new Error(`forbidden migration operation: ${forbidden}`);
    }
  }
});
