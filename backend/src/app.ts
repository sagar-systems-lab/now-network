import type { AuthVerifier } from "./auth.ts";
import type { ClaimCoordinator } from "./claim-coordinator.ts";
import type { EvidenceChallengeService } from "./evidence-challenge-service.ts";
import type { EvidenceCommitService } from "./evidence-commit-service.ts";
import type { EvidenceUploadService } from "./evidence-upload-service.ts";
import { ApiFault } from "./errors.ts";
import {
  faultResponse,
  optionalQueryInteger,
  readJsonObject,
  requestId,
  requiredQueryNumber,
  requiredString,
  requiredUuid,
  successResponse,
  UUID_PATTERN,
} from "./http.ts";
import type { IdentityRepository } from "./identity-repository.ts";
import {
  DEFAULT_OPPORTUNITY_LIMIT,
  MAX_OPPORTUNITY_LIMIT,
  MAX_OPPORTUNITY_RADIUS_M,
  OpportunityMatcher,
} from "./opportunity-matcher.ts";
import type { ReceiptService } from "./receipt-service.ts";
import type { RefreshCoordinator } from "./refresh-coordinator.ts";
import type { StateRepository } from "./state-repository.ts";
import type { StateProjectionService } from "./state-projection-service.ts";
import type { VerificationService } from "./verification-service.ts";
import {
  DEFAULT_HISTORY_LIMIT,
  DEFAULT_NEARBY_LIMIT,
  MAX_HISTORY_LIMIT,
  MAX_NEARBY_LIMIT,
  MAX_NEARBY_RADIUS_M,
  StateReadService,
} from "./state-read.ts";
import { WalletBindingService } from "./wallet-proof.ts";
import { handleRequest as handleHealthRequest } from "./health.ts";

export type AppDependencies = {
  authVerifier: AuthVerifier;
  identityRepository: IdentityRepository;
  stateRepository?: StateRepository;
  refreshCoordinator?: RefreshCoordinator;
  opportunityMatcher?: OpportunityMatcher;
  claimCoordinator?: ClaimCoordinator;
  evidenceChallengeService?: EvidenceChallengeService;
  evidenceCommitService?: EvidenceCommitService;
  evidenceUploadService?: EvidenceUploadService;
  verificationService?: VerificationService;
  stateProjectionService?: StateProjectionService;
  receiptService?: ReceiptService;
  now?: () => Date;
};

