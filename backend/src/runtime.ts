import { PostgresExperienceService } from "./experience-service.ts";
import { createApp } from "./app.ts";
import { ClaimCoordinator } from "./claim-coordinator.ts";
import { EvidenceChallengeService } from "./evidence-challenge-service.ts";
import { EvidenceCommitService } from "./evidence-commit-service.ts";
import { SupabaseEvidenceObjectStorage } from "./evidence-object-storage.ts";
import { EvidenceUploadService } from "./evidence-upload-service.ts";
import { SupabaseAuthVerifier } from "./auth.ts";
import { PostgresClaimRepository } from "./postgres-claim-repository.ts";
import { PostgresEvidenceChallengeRepository } from "./postgres-evidence-challenge-repository.ts";
import { PostgresEvidenceCommitRepository } from "./postgres-evidence-commit-repository.ts";
import { PostgresEvidenceUploadRepository } from "./postgres-evidence-upload-repository.ts";
import { PostgresIdentityRepository } from "./postgres-identity-repository.ts";
import { OpportunityMatcher } from "./opportunity-matcher.ts";
import { PostgresOpportunityRepository } from "./postgres-opportunity-repository.ts";
import { PostgresPaymentStatusRepository } from "./postgres-payment-status-repository.ts";
import { PostgresReceiptRepository } from "./postgres-receipt-repository.ts";
import { PostgresRuntimeHealthRepository } from "./postgres-runtime-health-repository.ts";
import { PostgresRefreshRepository } from "./postgres-refresh-repository.ts";
import { PostgresStateRepository } from "./postgres-state-repository.ts";
import { PostgresStateProjectionRepository } from "./postgres-state-projection-repository.ts";
import { PaymentStatusService } from "./payment-status-service.ts";
import { ReceiptService } from "./receipt-service.ts";
import { RefreshCoordinator } from "./refresh-coordinator.ts";
import { SolanaRpcRefreshChainObserver } from "./solana-refresh-observer.ts";
import { PostgresVerificationRepository } from "./postgres-verification-repository.ts";
import { VerificationService } from "./verification-service.ts";
import { StateProjectionService } from "./state-projection-service.ts";
import { RuntimeReadinessProbe } from "./runtime-health.ts";
import { withStructuredRequestLogging } from "./structured-log.ts";

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing required environment variable ${name}`);
  return value;
}

function requiredSecretEnv(name: string): string {
  const value = requiredEnv(name);
  if (value.length < 32) {
    throw new Error(`environment variable ${name} must contain at least 32 characters`);
  }
  return value;
}

function requiredPositiveIntegerEnv(name: string): number {
  const raw = requiredEnv(name);
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`environment variable ${name} must be a positive integer`);
  }
  return value;
}

function optionalPositiveIntegerEnv(name: string, fallback: number): number {
  const raw = Deno.env.get(name)?.trim();
  if (!raw) return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`environment variable ${name} must be a positive integer`);
  }
  return value;
}

export function createProductionHandler(): (request: Request) => Promise<Response> {
  const supabaseUrl = requiredEnv("SUPABASE_URL");
  const authVerifier = new SupabaseAuthVerifier(
    supabaseUrl,
    requiredEnv("SUPABASE_ANON_KEY"),
  );
  const connectionString = requiredEnv("SUPABASE_DB_URL");
  const identityRepository = new PostgresIdentityRepository(connectionString);
  const stateRepository = new PostgresStateRepository(connectionString);
  const refreshRepository = new PostgresRefreshRepository(connectionString);
  const paymentStatusRepository = new PostgresPaymentStatusRepository(connectionString);
  const receiptRepository = new PostgresReceiptRepository(connectionString);
  const runtimeHealthRepository = new PostgresRuntimeHealthRepository(connectionString);
  const claimRepository = new PostgresClaimRepository(connectionString);
  const evidenceChallengeRepository = new PostgresEvidenceChallengeRepository(
    connectionString,
  );
  const evidenceUploadRepository = new PostgresEvidenceUploadRepository(
    connectionString,
  );
  const evidenceCommitRepository = new PostgresEvidenceCommitRepository(
    connectionString,
  );
  const verificationRepository = new PostgresVerificationRepository(
    connectionString,
  );
  const stateProjectionRepository = new PostgresStateProjectionRepository(
    connectionString,
  );
  const opportunityRepository = new PostgresOpportunityRepository(connectionString);
  const opportunityMatcher = new OpportunityMatcher(opportunityRepository);
  const chainObserver = new SolanaRpcRefreshChainObserver(
    requiredEnv("NOW_SOLANA_RPC_URL"),
  );
  const cluster = requiredEnv("NOW_SOLANA_CLUSTER");
  const refreshCoordinator = new RefreshCoordinator(
    refreshRepository,
    stateRepository,
    identityRepository,
    chainObserver,
    {
      cluster,
      rewardMint: requiredEnv("NOW_REWARD_MINT"),
      refreshLifetimeSeconds: requiredPositiveIntegerEnv(
        "NOW_REFRESH_LIFETIME_SECONDS",
      ),
      evidenceLeadSeconds: requiredPositiveIntegerEnv(
        "NOW_EVIDENCE_LEAD_SECONDS",
      ),
    },
  );
  const claimCoordinator = new ClaimCoordinator(
    claimRepository,
    identityRepository,
    chainObserver,
    {
      cluster,
      claimDurationSeconds: optionalPositiveIntegerEnv(
        "NOW_CLAIM_DURATION_SECONDS",
        180,
      ),
    },
  );
  const evidenceChallengeService = new EvidenceChallengeService(
    evidenceChallengeRepository,
  );
  const evidenceObjectStorage = new SupabaseEvidenceObjectStorage(
    supabaseUrl,
    requiredEnv("SUPABASE_SERVICE_ROLE_KEY"),
    requiredEnv("NOW_EVIDENCE_STORAGE_BUCKET"),
  );
  const evidenceUploadService = new EvidenceUploadService(
    evidenceUploadRepository,
    evidenceObjectStorage,
  );
  const evidenceCommitService = new EvidenceCommitService(
    evidenceCommitRepository,
    evidenceObjectStorage,
    optionalPositiveIntegerEnv("NOW_EVIDENCE_MAX_BYTES", 10_485_760),
  );
  const verificationService = new VerificationService(
    verificationRepository,
  );
  const stateProjectionService = new StateProjectionService(
    stateProjectionRepository,
  );
  const paymentStatusService = new PaymentStatusService(paymentStatusRepository);
  const receiptService = new ReceiptService(receiptRepository);
  const app = createApp({
    experienceService: new PostgresExperienceService(connectionString, { url: supabaseUrl, serviceKey: requiredEnv("SUPABASE_SERVICE_ROLE_KEY"), evidenceBucket: requiredEnv("NOW_EVIDENCE_STORAGE_BUCKET") }, Boolean(Deno.env.get("NOW_FCM_SERVICE_ACCOUNT_JSON"))),
    authVerifier,
    identityRepository,
    stateRepository,
    refreshCoordinator,
    opportunityMatcher,
    claimCoordinator,
    evidenceChallengeService,
    evidenceCommitService,
    evidenceUploadService,
    verificationService,
    stateProjectionService,
    paymentStatusService,
    receiptService,
    readinessProbe: new RuntimeReadinessProbe(runtimeHealthRepository),
    readinessToken: requiredSecretEnv("NOW_READY_TOKEN"),
  });
  return withStructuredRequestLogging(app, "now-api");
}
