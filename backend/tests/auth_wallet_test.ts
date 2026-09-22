import bs58 from "npm:bs58@6.0.0";
import { createApp } from "../src/app.ts";
import type { AuthPrincipal, AuthVerifier } from "../src/auth.ts";
import { ApiFault } from "../src/errors.ts";
import type {
  ActorRecord,
  ChallengeConsumptionResult,
  IdentityRepository,
  WalletBindingChallengeRecord,
  WalletBindingRecord,
} from "../src/identity-repository.ts";

const AUTH_A = "00000000-0000-4000-8000-0000000000a1";
const AUTH_B = "00000000-0000-4000-8000-0000000000b2";

class TestAuthVerifier implements AuthVerifier {
  async verify(request: Request): Promise<AuthPrincipal> {
    const token = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ?? "";
    if (token === "token-a") {
      return { authUserId: AUTH_A, principalType: "SUPABASE_ANONYMOUS" };
    }
    if (token === "token-b") {
      return { authUserId: AUTH_B, principalType: "SUPABASE_ANONYMOUS" };
    }
    throw new ApiFault(
      401,
      token ? "AUTH_INVALID" : "AUTH_REQUIRED",
      "Authentication is required.",
    );
  }
}

class MemoryIdentityRepository implements IdentityRepository {
  private actorSequence = 1;
  private bindingSequence = 1;
  readonly actors = new Map<string, ActorRecord>();
  readonly principals = new Map<string, string>();
  readonly bindings = new Map<string, WalletBindingRecord>();
  readonly challenges = new Map<string, WalletBindingChallengeRecord>();
  readonly protectedActors = new Set<string>();

  async resolveActor(authUserId: string): Promise<ActorRecord> {
    const mapped = this.principals.get(authUserId);
    if (mapped) return structuredClone(this.actors.get(mapped)!);

    const sequence = String(this.actorSequence++).padStart(12, "0");
    const actorId = `00000000-0000-4000-8000-${sequence}`;
    const actor: ActorRecord = { actorId, status: "ACTIVE", revision: 1 };
    this.actors.set(actorId, actor);
    this.principals.set(authUserId, actorId);
    return structuredClone(actor);
  }

  async listWalletBindings(actorId: string): Promise<WalletBindingRecord[]> {
    return [...this.bindings.values()]
      .filter((binding) => binding.actorId === actorId)
      .map((binding) => structuredClone(binding));
  }

  async issueWalletBindingChallenge(challenge: WalletBindingChallengeRecord): Promise<void> {
    for (const [id, current] of this.challenges) {
      if (current.authUserId === challenge.authUserId && current.status === "ISSUED") {
        this.challenges.set(id, { ...current, status: "REVOKED" });
      }
    }
    this.challenges.set(challenge.challengeId, structuredClone(challenge));
  }

  async getWalletBindingChallenge(
    challengeId: string,
  ): Promise<WalletBindingChallengeRecord | null> {
    const value = this.challenges.get(challengeId);
    return value ? structuredClone(value) : null;
  }