export function createApp(dependencies: AppDependencies): (request: Request) => Promise<Response> {
  const walletBinding = new WalletBindingService(dependencies.identityRepository, dependencies.now);
  const stateRead = dependencies.stateRepository
    ? new StateReadService(dependencies.stateRepository, dependencies.now)
    : null;

  function requireReceiptService(): ReceiptService {
    if (!dependencies.receiptService) {
      throw new ApiFault(
        503,
        "RECEIPT_UNAVAILABLE",
        "Receipts are temporarily unavailable.",
        true,
        1_000,
      );
    }
    return dependencies.receiptService;
  }

  function requireStateRead(): StateReadService {
    if (stateRead === null) {
      throw new ApiFault(
        503,
        "STATE_READ_UNAVAILABLE",
        "State reads are temporarily unavailable.",
        true,
        1_000,
      );
    }
    return stateRead;
  }

  function requireRefreshCoordinator(): RefreshCoordinator {
    if (!dependencies.refreshCoordinator) {
      throw new ApiFault(
        503,
        "REFRESH_COORDINATOR_UNAVAILABLE",
        "Refresh coordination is temporarily unavailable.",
        true,
        1_000,
      );
    }
    return dependencies.refreshCoordinator;
  }

  function requireClaimCoordinator(): ClaimCoordinator {
    if (!dependencies.claimCoordinator) {
      throw new ApiFault(
        503,
        "CLAIM_COORDINATOR_UNAVAILABLE",
        "Claim coordination is temporarily unavailable.",
        true,
        1_000,
      );
    }
    return dependencies.claimCoordinator;
  }

  function requireEvidenceChallengeService(): EvidenceChallengeService {
    if (!dependencies.evidenceChallengeService) {
      throw new ApiFault(
        503,
        "EVIDENCE_CHALLENGE_UNAVAILABLE",
        "Evidence challenge issuance is temporarily unavailable.",
        true,
        1_000,
      );
    }
    return dependencies.evidenceChallengeService;
  }

  function requireEvidenceCommitService(): EvidenceCommitService {
    if (!dependencies.evidenceCommitService) {
      throw new ApiFault(
        503,
        "EVIDENCE_COMMIT_UNAVAILABLE",
        "Evidence commit is temporarily unavailable.",
        true,
        1_000,
      );
    }
    return dependencies.evidenceCommitService;
  }

  function requireEvidenceUploadService(): EvidenceUploadService {
    if (!dependencies.evidenceUploadService) {
      throw new ApiFault(
        503,
        "EVIDENCE_UPLOAD_UNAVAILABLE",
        "Evidence upload authorization is temporarily unavailable.",
        true,
        1_000,
      );
    }
    return dependencies.evidenceUploadService;
  }

  function requireVerificationService(): VerificationService {
    if (!dependencies.verificationService) {
      throw new ApiFault(
        503,
        "VERIFICATION_UNAVAILABLE",
        "Verification is temporarily unavailable.",
        true,
        1_000,
      );
    }
    return dependencies.verificationService;
  }

  function requireStateProjectionService(): StateProjectionService {
    if (!dependencies.stateProjectionService) {
      throw new ApiFault(
        503,
        "STATE_PROJECTION_UNAVAILABLE",
        "State projection is temporarily unavailable.",
        true,
        1_000,
      );
    }
    return dependencies.stateProjectionService;
  }

  function requireOpportunityMatcher(): OpportunityMatcher {
    if (!dependencies.opportunityMatcher) {
      throw new ApiFault(
        503,
        "OPPORTUNITY_MATCHER_UNAVAILABLE",
        "Opportunity discovery is temporarily unavailable.",
        true,
        1_000,
      );
    }
    return dependencies.opportunityMatcher;
  }

  return async (request: Request): Promise<Response> => {
    const url = new URL(request.url);
    if (
      request.method === "GET" &&
      (url.pathname === "/health" || url.pathname.endsWith("/health"))
    ) {
      return handleHealthRequest(request);
    }

    const id = requestId(request);
    try {
      if (request.method === "GET" && routeMatches(url.pathname, "/v1/states/nearby")) {
        const data = await requireStateRead().nearby({
          lat: requiredQueryNumber(url, "lat", -90, 90),
          lng: requiredQueryNumber(url, "lng", -180, 180),
          radiusM: requiredQueryNumber(url, "radius_m", 1, MAX_NEARBY_RADIUS_M, true),
          limit: optionalQueryInteger(url, "limit", DEFAULT_NEARBY_LIMIT, 1, MAX_NEARBY_LIMIT),
          cursor: url.searchParams.get("cursor"),
        });
        return successResponse(id, data);
      }

      const stateRoute = matchStateRoute(url.pathname);
      if (request.method === "GET" && stateRoute !== null) {
        if (stateRoute.history) {
          const data = await requireStateRead().history({
            stateId: stateRoute.stateId,
            limit: optionalQueryInteger(
              url,
              "limit",
              DEFAULT_HISTORY_LIMIT,
              1,
              MAX_HISTORY_LIMIT,
            ),
            cursor: url.searchParams.get("cursor"),
          });
          return successResponse(id, data);
        }

        return successResponse(id, await requireStateRead().detail(stateRoute.stateId));
      }

      const principal = await dependencies.authVerifier.verify(request);
      const actor = await dependencies.identityRepository.resolveActor(
        principal.authUserId,
        principal.principalType,
      );

      if (
        request.method === "GET" &&
        routeMatches(url.pathname, "/v1/opportunities/nearby")
      ) {
        const data = await requireOpportunityMatcher().nearby(actor, {
          lat: requiredQueryNumber(url, "lat", -90, 90),
          lng: requiredQueryNumber(url, "lng", -180, 180),
          radiusM: requiredQueryNumber(
            url,
            "radius_m",
            1,
            MAX_OPPORTUNITY_RADIUS_M,
            true,
          ),
          limit: optionalQueryInteger(
            url,
            "limit",
            DEFAULT_OPPORTUNITY_LIMIT,
            1,
            MAX_OPPORTUNITY_LIMIT,
          ),
          cursor: url.searchParams.get("cursor"),
        });
        return successResponse(id, data);
      }

      const opportunityClaimRoute = matchOpportunityClaimRoute(url.pathname);
      if (request.method === "POST" && opportunityClaimRoute !== null) {
        const body = await readJsonObject(request);
        const result = await requireClaimCoordinator().prepare({
          actor,
          refreshId: opportunityClaimRoute.refreshId,
          walletBindingId: requiredUuid(body, "wallet_binding_id"),
          idempotencyKey: request.headers.get("idempotency-key")?.trim() ?? "",
        });
        return successResponse(id, result.data, result.status);
      }

      const claimRoute = matchClaimRoute(url.pathname);
      if (claimRoute !== null) {
        if (request.method === "GET" && claimRoute.action === "detail") {
          const data = await requireClaimCoordinator().get(actor, claimRoute.acceptanceId);
          return successResponse(id, data);
        }
        if (request.method === "POST" && claimRoute.action === "observe") {
          const body = await readJsonObject(request);
          const data = await requireClaimCoordinator().observe({
            actor,
            acceptanceId: claimRoute.acceptanceId,
            signature: requiredString(body, "signature", 128),
          });
          return successResponse(id, data);
        }
        if (request.method === "POST" && claimRoute.action === "challenge") {
          const data = await requireEvidenceChallengeService().issue(
            actor,
            claimRoute.acceptanceId,
          );
          return successResponse(id, data, 201);
        }
      }

      const evidenceChallengeRoute = matchEvidenceChallengeRoute(url.pathname);
      if (
        request.method === "POST" &&
        evidenceChallengeRoute !== null &&
        evidenceChallengeRoute.action === "upload"
      ) {
        const body = await readJsonObject(request);
        const data = await requireEvidenceUploadService().authorize({
          actor,
          challengeId: evidenceChallengeRoute.challengeId,
          nonce: requiredString(body, "nonce", 128),
          mediaMime: requiredString(body, "media_mime", 128),
        });
        return successResponse(id, data, 201);
      }

      const evidenceRoute = matchEvidenceRoute(url.pathname);
      if (
        request.method === "POST" &&
        evidenceRoute !== null &&
        evidenceRoute.action === "commit"
      ) {
        const body = await readJsonObject(request);
        const result = await requireEvidenceCommitService().commit({
          actor,
          evidenceId: evidenceRoute.evidenceId,
          body,
          idempotencyKey: request.headers.get("idempotency-key")?.trim() ?? "",
        });
        return successResponse(id, result.data, result.status);
      }

      const opportunityRoute = matchOpportunityRoute(url.pathname);
      if (request.method === "GET" && opportunityRoute !== null) {
        const data = await requireOpportunityMatcher().detail(
          actor,
          opportunityRoute.refreshId,
        );
        return successResponse(id, data);
      }

      if (request.method === "POST" && routeMatches(url.pathname, "/v1/refreshes")) {
        const body = await readJsonObject(request);
        const result = await requireRefreshCoordinator().create({
          actor,
          stateId: requiredUuid(body, "state_id"),
          walletBindingId: requiredUuid(body, "wallet_binding_id"),
          fundingTargetAtomic: requiredString(body, "funding_target_atomic", 32),
          idempotencyKey: request.headers.get("idempotency-key")?.trim() ?? "",
        });
        return successResponse(id, result.data, result.status);
      }

      const refreshRoute = matchRefreshRoute(url.pathname);
      if (refreshRoute !== null) {
        if (request.method === "GET" && refreshRoute.action === "detail") {
          const data = await requireRefreshCoordinator().get(actor, refreshRoute.refreshId);
          return successResponse(id, data);
        }

        if (request.method === "GET" && refreshRoute.action === "receipt") {
          const data = await requireReceiptService().get(
            actor,
            refreshRoute.refreshId,
          );
          return successResponse(id, data);
        }

        if (request.method === "POST" && refreshRoute.action === "funding-intent") {
          const data = await requireRefreshCoordinator().fundingIntent({
            actor,
            refreshId: refreshRoute.refreshId,
            idempotencyKey: request.headers.get("idempotency-key")?.trim() ?? "",
          });
          return successResponse(id, data);
        }

        if (request.method === "POST" && refreshRoute.action === "funding-observe") {
          const body = await readJsonObject(request);
          const data = await requireRefreshCoordinator().observeFunding({
            actor,
            refreshId: refreshRoute.refreshId,
            signature: requiredString(body, "signature", 128),
            idempotencyKey: request.headers.get("idempotency-key")?.trim() ?? "",
          });
          return successResponse(id, data);
        }

        if (request.method === "POST" && refreshRoute.action === "verify") {
          const result = await requireVerificationService().verify(
            actor,
            refreshRoute.refreshId,
          );
          if (result.data.result !== "VERIFIED") {
            return successResponse(id, result.data, result.status);
          }

          const verificationResultId = result.data.verification_result_id;
          if (
            typeof verificationResultId !== "string" ||
            !UUID_PATTERN.test(verificationResultId)
          ) {
            throw new Error("verification result did not expose a valid identity");
          }
          const projection = await requireStateProjectionService().project(
            refreshRoute.refreshId,
            verificationResultId,
          );
          return successResponse(
            id,
            { ...result.data, state_projection: projection },
            result.status,
          );
        }
      }

      if (request.method === "GET" && routeMatches(url.pathname, "/v1/me")) {
        const bindings = await dependencies.identityRepository.listWalletBindings(actor.actorId);
        return successResponse(
          id,
          {
            actor_id: actor.actorId,
            status: actor.status,
            wallet_bindings: bindings.map((binding) => ({
              wallet_binding_id: binding.walletBindingId,
              wallet_address: binding.walletAddress,
              cluster: binding.cluster,
              status: binding.status,
              revision: binding.revision,
            })),
          },
          200,
          actor.revision,
        );
      }

      if (
        request.method === "POST" &&
        routeMatches(url.pathname, "/v1/wallet-bindings/challenge")
      ) {
        const body = await readJsonObject(request);
        const challenge = await walletBinding.issue(
          actor,
          principal.authUserId,
          requiredString(body, "wallet_address", 64),
          requiredString(body, "cluster", 32),
        );
        return successResponse(id, challenge, 201);
      }

      if (
        request.method === "POST" &&
        routeMatches(url.pathname, "/v1/wallet-bindings/verify")
      ) {
        const body = await readJsonObject(request);
        const result = await walletBinding.verify(
          actor,
          principal.authUserId,
          requiredUuid(body, "challenge_id"),
          requiredString(body, "signature", 256),
        );
        return successResponse(
          id,
          {
            actor_id: result.actor.actorId,
            recovered: result.recovered,
            wallet_binding: {
              wallet_binding_id: result.binding.walletBindingId,
              wallet_address: result.binding.walletAddress,
              cluster: result.binding.cluster,
              status: result.binding.status,
              revision: result.binding.revision,
            },
          },
          200,
          result.actor.revision,
        );
      }

      throw new ApiFault(404, "NOT_FOUND", "The requested endpoint does not exist.");
    } catch (error) {
      return faultResponse(id, error);
    }
  };
}

