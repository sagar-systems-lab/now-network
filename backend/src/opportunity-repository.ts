import type { VerificationClass } from "../../packages/contracts/src/core.ts";
import type { StateType } from "../../packages/contracts/src/lifecycle.ts";
import type { PolicyTemplateV1 } from "../../packages/policy/src/types.ts";

export type OpportunityCursor = {
  distanceM: number;
  refreshId: string;
  expiresAt?: Date;
  rewardMint?: string;
  estimatedAtomic?: string;
};

export type OpportunityRecord = {
  center?: { latitude: number; longitude: number } | null;
  refreshId: string;
  stateId: string;
  stateVersion: number;
  title: string;
  question: string;
  stateType: StateType;
  unitCode: string | null;
  locationId: string;
  locationName: string;
  locationType: string;
  displayAddress: string | null;
  rewardMint: string;
  rewardAtomic: bigint;
  payoutRule: "SINGLE_WINNER_ALL" | "EQUAL_SPLIT_REQUIRED_WITNESSES";
  refreshExpiresAt: Date;
  evidenceDeadline: Date;
  verificationClass: VerificationClass;
  requiredWitnesses: number;
  maxWitnesses: number;
  activeClaims: number;
  remainingSlots: number;
  proofPolicySnapshot: PolicyTemplateV1;
  distanceM: number | null;
  stateRevision: number;
  refreshRevision: number;
};

export type OpportunitySort = "nearest" | "payout" | "ending";

export type NearbyOpportunityInput = {
  actorId: string;
  lat: number;
  lng: number;
  radiusM: number;
  limit: number;
  cursor: OpportunityCursor | null;
  sort?: OpportunitySort;
  category?: string | null;
  asOf?: Date;
};

export interface OpportunityRepository {
  listNearby(input: NearbyOpportunityInput): Promise<OpportunityRecord[]>;
  summarizeNearby?(input: NearbyOpportunityInput): Promise<{ total: number; categories: string[] }>;

  getOpportunity(input: {
    actorId: string;
    refreshId: string;
  }): Promise<OpportunityRecord | null>;
}
