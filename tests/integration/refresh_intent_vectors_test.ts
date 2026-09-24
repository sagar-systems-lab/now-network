import bs58 from "npm:bs58@6.0.0";
import {
  bytesToHex,
  deriveAnswerSchemaDigestV1,
  deriveChainRefreshIdV1,
  deriveLocationScopeDigestV1,
  deriveRefreshIntentCoreHashV1,
  deriveStateIdDigestV1,
  encodeRefreshIntentCoreV1,
} from "../../packages/contracts/src/refresh-intent.ts";
import { policyTemplateForKey } from "../../packages/policy/src/registry.ts";
import { policyDigestHex } from "../../packages/policy/src/template.ts";

function hexToBytes(value: string): Uint8Array {
  if (value.length === 0) return new Uint8Array();
  if (!/^[0-9a-f]+$/u.test(value) || value.length % 2 !== 0) {
    throw new TypeError("invalid hex fixture");
  }
  return Uint8Array.from(value.match(/../gu) ?? [], (part) => Number.parseInt(part, 16));
}

Deno.test("refresh intent golden vector is byte-stable", async () => {
  const vector = JSON.parse(
    await Deno.readTextFile("test-vectors/refresh-intent-v1.json"),
  ) as {
    refresh_id: string;
    state_id: string;
    state_definition_version: number;
    location_id: string;
    center_ewkb_hex: string;
    boundary_ewkb_hex: string;
    answer_schema: unknown;
    policy_template_key: string;
    reward_mint: string;
    refresh_expires_at_unix: number;
    expected: Record<string, string>;
  };

  const policy = policyTemplateForKey(vector.policy_template_key);
  if (!policy) throw new Error("policy fixture missing");

  const [
    chainRefreshId,
    stateIdDigest,
    locationScopeDigest,
    answerSchemaDigest,
  ] = await Promise.all([
    deriveChainRefreshIdV1(vector.refresh_id),
    deriveStateIdDigestV1(vector.state_id),
    deriveLocationScopeDigestV1({
      locationId: vector.location_id,
      centerEwkb: hexToBytes(vector.center_ewkb_hex),
      boundaryEwkb: vector.boundary_ewkb_hex ? hexToBytes(vector.boundary_ewkb_hex) : null,
    }),
    deriveAnswerSchemaDigestV1(vector.answer_schema),
  ]);
  const proofPolicyDigest = hexToBytes(await policyDigestHex(policy));
  const intent = {
    stateIdDigest,
    stateDefinitionVersion: vector.state_definition_version,
    locationScopeDigest,
    stateType: policy.state_type,
    answerSchemaDigest,
    freshnessTtlSeconds: policy.fresh_ttl_seconds,
    proofPolicyDigest,
    verificationClass: policy.verification_class,
    requiredWitnesses: 1,
    maxWitnesses: 1,
    payoutRule: "SINGLE_WINNER_ALL" as const,
    refreshExpiresAtUnix: BigInt(vector.refresh_expires_at_unix),
    rewardMint: Uint8Array.from(bs58.decode(vector.reward_mint)),
  };
  const canonical = encodeRefreshIntentCoreV1(intent);
  const intentHash = await deriveRefreshIntentCoreHashV1(intent);

  const actual = {
    chain_refresh_id_hex: bytesToHex(chainRefreshId),
    state_id_digest_hex: bytesToHex(stateIdDigest),
    location_scope_digest_hex: bytesToHex(locationScopeDigest),
    answer_schema_digest_hex: bytesToHex(answerSchemaDigest),
    proof_policy_digest_hex: bytesToHex(proofPolicyDigest),
    intent_core_canonical_hex: bytesToHex(canonical),
    intent_core_hash_hex: bytesToHex(intentHash),
  };

  for (const [key, expected] of Object.entries(vector.expected)) {
    if (actual[key as keyof typeof actual] !== expected) {
      throw new Error(`${key} golden vector mismatch`);
    }
  }
});