function routeMatches(pathname: string, route: string): boolean {
  return pathname === route || pathname.endsWith(route);
}

function matchStateRoute(
  pathname: string,
): { stateId: string; history: boolean } | null {
  const match = pathname.match(/(?:^|\/)v1\/states\/([^/]+)(\/history)?$/u);
  if (!match) return null;
  if (!UUID_PATTERN.test(match[1])) {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid state_id.");
  }
  return { stateId: match[1], history: match[2] === "/history" };
}

function matchRefreshRoute(
  pathname: string,
): {
  refreshId: string;
  action:
    | "detail"
    | "funding-intent"
    | "funding-observe"
    | "verify"
    | "receipt";
} | null {
  const match = pathname.match(
    /(?:^|\/)v1\/refreshes\/([^/]+)(?:\/(funding-intent|funding-observe|verify|receipt))?$/u,
  );
  if (!match) return null;
  if (!UUID_PATTERN.test(match[1])) {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid refresh_id.");
  }
  return {
    refreshId: match[1],
    action: match[2] === "funding-intent"
      ? "funding-intent"
      : match[2] === "funding-observe"
      ? "funding-observe"
      : match[2] === "verify"
      ? "verify"
      : match[2] === "receipt"
      ? "receipt"
      : "detail",
  };
}

