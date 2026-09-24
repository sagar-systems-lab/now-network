import { ApiFault } from "./errors.ts";

export type AuthPrincipal = {
  authUserId: string;
  principalType: "SUPABASE_ANONYMOUS" | "SUPABASE_AUTH";
};

export interface AuthVerifier {
  verify(request: Request): Promise<AuthPrincipal>;
}

type FetchLike = typeof fetch;

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export class SupabaseAuthVerifier implements AuthVerifier {
  constructor(
    private readonly supabaseUrl: string,
    private readonly anonKey: string,
    private readonly fetchImpl: FetchLike = fetch,
  ) {}

  async verify(request: Request): Promise<AuthPrincipal> {
    const authorization = request.headers.get("authorization")?.trim() ?? "";
    if (!authorization.toLowerCase().startsWith("bearer ")) {
      throw new ApiFault(401, "AUTH_REQUIRED", "Authentication is required.");
    }

    const token = authorization.slice(7).trim();
    if (!token) throw new ApiFault(401, "AUTH_REQUIRED", "Authentication is required.");

    const response = await this.fetchImpl(`${this.supabaseUrl.replace(/\/$/, "")}/auth/v1/user`, {
      method: "GET",
      headers: {
        authorization: `Bearer ${token}`,
        apikey: this.anonKey,
        accept: "application/json",
      },
    });

    if (!response.ok) {
      throw new ApiFault(401, "AUTH_INVALID", "The session is invalid or expired.");
    }

    const payload: unknown = await response.json();
    if (!payload || typeof payload !== "object") {
      throw new ApiFault(401, "AUTH_INVALID", "The session is invalid or expired.");
    }

    const user = payload as Record<string, unknown>;
    const id = typeof user.id === "string" ? user.id : "";
    if (!UUID_PATTERN.test(id)) {
      throw new ApiFault(401, "AUTH_INVALID", "The session is invalid or expired.");
    }

    return {
      authUserId: id,
      principalType: user.is_anonymous === true ? "SUPABASE_ANONYMOUS" : "SUPABASE_AUTH",
    };
  }
}
