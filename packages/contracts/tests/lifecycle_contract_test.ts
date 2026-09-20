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
} from "../src/lifecycle.ts";

const schemaUrl = new URL("../schema/lifecycle-v1.schema.json", import.meta.url);

function assertSame(actual: readonly string[], expected: unknown, name: string): void {
  if (!Array.isArray(expected)) throw new Error(`${name} schema enum is missing`);
  if (JSON.stringify(actual) !== JSON.stringify(expected.map(String))) {
    throw new Error(`${name} TypeScript values do not match JSON Schema`);
  }
}

Deno.test("lifecycle constants match JSON Schema", async () => {
  const schema = JSON.parse(await Deno.readTextFile(schemaUrl)) as {
    $defs: Record<string, { enum?: unknown }>;
  };

  assertSame(STATE_TYPES, schema.$defs.StateType.enum, "StateType");
  assertSame(REFRESH_STATUSES, schema.$defs.RefreshStatus.enum, "RefreshStatus");
  assertSame(EVIDENCE_STATUSES, schema.$defs.EvidenceStatus.enum, "EvidenceStatus");
  assertSame(VERIFICATION_STATUSES, schema.$defs.VerificationStatus.enum, "VerificationStatus");
  assertSame(SETTLEMENT_STATUSES, schema.$defs.SettlementStatus.enum, "SettlementStatus");
  assertSame(REFUND_STATUSES, schema.$defs.RefundStatus.enum, "RefundStatus");
  assertSame(RECEIPT_STATUSES, schema.$defs.ReceiptStatus.enum, "ReceiptStatus");
  assertSame(BACKEND_OUTBOX_STATUSES, schema.$defs.BackendOutboxStatus.enum, "BackendOutboxStatus");
  assertSame(EVENT_VISIBILITIES, schema.$defs.EventVisibility.enum, "EventVisibility");
});
