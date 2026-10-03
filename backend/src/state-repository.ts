import type { VerificationClass } from "../../packages/contracts/src/core.ts";
import type {
  RefreshStatus,
  StateType,
  VerificationStatus,
} from "../../packages/contracts/src/lifecycle.ts";

export type NearbyStateCursor = {
  distanceM: number;
  stateId: string;
  asOf?: Date;
};

export type StateHistoryCursor = {
  observedAt: Date;
  historyId: string;
};

export type MapLocation = {
  locationId: string;
  name: string;
  locationType: string;
  displayAddress: string | null;
  center: { latitude: number; longitude: number } | null;
};

export type NearbyStateRecord = {
  location?: MapLocation | null;
  stateId: string;
  title: string;
  question: string;
  stateType: StateType;
  unitCode: string | null;
  currentValue: unknown | null;
  observedAt: Date | null;
  agingAt: Date | null;
  freshUntil: Date | null;
  verificationClass: VerificationClass | null;
  refreshStatus: RefreshStatus | null;
  conflictActive: boolean;
  distanceM: number;
  revision: number;
};

export type StateLocationRecord = {
  center?: { latitude: number; longitude: number } | null;
  locationId: string;
  name: string;
  locationType: string;
  displayAddress: string | null;
  centerEwkb: Uint8Array;
  boundaryEwkb: Uint8Array | null;
};

export type StateVerificationSummary = {
  status: VerificationStatus;
  reasonCodes: string[];
  evidenceCount: number;
};

export type StateActiveRefreshSummary = {
  refreshId: string;
  status: RefreshStatus;
  verificationClass: VerificationClass;
  expiresAt: Date;
  revision: number;
};

export type StateDetailRecord = {
  stateId: string;
  version: number;
  canonicalKey: string;
  title: string;
  question: string;
  stateType: StateType;
  unitCode: string | null;
  answerSchema: unknown;
  currentValue: unknown | null;
  observedAt: Date | null;
  observationEarliest: Date | null;
  observationLatest: Date | null;
  agingAt: Date | null;
  freshUntil: Date | null;
  verificationClass: VerificationClass | null;
  conflictActive: boolean;
  revision: number;
  location: StateLocationRecord;
  verification: StateVerificationSummary | null;
  activeRefresh: StateActiveRefreshSummary | null;
};

export type StateHistoryRecord = {
  historyId: string;
  stateId: string;
  stateVersion: number;
  value: unknown | null;
  observedAt: Date;
  agingAt: Date;
  freshUntil: Date;
  verificationClass: VerificationClass;
  verificationStatus: VerificationStatus | null;
};

export type NearbyStateInput = {
  lat: number;
  lng: number;
  radiusM: number;
  limit: number;
  cursor: NearbyStateCursor | null;
  search?: string;
  freshness?: string;
  asOf?: Date;
};

export type NearbyStateCounts = { total: number; live: number; aging: number; stale: number };

export interface StateRepository {
  listNearby(input: NearbyStateInput): Promise<NearbyStateRecord[]>;
  summarizeNearby?(input: NearbyStateInput): Promise<NearbyStateCounts>;

  getState(stateId: string): Promise<StateDetailRecord | null>;

  listHistory(input: {
    stateId: string;
    limit: number;
    cursor: StateHistoryCursor | null;
  }): Promise<StateHistoryRecord[]>;
}
