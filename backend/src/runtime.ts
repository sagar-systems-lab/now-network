import { createApp } from "./app.ts";
import { ClaimCoordinator } from "./claim-coordinator.ts";
import { EvidenceChallengeService } from "./evidence-challenge-service.ts";
import { SupabaseEvidenceObjectStorage } from "./evidence-object-storage.ts";
import { EvidenceUploadService } from "./evidence-upload-service.ts";
import { SupabaseAuthVerifier } from "./auth.ts";
import { PostgresClaimRepository } from "./postgres-claim-repository.ts";
import { PostgresEvidenceChallengeRepository } from "./postgres-evidence-challenge-repository.ts";
import { PostgresEvidenceUploadRepository } from "./postgres-evidence-upload-repository.ts";
import { PostgresIdentityRepository } from "./postgres-identity-repository.ts";
import { OpportunityMatcher } from "./opportunity-matcher.ts";
import { PostgresOpportunityRepository } from "./postgres-opportunity-repository.ts";
import { PostgresRefreshRepository } from "./postgres-refresh-repository.ts";
import { PostgresStateRepository } from "./postgres-state-repository.ts";
import { RefreshCoordinator } from "./refresh-coordinator.ts";
import { SolanaRpcRefreshChainObserver } from "./solana-refresh-observer.ts";

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing required environment variable ${name}`);
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
  const claimRepository = new PostgresClaimRepository(connectionString);
  const evidenceChallengeRepository = new PostgresEvidenceChallengeRepository(
    connectionString,
  );
  const evidenceUploadRepository = new PostgresEvidenceUploadRepository(
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
  const evidenceUploadService = new EvidenceUploadService(
    evidenceUploadRepository,
    new SupabaseEvidenceObjectStorage(
      supabaseUrl,
      requiredEnv("SUPABASE_SERVICE_ROLE_KEY"),
      requiredEnv("NOW_EVIDENCE_STORAGE_BUCKET"),
    ),
  );
  return createApp({
    authVerifier,
    identityRepository,
    stateRepository,
    refreshCoordinator,
    opportunityMatcher,
    claimCoordinator,
    evidenceChallengeService,
    evidenceUploadService,
  });
}
