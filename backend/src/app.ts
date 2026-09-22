import type { AuthVerifier } from "./auth.ts";
import { ApiFault } from "./errors.ts";
import {
  faultResponse,
  readJsonObject,
  requestId,
  requiredString,
  requiredUuid,
  successResponse,
} from "./http.ts";
import type { IdentityRepository } from "./identity-repository.ts";
import { WalletBindingService } from "./wallet-proof.ts";
import { handleRequest as handleHealthRequest } from "./health.ts";

export type AppDependencies = {
  authVerifier: AuthVerifier;
  identityRepository: IdentityRepository;
  now?: () => Date;
};

export function createApp(dependencies: AppDependencies): (request: Request) => Promise<Response> {
  const walletBinding = new WalletBindingService(dependencies.identityRepository, dependencies.now);

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
      const principal = await dependencies.authVerifier.verify(request);
      const actor = await dependencies.identityRepository.resolveActor(
        principal.authUserId,
        principal.principalType,
      );

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
