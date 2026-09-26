import { NowWorkerCoordinator } from "./now-worker-coordinator.ts";
import { PostgresReceiptRepository } from "./postgres-receipt-repository.ts";
import { PostgresRealtimeOutboxRepository } from "./postgres-realtime-outbox-repository.ts";
import { PostgresSettlementRepository } from "./postgres-settlement-repository.ts";
import { ReceiptCoordinator } from "./receipt-coordinator.ts";
import { RealtimePublisher } from "./realtime-outbox.ts";
import { SettlementCoordinator } from "./settlement-coordinator.ts";
import { settlementVerifierFromJson, SolanaSettlementClient } from "./solana-settlement-client.ts";
import { createSettlementWorkerHandler } from "./settlement-worker.ts";

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing required environment variable ${name}`);
  return value;
}

function optionalPositiveIntegerEnv(
  name: string,
  fallback: number,
  maximum: number,
): number {
  const raw = Deno.env.get(name)?.trim();
  if (!raw) return fallback;
  const value = Number(raw);
  if (
    !Number.isSafeInteger(value) ||
    value < 1 ||
    value > maximum
  ) {
    throw new Error(
      `environment variable ${name} must be an integer from 1 to ${maximum}`,
    );
  }
  return value;
}

export function createProductionSettlementWorkerHandler(): (
  request: Request,
) => Promise<Response> {
  const connectionString = requiredEnv("SUPABASE_DB_URL");
  const repository = new PostgresSettlementRepository(connectionString);
  const receiptRepository = new PostgresReceiptRepository(connectionString);
  const realtimeRepository = new PostgresRealtimeOutboxRepository(
    connectionString,
  );
  const verifier = settlementVerifierFromJson(
    requiredEnv("NOW_SETTLEMENT_VERIFIER_KEYPAIR_JSON"),
  );
  const chain = new SolanaSettlementClient(
    requiredEnv("NOW_SOLANA_RPC_URL"),
    verifier,
  );
  const settlementCoordinator = new SettlementCoordinator(
    repository,
    chain,
    () => new Date(),
    optionalPositiveIntegerEnv(
      "NOW_SETTLEMENT_RECONCILE_DELAY_MS",
      2_000,
      60_000,
    ),
  );
  const coordinator = new NowWorkerCoordinator(
    settlementCoordinator,
    new ReceiptCoordinator(receiptRepository),
    new RealtimePublisher(realtimeRepository),
  );

  return createSettlementWorkerHandler(
    coordinator,
    requiredEnv("NOW_WORKER_TOKEN"),
    optionalPositiveIntegerEnv("NOW_WORKER_BATCH_LIMIT", 8, 32),
  );
}
