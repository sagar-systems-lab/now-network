import bs58 from "npm:bs58@6.0.0";
import { ApiFault } from "./errors.ts";
import type {
  ActorRecord,
  ChallengeConsumptionResult,
  IdentityRepository,
  WalletBindingChallengeRecord,
  WalletBindingRecord,
} from "./identity-repository.ts";

const encoder = new TextEncoder();
const CLUSTER_PATTERN = /^[a-z0-9][a-z0-9-]{0,31}$/;
export const WALLET_BINDING_TTL_MS = 5 * 60 * 1000;

export type WalletChallengeResponse = {
  challenge_id: string;
  wallet_address: string;
  cluster: string;
  purpose: "wallet_binding";
  domain: "NOW Network";
  message: string;
  issued_at: string;
  expires_at: string;
};

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/g, "");
}

function decodeBase64(input: string): Uint8Array {
  const normalized = input.replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  let binary: string;
  try {
    binary = atob(padded);
  } catch {
    throw new ApiFault(400, "INVALID_REQUEST", "Invalid signature encoding.");
  }
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

async function sha256(input: string | Uint8Array): Promise<Uint8Array> {
  const bytes = typeof input === "string" ? encoder.encode(input) : input;
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

async function sessionBinding(authUserId: string): Promise<string> {
  return bytesToBase64Url(await sha256(`now-session:${authUserId}`));
}

export function validateWalletAddress(walletAddress: string): Uint8Array {
  let decoded: Uint8Array;
  try {
    decoded = bs58.decode(walletAddress);
  } catch {
    throw new ApiFault(422, "WALLET_ADDRESS_INVALID", "The wallet address is invalid.");
  }
  if (decoded.length !== 32 || bs58.encode(decoded) !== walletAddress) {
    throw new ApiFault(422, "WALLET_ADDRESS_INVALID", "The wallet address is invalid.");
  }
  return decoded;
}

export function validateCluster(cluster: string): string {
  if (!CLUSTER_PATTERN.test(cluster)) {
    throw new ApiFault(422, "WALLET_CLUSTER_INVALID", "The wallet cluster is invalid.");
  }
  return cluster;
}

export async function canonicalWalletBindingMessage(input: {
  challengeId: string;
  nonce: string;
  actorId: string;
  authUserId: string;
  walletAddress: string;
  cluster: string;
  issuedAt: Date;
  expiresAt: Date;
}): Promise<string> {
  return [
    "NOW Network",
    "Purpose: wallet binding",
    `Challenge-ID: ${input.challengeId}`,
    `Nonce: ${input.nonce}`,
    `Actor-ID: ${input.actorId}`,
    `Session-Binding: ${await sessionBinding(input.authUserId)}`,
    `Wallet: ${input.walletAddress}`,
    `Cluster: ${input.cluster}`,
    `Issued-At: ${input.issuedAt.toISOString()}`,
    `Expires-At: ${input.expiresAt.toISOString()}`,
  ].join("\n");
}

export class WalletBindingService {
  constructor(
    private readonly repository: IdentityRepository,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async issue(
    actor: ActorRecord,
    authUserId: string,
    walletAddress: string,
    cluster: string,
  ): Promise<WalletChallengeResponse> {
    ensureActorCanMutate(actor);
    validateWalletAddress(walletAddress);
    validateCluster(cluster);

    const challengeId = crypto.randomUUID();
    const nonceBytes = crypto.getRandomValues(new Uint8Array(32));
    const nonce = bytesToBase64Url(nonceBytes);
    const issuedAt = this.now();
    const expiresAt = new Date(issuedAt.getTime() + WALLET_BINDING_TTL_MS);
    const message = await canonicalWalletBindingMessage({
      challengeId,
      nonce,
      actorId: actor.actorId,
      authUserId,
      walletAddress,
      cluster,
      issuedAt,
      expiresAt,
    });

    const challenge: WalletBindingChallengeRecord = {
      challengeId,
      actorId: actor.actorId,
      authUserId,
      walletAddress,
      cluster,
      purpose: "wallet_binding",
      domain: "NOW Network",
      message,
      messageSha256: await sha256(message),
      nonceHash: await sha256(nonceBytes),
      status: "ISSUED",
      issuedAt,
      expiresAt,
      consumedAt: null,
    };

    await this.repository.issueWalletBindingChallenge(challenge);

    return {
      challenge_id: challengeId,
      wallet_address: walletAddress,
      cluster,
      purpose: "wallet_binding",
      domain: "NOW Network",
      message,
      issued_at: issuedAt.toISOString(),
      expires_at: expiresAt.toISOString(),
    };
  }

  async verify(
    actor: ActorRecord,
    authUserId: string,
    challengeId: string,
    signatureEncoded: string,
  ): Promise<{ actor: ActorRecord; binding: WalletBindingRecord; recovered: boolean }> {
    ensureActorCanMutate(actor);
    const challenge = await this.repository.getWalletBindingChallenge(challengeId);
    if (!challenge) {
      throw new ApiFault(404, "WALLET_BINDING_CHALLENGE_INVALID", "The wallet challenge was not found.");
    }
    if (challenge.authUserId !== authUserId) {
      throw new ApiFault(403, "ACTOR_MISMATCH", "The wallet challenge belongs to another session.");
    }
    if (challenge.purpose !== "wallet_binding" || challenge.domain !== "NOW Network") {
      throw new ApiFault(403, "WALLET_BINDING_CHALLENGE_INVALID", "The wallet challenge is invalid.");
    }
    if (challenge.status === "CONSUMED") {
      throw new ApiFault(409, "WALLET_BINDING_CHALLENGE_CONSUMED", "The wallet challenge was already used.");
    }
    if (challenge.status === "REVOKED") {
      throw new ApiFault(409, "WALLET_BINDING_CHALLENGE_REVOKED", "The wallet challenge was revoked.");
    }
    if (challenge.status === "EXPIRED" || challenge.expiresAt.getTime() <= this.now().getTime()) {
      throw new ApiFault(410, "CHALLENGE_EXPIRED", "The wallet challenge expired.");
    }
    if (challenge.actorId !== actor.actorId) {
      throw new ApiFault(403, "ACTOR_MISMATCH", "The wallet challenge belongs to another session.");
    }
    const actualMessageHash = await sha256(challenge.message);
    if (!equalBytes(actualMessageHash, challenge.messageSha256)) {
      throw new ApiFault(403, "WALLET_BINDING_CHALLENGE_INVALID", "The wallet challenge is invalid.");
    }

    const publicKeyBytes = validateWalletAddress(challenge.walletAddress);
    const signature = decodeBase64(signatureEncoded);
    if (signature.length !== 64) {
      throw new ApiFault(403, "WALLET_SIGNATURE_INVALID", "The wallet signature is invalid.");
    }

    let key: CryptoKey;
    try {
      key = await crypto.subtle.importKey(
        "raw",
        publicKeyBytes,
        { name: "Ed25519" },
        false,
        ["verify"],
      );
    } catch {
      throw new ApiFault(403, "WALLET_SIGNATURE_INVALID", "The wallet signature is invalid.");
    }

    const valid = await crypto.subtle.verify(
      "Ed25519",
      key,
      signature,
      encoder.encode(challenge.message),
    );
    if (!valid) {
      throw new ApiFault(403, "WALLET_SIGNATURE_INVALID", "The wallet signature is invalid.");
    }

    const result = await this.repository.consumeWalletBindingChallenge(
      challengeId,
      authUserId,
      this.now(),
    );
    if (result.kind !== "bound") throw consumeFault(result);
    return { actor: result.actor, binding: result.binding, recovered: result.recovered };
  }
}

function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index++) {
    if (left[index] !== right[index]) return false;
  }
  return true;
}

function ensureActorCanMutate(actor: ActorRecord): void {
  if (actor.status === "DISABLED") {
    throw new ApiFault(403, "ACTOR_DISABLED", "This actor is disabled.");
  }
  if (actor.status === "RESTRICTED") {
    throw new ApiFault(403, "ACTOR_RESTRICTED", "This actor cannot perform this operation.");
  }
}

function consumeFault(result: Exclude<ChallengeConsumptionResult, { kind: "bound" }>): ApiFault {
  switch (result.kind) {
    case "not_found":
      return new ApiFault(404, "WALLET_BINDING_CHALLENGE_INVALID", "The wallet challenge was not found.");
    case "expired":
      return new ApiFault(410, "CHALLENGE_EXPIRED", "The wallet challenge expired.");
    case "consumed":
      return new ApiFault(409, "WALLET_BINDING_CHALLENGE_CONSUMED", "The wallet challenge was already used.");
    case "revoked":
      return new ApiFault(409, "WALLET_BINDING_CHALLENGE_REVOKED", "The wallet challenge was revoked.");
    case "actor_mismatch":
      return new ApiFault(403, "ACTOR_MISMATCH", "The wallet challenge belongs to another session.");
    case "actor_disabled":
      return new ApiFault(403, "ACTOR_DISABLED", "This actor is disabled.");
    case "actor_restricted":
      return new ApiFault(403, "ACTOR_RESTRICTED", "This actor cannot perform this operation.");
    case "binding_conflict":
      return new ApiFault(409, "WALLET_BINDING_CONFLICT", "The wallet is bound to incompatible actor state.");
  }
}
