import { createApp } from "./app.ts";
import { SupabaseAuthVerifier } from "./auth.ts";
import { PostgresIdentityRepository } from "./postgres-identity-repository.ts";
import { PostgresStateRepository } from "./postgres-state-repository.ts";

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing required environment variable ${name}`);
  return value;
}

export function createProductionHandler(): (request: Request) => Promise<Response> {
  const authVerifier = new SupabaseAuthVerifier(
    requiredEnv("SUPABASE_URL"),
    requiredEnv("SUPABASE_ANON_KEY"),
  );
  const connectionString = requiredEnv("SUPABASE_DB_URL");
  const identityRepository = new PostgresIdentityRepository(connectionString);
  const stateRepository = new PostgresStateRepository(connectionString);
  return createApp({ authVerifier, identityRepository, stateRepository });
}