  async consumeWalletBindingChallenge(
    challengeId: string,
    authUserId: string,
    now: Date,
  ): Promise<ChallengeConsumptionResult> {
    const challenge = this.challenges.get(challengeId);
    if (!challenge) return { kind: "not_found" };
    if (challenge.authUserId !== authUserId) return { kind: "actor_mismatch" };
    if (challenge.status === "CONSUMED") return { kind: "consumed" };
    if (challenge.status === "REVOKED") return { kind: "revoked" };
    if (challenge.status === "EXPIRED" || challenge.expiresAt.getTime() <= now.getTime()) {
      this.challenges.set(challengeId, { ...challenge, status: "EXPIRED" });
      return { kind: "expired" };
    }
    if (this.principals.get(authUserId) !== challenge.actorId) {
      return { kind: "actor_mismatch" };
    }
    const currentActor = this.actors.get(challenge.actorId)!;
    if (currentActor.status === "DISABLED") return { kind: "actor_disabled" };
    if (currentActor.status === "RESTRICTED") return { kind: "actor_restricted" };

    const key = `${challenge.cluster}:${challenge.walletAddress}`;
    let binding = this.bindings.get(key);
    let targetActorId = challenge.actorId;
    let recovered = false;

    if (!binding) {
      const sequence = String(this.bindingSequence++).padStart(12, "0");
      binding = {
        walletBindingId: `00000000-0000-4000-8001-${sequence}`,
        actorId: challenge.actorId,
        walletAddress: challenge.walletAddress,
        cluster: challenge.cluster,
        status: "ACTIVE",
        revision: 1,
      };
      this.bindings.set(key, binding);
    } else {
      const targetActor = this.actors.get(binding.actorId)!;
      if (binding.status !== "ACTIVE" || targetActor.status !== "ACTIVE") {
        return { kind: "binding_conflict" };
      }
      targetActorId = binding.actorId;
      if (targetActorId !== challenge.actorId) {
        if (this.protectedActors.has(challenge.actorId)) {
          return { kind: "binding_conflict" };
        }
        this.principals.set(authUserId, targetActorId);
        recovered = true;
      }
      binding = { ...binding, revision: binding.revision + 1 };
      this.bindings.set(key, binding);
    }

    this.challenges.set(challengeId, {
      ...challenge,
      status: "CONSUMED",
      consumedAt: now,
    });
    return {
      kind: "bound",
      actor: structuredClone(this.actors.get(targetActorId)!),
      binding: structuredClone(binding),
      recovered,
    };
  }

  disableActor(actorId: string): void {
    const actor = this.actors.get(actorId)!;
    this.actors.set(actorId, {
      ...actor,
      status: "DISABLED",
      revision: actor.revision + 1,
    });
  }
}

async function createWallet(): Promise<{ address: string; privateKey: CryptoKey }> {
  const pair = await crypto.subtle.generateKey(
    { name: "Ed25519" },
    true,
    ["sign", "verify"],
  );
  const keyPair = pair as CryptoKeyPair;
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", keyPair.publicKey));
  return { address: bs58.encode(raw), privateKey: keyPair.privateKey };
}

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

async function sign(message: string, privateKey: CryptoKey): Promise<string> {
  const signature = await crypto.subtle.sign(
    "Ed25519",
    privateKey,
    new TextEncoder().encode(message),
  );
  return toBase64(new Uint8Array(signature));
}

