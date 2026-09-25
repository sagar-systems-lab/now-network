import type { AuthVerifier } from "./auth.ts";
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
import type { RefreshCoordinator } from "./refresh-coordinator.ts";
import type { StateRepository } from "./state-repository.ts";
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
  now?: () => Date;
};

export function createApp(dependencies: AppDependencies): (request: Request) => Promise<Response> {
  const walletBinding = new WalletBindingService(dependencies.identityRepository, dependencies.now);
  const stateRead = dependencies.stateRepository
    ? new StateReadService(dependencies.stateRepository, dependencies.now)
    : null;

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
  action: "detail" | "funding-intent" | "funding-observe";
} | null {
  const match = pathname.match(
    /(?:^|\/)v1\/refreshes\/([^/]+)(?:\/(funding-intent|funding-observe))?$/u,
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