function matchOpportunityRoute(pathname: string): { refreshId: string } | null {
  const match = pathname.match(/(?:^|\/)v1\/opportunities\/([^/]+)$/u);
  if (!match || match[1] === "nearby") return null;
  if (!UUID_PATTERN.test(match[1])) {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid refresh_id.");
  }
  return { refreshId: match[1] };
}

function matchOpportunityClaimRoute(pathname: string): { refreshId: string } | null {
  const match = pathname.match(/(?:^|\/)v1\/opportunities\/([^/]+)\/claim$/u);
  if (!match) return null;
  if (!UUID_PATTERN.test(match[1])) {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid refresh_id.");
  }
  return { refreshId: match[1] };
}

function matchClaimRoute(
  pathname: string,
): { acceptanceId: string; action: "detail" | "observe" | "challenge" } | null {
  const match = pathname.match(
    /(?:^|\/)v1\/claims\/([^/]+)(?:\/(observe|challenge))?$/u,
  );
  if (!match) return null;
  if (!UUID_PATTERN.test(match[1])) {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid acceptance_id.");
  }
  return {
    acceptanceId: match[1],
    action: match[2] === "observe" ? "observe" : match[2] === "challenge" ? "challenge" : "detail",
  };
}

function matchEvidenceRoute(
  pathname: string,
): { evidenceId: string; action: "commit" } | null {
  const match = pathname.match(
    /(?:^|\/)v1\/evidence\/([^/]+)\/(commit)$/u,
  );
  if (!match) return null;
  if (!UUID_PATTERN.test(match[1])) {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid evidence_id.");
  }
  return { evidenceId: match[1], action: "commit" };
}

function matchEvidenceChallengeRoute(
  pathname: string,
): { challengeId: string; action: "upload" } | null {
  const match = pathname.match(
    /(?:^|\/)v1\/evidence-challenges\/([^/]+)\/(upload)$/u,
  );
  if (!match) return null;
  if (!UUID_PATTERN.test(match[1])) {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid challenge_id.");
  }
  return { challengeId: match[1], action: "upload" };
}