function request(path: string, token: string | null, body?: unknown): Request {
  const headers = new Headers();
  if (token) headers.set("authorization", `Bearer ${token}`);
  if (body !== undefined) headers.set("content-type", "application/json");
  return new Request(`http://localhost${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function payload(response: Response): Promise<Record<string, unknown>> {
  return await response.json() as Record<string, unknown>;
}

function errorCode(body: Record<string, unknown>): string {
  return ((body.error as Record<string, unknown>)?.code ?? "") as string;
}

async function issue(
  app: (request: Request) => Promise<Response>,
  token: string,
  walletAddress: string,
): Promise<Record<string, unknown>> {
  const envelope = await payload(
    await app(
      request("/v1/wallet-bindings/challenge", token, {
        wallet_address: walletAddress,
        cluster: "devnet",
      }),
    ),
  );
  return envelope.data as Record<string, unknown>;
}

async function verify(
  app: (request: Request) => Promise<Response>,
  token: string,
  challenge: Record<string, unknown>,
  privateKey: CryptoKey,
): Promise<Response> {
  return await app(
    request("/v1/wallet-bindings/verify", token, {
      challenge_id: challenge.challenge_id,
      signature: await sign(challenge.message as string, privateKey),
    }),
  );
}

Deno.test("anonymous session resolves a stable actor", async () => {
  const repo = new MemoryIdentityRepository();
  const app = createApp({
    authVerifier: new TestAuthVerifier(),
    identityRepository: repo,
  });

  const first = await payload(await app(request("/v1/me", "token-a")));
  const second = await payload(await app(request("/v1/me", "token-a")));
  const firstActor = (first.data as Record<string, unknown>).actor_id;
  const secondActor = (second.data as Record<string, unknown>).actor_id;
  if (firstActor !== secondActor) {
    throw new Error("actor identity must be stable per auth principal");
  }
});

Deno.test("missing and invalid sessions fail closed", async () => {
  const repo = new MemoryIdentityRepository();
  const app = createApp({
    authVerifier: new TestAuthVerifier(),
    identityRepository: repo,
  });

  const missing = await app(request("/v1/me", null));
  if (missing.status !== 401 || errorCode(await payload(missing)) !== "AUTH_REQUIRED") {
    throw new Error("missing session must return AUTH_REQUIRED");
  }

  const invalid = await app(request("/v1/me", "tampered"));
  if (invalid.status !== 401 || errorCode(await payload(invalid)) !== "AUTH_INVALID") {
    throw new Error("invalid session must return AUTH_INVALID");
  }
});

Deno.test("wallet challenge binds once and replay is rejected", async () => {
  const repo = new MemoryIdentityRepository();
  const app = createApp({
    authVerifier: new TestAuthVerifier(),
    identityRepository: repo,
  });
  const wallet = await createWallet();

  const challenge = await issue(app, "token-a", wallet.address);
  const first = await verify(app, "token-a", challenge, wallet.privateKey);
  if (first.status !== 200) {
    throw new Error(`wallet bind failed: ${JSON.stringify(await payload(first))}`);
  }

  const replay = await verify(app, "token-a", challenge, wallet.privateKey);
  if (
    replay.status !== 409 ||
    errorCode(await payload(replay)) !== "WALLET_BINDING_CHALLENGE_CONSUMED"
  ) {
    throw new Error("challenge replay must be rejected");
  }
});

Deno.test("a newer wallet challenge revokes the previous challenge", async () => {
  const repo = new MemoryIdentityRepository();
  const app = createApp({
    authVerifier: new TestAuthVerifier(),
    identityRepository: repo,
  });
  const wallet = await createWallet();

  const first = await issue(app, "token-a", wallet.address);
  await issue(app, "token-a", wallet.address);

  const response = await verify(app, "token-a", first, wallet.privateKey);
  if (
    response.status !== 409 ||
    errorCode(await payload(response)) !== "WALLET_BINDING_CHALLENGE_REVOKED"
  ) {
    throw new Error("superseded challenge must be revoked");
  }
});

Deno.test("a signature from the wrong wallet is rejected", async () => {
  const repo = new MemoryIdentityRepository();
  const app = createApp({
    authVerifier: new TestAuthVerifier(),
    identityRepository: repo,
  });
  const expectedWallet = await createWallet();
  const wrongWallet = await createWallet();

  const challenge = await issue(app, "token-a", expectedWallet.address);
  const response = await verify(app, "token-a", challenge, wrongWallet.privateKey);
  if (
    response.status !== 403 ||
    errorCode(await payload(response)) !== "WALLET_SIGNATURE_INVALID"
  ) {
    throw new Error("wrong wallet signature must be rejected");
  }
});

Deno.test("expired wallet challenge is rejected", async () => {
  let clock = new Date("2026-09-22T12:00:00.000Z");
  const repo = new MemoryIdentityRepository();
  const app = createApp({
    authVerifier: new TestAuthVerifier(),
    identityRepository: repo,
    now: () => new Date(clock),
  });
  const wallet = await createWallet();

  const challenge = await issue(app, "token-a", wallet.address);
  const signature = await sign(challenge.message as string, wallet.privateKey);
  clock = new Date("2026-09-22T12:06:00.000Z");

  const response = await app(
    request("/v1/wallet-bindings/verify", "token-a", {
      challenge_id: challenge.challenge_id,
      signature,
    }),
  );
  if (
    response.status !== 410 ||
    errorCode(await payload(response)) !== "CHALLENGE_EXPIRED"
  ) {
    throw new Error("expired challenge must be rejected");
  }
});

Deno.test("cross-session challenge use is rejected", async () => {
  const repo = new MemoryIdentityRepository();
  const app = createApp({
    authVerifier: new TestAuthVerifier(),
    identityRepository: repo,
  });
  const wallet = await createWallet();

  const challenge = await issue(app, "token-a", wallet.address);
  const response = await verify(app, "token-b", challenge, wallet.privateKey);
  if (
    response.status !== 403 ||
    errorCode(await payload(response)) !== "ACTOR_MISMATCH"
  ) {
    throw new Error("cross-session challenge must be rejected");
  }
});

Deno.test("domain or cluster message mutation invalidates the signature", async () => {
  const repo = new MemoryIdentityRepository();
  const app = createApp({
    authVerifier: new TestAuthVerifier(),
    identityRepository: repo,
  });
  const wallet = await createWallet();

  const challenge = await issue(app, "token-a", wallet.address);
  const altered = (challenge.message as string)
    .replace("NOW Network", "Other Network")
    .replace("Cluster: devnet", "Cluster: mainnet-beta");
  const signature = await sign(altered, wallet.privateKey);

  const response = await app(
    request("/v1/wallet-bindings/verify", "token-a", {
      challenge_id: challenge.challenge_id,
      signature,
    }),
  );
  if (
    response.status !== 403 ||
    errorCode(await payload(response)) !== "WALLET_SIGNATURE_INVALID"
  ) {
    throw new Error("mutated challenge message must not authorize wallet binding");
  }
});

Deno.test("wallet proof recovers the existing actor for a new anonymous principal", async () => {
  const repo = new MemoryIdentityRepository();
  const app = createApp({
    authVerifier: new TestAuthVerifier(),
    identityRepository: repo,
  });
  const wallet = await createWallet();

  const actorA = (await repo.resolveActor(AUTH_A, "SUPABASE_ANONYMOUS")).actorId;
  const firstChallenge = await issue(app, "token-a", wallet.address);
  await verify(app, "token-a", firstChallenge, wallet.privateKey);

  const provisionalB = (await repo.resolveActor(AUTH_B, "SUPABASE_ANONYMOUS")).actorId;
  if (provisionalB === actorA) {
    throw new Error("test requires a provisional second actor");
  }

  const recoveryChallenge = await issue(app, "token-b", wallet.address);
  const recoveredEnvelope = await payload(
    await verify(app, "token-b", recoveryChallenge, wallet.privateKey),
  );
  const recovered = recoveredEnvelope.data as Record<string, unknown>;
  if (recovered.recovered !== true || recovered.actor_id !== actorA) {
    throw new Error("wallet proof must recover the wallet's existing actor");
  }

  const me = await payload(await app(request("/v1/me", "token-b")));
  if ((me.data as Record<string, unknown>).actor_id !== actorA) {
    throw new Error("recovered principal must resolve to the existing actor");
  }
});

Deno.test("recovery fails closed when provisional actor owns protected state", async () => {
  const repo = new MemoryIdentityRepository();
  const app = createApp({
    authVerifier: new TestAuthVerifier(),
    identityRepository: repo,
  });
  const wallet = await createWallet();

  const actorA = (await repo.resolveActor(AUTH_A, "SUPABASE_ANONYMOUS")).actorId;
  const first = await issue(app, "token-a", wallet.address);
  await verify(app, "token-a", first, wallet.privateKey);

  const actorB = (await repo.resolveActor(AUTH_B, "SUPABASE_ANONYMOUS")).actorId;
  if (actorB === actorA) throw new Error("test requires distinct actors");
  repo.protectedActors.add(actorB);

  const recovery = await issue(app, "token-b", wallet.address);
  const response = await verify(app, "token-b", recovery, wallet.privateKey);
  if (
    response.status !== 409 ||
    errorCode(await payload(response)) !== "WALLET_BINDING_CONFLICT"
  ) {
    throw new Error("actor recovery must not orphan authority-bearing state");
  }
});

Deno.test("disabled actor cannot issue a wallet challenge", async () => {
  const repo = new MemoryIdentityRepository();
  const app = createApp({
    authVerifier: new TestAuthVerifier(),
    identityRepository: repo,
  });
  const wallet = await createWallet();
  const actor = await repo.resolveActor(AUTH_A, "SUPABASE_ANONYMOUS");
  repo.disableActor(actor.actorId);

  const response = await app(
    request("/v1/wallet-bindings/challenge", "token-a", {
      wallet_address: wallet.address,
      cluster: "devnet",
    }),
  );
  if (
    response.status !== 403 ||
    errorCode(await payload(response)) !== "ACTOR_DISABLED"
  ) {
    throw new Error("disabled actor must be blocked from wallet binding");
  }
});
