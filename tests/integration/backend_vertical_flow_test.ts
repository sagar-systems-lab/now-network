import { type AppDependencies, createApp } from "../../backend/src/app.ts";
import { ApiFault } from "../../backend/src/errors.ts";
import { NowWorkerCoordinator } from "../../backend/src/now-worker-coordinator.ts";
import type { ReceiptCoordinator } from "../../backend/src/receipt-coordinator.ts";
import type { RealtimePublisher } from "../../backend/src/realtime-outbox.ts";
import type { SettlementCoordinator } from "../../backend/src/settlement-coordinator.ts";
import { createSettlementWorkerHandler } from "../../backend/src/settlement-worker.ts";

const ACTOR_ID = "10000000-0000-4000-8000-000000000001";
const AUTH_USER_ID = "10000000-0000-4000-8000-000000000002";
const STATE_ID = "20000000-0000-4000-8000-000000000001";
const WALLET_BINDING_ID = "30000000-0000-4000-8000-000000000001";
const REFRESH_ID = "40000000-0000-4000-8000-000000000001";
const ACCEPTANCE_ID = "50000000-0000-4000-8000-000000000001";
const CHALLENGE_ID = "60000000-0000-4000-8000-000000000001";
const EVIDENCE_ID = "70000000-0000-4000-8000-000000000001";
const VERIFICATION_ID = "80000000-0000-4000-8000-000000000001";
const RECEIPT_ID = "90000000-0000-4000-8000-000000000001";
const WORKER_TOKEN = "vertical-worker-token-0123456789abcdef";
const REQUESTER_ACTOR_ID = "11000000-0000-4000-8000-000000000001";
const REQUESTER_AUTH_USER_ID = "11000000-0000-4000-8000-000000000002";
const CONTRIBUTOR_ACTOR_ID = "12000000-0000-4000-8000-000000000001";
const CONTRIBUTOR_AUTH_USER_ID = "12000000-0000-4000-8000-000000000002";
const REQUESTER_WALLET_BINDING_ID = "31000000-0000-4000-8000-000000000001";
const CONTRIBUTOR_WALLET_BINDING_ID = "32000000-0000-4000-8000-000000000001";

type FlowState = {
  freshness: "STALE" | "LIVE";
  refresh: string;
  claim: string;
  evidenceCommitted: boolean;
  verified: boolean;
  settled: boolean;
  receiptFinal: boolean;
  realtimePublished: boolean;
};

function authenticatedRequest(
  path: string,
  init: RequestInit = {},
  token = "integration-session",
): Request {
  const headers = new Headers(init.headers);
  headers.set("authorization", `Bearer ${token}`);
  return new Request(`https://now.invalid${path}`, {
    ...init,
    headers,
  });
}

async function expectSuccess(
  handler: (request: Request) => Promise<Response>,
  request: Request,
  expectedStatus: number,
): Promise<Record<string, unknown>> {
  const response = await handler(request);
  const body = await response.json() as Record<string, unknown>;
  if (response.status !== expectedStatus) {
    throw new Error(
      `unexpected response ${response.status}: ${JSON.stringify(body)}`,
    );
  }
  return body;
}

Deno.test("headless backend flow reaches a final receipt", async () => {
  const flow: FlowState = {
    freshness: "STALE",
    refresh: "NONE",
    claim: "NONE",
    evidenceCommitted: false,
    verified: false,
    settled: false,
    receiptFinal: false,
    realtimePublished: false,
  };

  const actor = {
    actorId: ACTOR_ID,
    status: "ACTIVE" as const,
    revision: 1,
  };

  const authVerifier: AppDependencies["authVerifier"] = {
    verify: () =>
      Promise.resolve({
        authUserId: AUTH_USER_ID,
        principalType: "SUPABASE_ANONYMOUS",
      }),
  };

  const identityRepository = {
    resolveActor: () => Promise.resolve(actor),
    listWalletBindings: () => Promise.resolve([]),
  } as unknown as AppDependencies["identityRepository"];

  const refreshCoordinator = {
    create() {
      if (flow.freshness !== "STALE" || flow.refresh !== "NONE") {
        throw new Error("refresh did not start from stale state");
      }
      flow.refresh = "DRAFT";
      return Promise.resolve({
        status: 201,
        data: {
          refresh_id: REFRESH_ID,
          state_id: STATE_ID,
          status: flow.refresh,
        },
      });
    },
    fundingIntent() {
      if (flow.refresh !== "DRAFT") {
        throw new Error("funding intent was out of order");
      }
      flow.refresh = "AWAITING_FUNDING";
      return Promise.resolve({
        refresh_id: REFRESH_ID,
        status: flow.refresh,
        operation_id: "41000000-0000-4000-8000-000000000001",
      });
    },
    observeFunding() {
      if (flow.refresh !== "AWAITING_FUNDING") {
        throw new Error("funding observation was out of order");
      }
      flow.refresh = "AVAILABLE";
      return Promise.resolve({
        refresh_id: REFRESH_ID,
        status: flow.refresh,
      });
    },
    get() {
      return Promise.resolve({
        refresh_id: REFRESH_ID,
        status: flow.refresh,
      });
    },
  } as unknown as NonNullable<AppDependencies["refreshCoordinator"]>;

  const claimCoordinator = {
    prepare() {
      if (flow.refresh !== "AVAILABLE" || flow.claim !== "NONE") {
        throw new Error("claim preparation was out of order");
      }
      flow.claim = "WALLET_PENDING";
      return Promise.resolve({
        status: 201,
        data: {
          acceptance_id: ACCEPTANCE_ID,
          refresh_id: REFRESH_ID,
          status: flow.claim,
        },
      });
    },
    observe() {
      if (flow.claim !== "WALLET_PENDING") {
        throw new Error("claim observation was out of order");
      }
      flow.claim = "CLAIMED";
      flow.refresh = "CLAIMED";
      return Promise.resolve({
        acceptance_id: ACCEPTANCE_ID,
        refresh_id: REFRESH_ID,
        status: flow.claim,
      });
    },
    get() {
      return Promise.resolve({
        acceptance_id: ACCEPTANCE_ID,
        status: flow.claim,
      });
    },
  } as unknown as NonNullable<AppDependencies["claimCoordinator"]>;

  const evidenceChallengeService = {
    issue() {
      if (flow.claim !== "CLAIMED") {
        throw new Error("evidence challenge was issued before claim");
      }
      flow.claim = "CAPTURE_ACTIVE";
      flow.refresh = "CAPTURE_IN_PROGRESS";
      return Promise.resolve({
        challenge_id: CHALLENGE_ID,
        nonce: "integration-nonce",
        expires_at: "2026-09-26T10:00:00.000Z",
      });
    },
  } as unknown as NonNullable<AppDependencies["evidenceChallengeService"]>;

  const evidenceUploadService = {
    authorize() {
      if (flow.claim !== "CAPTURE_ACTIVE") {
        throw new Error("upload authorization was out of order");
      }
      return Promise.resolve({
        evidence_id: EVIDENCE_ID,
        challenge_id: CHALLENGE_ID,
        upload_url: "https://storage.invalid/signed-upload",
      });
    },
  } as unknown as NonNullable<AppDependencies["evidenceUploadService"]>;

  const evidenceCommitService = {
    commit() {
      if (flow.claim !== "CAPTURE_ACTIVE") {
        throw new Error("evidence commit was out of order");
      }
      flow.evidenceCommitted = true;
      flow.claim = "EVIDENCE_COMMITTED";
      flow.refresh = "EVIDENCE_SUBMITTED";
      return Promise.resolve({
        status: 201,
        data: {
          evidence_id: EVIDENCE_ID,
          refresh_id: REFRESH_ID,
          status: "COMMITTED",
        },
      });
    },
  } as unknown as NonNullable<AppDependencies["evidenceCommitService"]>;

  const verificationService = {
    verify() {
      if (!flow.evidenceCommitted || flow.refresh !== "EVIDENCE_SUBMITTED") {
        throw new Error("verification was out of order");
      }
      flow.verified = true;
      flow.refresh = "VERIFIED";
      return Promise.resolve({
        status: 200,
        data: {
          result: "VERIFIED",
          refresh_id: REFRESH_ID,
          verification_result_id: VERIFICATION_ID,
        },
      });
    },
  } as unknown as NonNullable<AppDependencies["verificationService"]>;

  const stateProjectionService = {
    project(refreshId: string, verificationResultId: string) {
      if (
        !flow.verified ||
        refreshId !== REFRESH_ID ||
        verificationResultId !== VERIFICATION_ID
      ) {
        throw new Error("state projection lost verification authority");
      }
      flow.freshness = "LIVE";
      return Promise.resolve({
        state_id: STATE_ID,
        refresh_id: REFRESH_ID,
        verification_result_id: VERIFICATION_ID,
        freshness: "LIVE",
      });
    },
  } as unknown as NonNullable<AppDependencies["stateProjectionService"]>;

  const receiptService = {
    get() {
      if (!flow.receiptFinal) {
        throw new Error("receipt was requested before finalization");
      }
      return Promise.resolve({
        receipt_id: RECEIPT_ID,
        refresh_id: REFRESH_ID,
        state_id: STATE_ID,
        verification_result_id: VERIFICATION_ID,
        status: "FINAL",
      });
    },
  } as unknown as NonNullable<AppDependencies["receiptService"]>;

  const app = createApp({
    authVerifier,
    identityRepository,
    refreshCoordinator,
    claimCoordinator,
    evidenceChallengeService,
    evidenceUploadService,
    evidenceCommitService,
    verificationService,
    stateProjectionService,
    receiptService,
  });

  await expectSuccess(
    app,
    authenticatedRequest("/v1/refreshes", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "idempotency-key": "aaaaaaa1",
      },
      body: JSON.stringify({
        state_id: STATE_ID,
        wallet_binding_id: WALLET_BINDING_ID,
        funding_target_atomic: "1000",
      }),
    }),
    201,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/refreshes/${REFRESH_ID}/funding-intent`, {
      method: "POST",
      headers: { "idempotency-key": "bbbbbbb2" },
    }),
    200,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/refreshes/${REFRESH_ID}/funding-observe`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "idempotency-key": "ccccccc3",
      },
      body: JSON.stringify({ signature: "integration-funding-signature" }),
    }),
    200,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/opportunities/${REFRESH_ID}/claim`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "idempotency-key": "ddddddd4",
      },
      body: JSON.stringify({
        wallet_binding_id: WALLET_BINDING_ID,
      }),
    }),
    201,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/claims/${ACCEPTANCE_ID}/observe`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ signature: "integration-claim-signature" }),
    }),
    200,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/claims/${ACCEPTANCE_ID}/challenge`, {
      method: "POST",
    }),
    201,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/evidence-challenges/${CHALLENGE_ID}/upload`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        nonce: "integration-nonce",
        media_mime: "image/jpeg",
      }),
    }),
    201,
  );

  await expectSuccess(
    app,
    authenticatedRequest(`/v1/evidence/${EVIDENCE_ID}/commit`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "idempotency-key": "eeeeeee5",
      },
      body: JSON.stringify({
        answer_value: { kind: "numeric", scaled_value: "2", scale: 0 },
      }),
    }),
    201,
  );

  const verificationResponse = await expectSuccess(
    app,
    authenticatedRequest(`/v1/refreshes/${REFRESH_ID}/verify`, {
      method: "POST",
    }),
    200,
  );
  const verificationData = verificationResponse.data as Record<string, unknown>;
  const projection = verificationData.state_projection as Record<string, unknown>;
  if (projection.freshness !== "LIVE" || flow.freshness !== "LIVE") {
    throw new Error("verified state did not become LIVE");
  }

  const settlement = {
    runOnce() {
      if (!flow.verified || flow.freshness !== "LIVE") {
        throw new Error("settlement started before verified LIVE state");
      }
      flow.settled = true;
      return Promise.resolve({
        prepared: 1,
        signed: 1,
        submitted: 1,
        ambiguous: 0,
        pending: 0,
        confirmed: 0,
        finalized: 1,
        safeRetries: 0,
        conflicts: 0,
        deferred: 0,
      });
    },
  } as unknown as SettlementCoordinator;

  const receipts = {
    runOnce() {
      if (!flow.settled) {
        throw new Error("receipt finalization ran before settlement");
      }
      flow.receiptFinal = true;
      flow.refresh = "COMPLETED";
      return Promise.resolve({
        finalized: 1,
        replayed: 0,
        conflicts: 0,
      });
    },
  } as unknown as ReceiptCoordinator;

  const realtime = {
    runOnce() {
      if (!flow.receiptFinal) {
        throw new Error("realtime publication ran before receipt");
      }
      flow.realtimePublished = true;
      return Promise.resolve({ published: 2 });
    },
  } as unknown as RealtimePublisher;

  const worker = createSettlementWorkerHandler(
    new NowWorkerCoordinator(settlement, receipts, realtime),
    WORKER_TOKEN,
  );
  const workerResponse = await worker(
    new Request("https://worker.invalid/", {
      method: "POST",
      headers: { authorization: `Bearer ${WORKER_TOKEN}` },
    }),
  );
  if (workerResponse.status !== 200) {
    throw new Error("headless worker did not complete settlement pipeline");
  }

  const receiptResponse = await expectSuccess(
    app,
    authenticatedRequest(`/v1/refreshes/${REFRESH_ID}/receipt`),
    200,
  );
  const receipt = receiptResponse.data as Record<string, unknown>;
  if (
    receipt.status !== "FINAL" ||
    !flow.settled ||
    !flow.receiptFinal ||
    !flow.realtimePublished ||
    flow.refresh !== "COMPLETED"
  ) {
    throw new Error("headless backend flow did not close at final receipt");
  }
});

Deno.test("two independent identities complete ASK-to-receipt without crossing authority", async () => {
  const flow = {
    refresh: "NONE",
    claim: "NONE",
    evidence: "NONE",
    verified: false,
    projected: false,
    settled: false,
    receiptFinal: false,
    realtimePublished: false,
    discoveredByContributor: false,
  };

  const requester = {
    actorId: REQUESTER_ACTOR_ID,
    status: "ACTIVE" as const,
    revision: 1,
  };
  const contributor = {
    actorId: CONTRIBUTOR_ACTOR_ID,
    status: "ACTIVE" as const,
    revision: 1,
  };

  const authVerifier: AppDependencies["authVerifier"] = {
    verify(request) {
      const token = request.headers.get("authorization");
      if (token === "Bearer requester-session") {
        return Promise.resolve({
          authUserId: REQUESTER_AUTH_USER_ID,
          principalType: "SUPABASE_ANONYMOUS",
        });
      }
      if (token === "Bearer contributor-session") {
        return Promise.resolve({
          authUserId: CONTRIBUTOR_AUTH_USER_ID,
          principalType: "SUPABASE_ANONYMOUS",
        });
      }
      throw new ApiFault(401, "AUTH_INVALID", "The session is invalid or expired.");
    },
  };

  const identityRepository = {
    resolveActor(authUserId: string) {
      if (authUserId === REQUESTER_AUTH_USER_ID) return Promise.resolve(requester);
      if (authUserId === CONTRIBUTOR_AUTH_USER_ID) return Promise.resolve(contributor);
      throw new ApiFault(401, "AUTH_INVALID", "Unknown integration actor.");
    },
    listWalletBindings: () => Promise.resolve([]),
  } as unknown as AppDependencies["identityRepository"];

  const assertRequester = (actorId: string) => {
    if (actorId !== REQUESTER_ACTOR_ID) {
      throw new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
    }
  };
  const assertContributor = (actorId: string) => {
    if (actorId !== CONTRIBUTOR_ACTOR_ID) {
      throw new ApiFault(404, "CLAIM_NOT_FOUND", "Claim was not found.");
    }
  };

  const refreshCoordinator = {
    create(input: { actor: { actorId: string }; walletBindingId: string }) {
      assertRequester(input.actor.actorId);
      if (input.walletBindingId !== REQUESTER_WALLET_BINDING_ID) {
        throw new Error("requester funding used contributor wallet binding");
      }
      if (flow.refresh !== "NONE") throw new Error("refresh was created twice");
      flow.refresh = "DRAFT";
      return Promise.resolve({
        status: 201,
        data: { refresh_id: REFRESH_ID, state_id: STATE_ID, status: "DRAFT" },
      });
    },
    fundingIntent(input: { actor: { actorId: string } }) {
      assertRequester(input.actor.actorId);
      if (flow.refresh !== "DRAFT") throw new Error("funding intent was out of order");
      flow.refresh = "AWAITING_FUNDING";
      return Promise.resolve({
        refresh_id: REFRESH_ID,
        status: flow.refresh,
        operation_id: "41000000-0000-4000-8000-000000000002",
      });
    },
    observeFunding(input: { actor: { actorId: string } }) {
      assertRequester(input.actor.actorId);
      if (flow.refresh !== "AWAITING_FUNDING") {
        throw new Error("funding observation was out of order");
      }
      flow.refresh = "AVAILABLE";
      return Promise.resolve({ refresh_id: REFRESH_ID, status: flow.refresh });
    },
    get(actor: { actorId: string }) {
      assertRequester(actor.actorId);
      return Promise.resolve({ refresh_id: REFRESH_ID, status: flow.refresh });
    },
  } as unknown as NonNullable<AppDependencies["refreshCoordinator"]>;

  const opportunityMatcher = {
    nearby(actor: { actorId: string }) {
      if (actor.actorId === REQUESTER_ACTOR_ID) {
        return Promise.resolve({ items: [], next_cursor: null });
      }
      assertContributor(actor.actorId);
      if (flow.refresh !== "AVAILABLE") throw new Error("opportunity appeared before funding");
      flow.discoveredByContributor = true;
      return Promise.resolve({
        items: [{
          refresh_id: REFRESH_ID,
          state_id: STATE_ID,
          title: "Parking",
          claimable: true,
        }],
        next_cursor: null,
      });
    },
  } as unknown as NonNullable<AppDependencies["opportunityMatcher"]>;

  const claimCoordinator = {
    prepare(input: {
      actor: { actorId: string };
      walletBindingId: string;
    }) {
      if (input.actor.actorId === REQUESTER_ACTOR_ID) {
        throw new ApiFault(409, "CLAIM_NOT_AVAILABLE", "Requester cannot claim own refresh.");
      }
      assertContributor(input.actor.actorId);
      if (!flow.discoveredByContributor || flow.refresh !== "AVAILABLE") {
        throw new Error("claim bypassed contributor discovery");
      }
      if (input.walletBindingId !== CONTRIBUTOR_WALLET_BINDING_ID) {
        throw new Error("claim used requester wallet binding");
      }
      flow.claim = "WALLET_PENDING";
      return Promise.resolve({
        status: 201,
        data: {
          acceptance_id: ACCEPTANCE_ID,
          refresh_id: REFRESH_ID,
          status: flow.claim,
        },
      });
    },
    observe(input: { actor: { actorId: string } }) {
      assertContributor(input.actor.actorId);
      if (flow.claim !== "WALLET_PENDING") throw new Error("claim observation was out of order");
      flow.claim = "CLAIMED";
      flow.refresh = "CLAIMED";
      return Promise.resolve({
        acceptance_id: ACCEPTANCE_ID,
        refresh_id: REFRESH_ID,
        status: flow.claim,
      });
    },
    get(actor: { actorId: string }) {
      assertContributor(actor.actorId);
      return Promise.resolve({
        acceptance_id: ACCEPTANCE_ID,
        refresh_id: REFRESH_ID,
        status: flow.claim,
      });
    },
  } as unknown as NonNullable<AppDependencies["claimCoordinator"]>;

  const evidenceChallengeService = {
    issue(actor: { actorId: string }) {
      assertContributor(actor.actorId);
      if (flow.claim !== "CLAIMED") throw new Error("evidence challenge preceded claim");
      flow.claim = "CAPTURE_ACTIVE";
      flow.refresh = "CAPTURE_IN_PROGRESS";
      return Promise.resolve({
        challenge_id: CHALLENGE_ID,
        refresh_id: REFRESH_ID,
        acceptance_id: ACCEPTANCE_ID,
        nonce: "integration-nonce",
        expires_at: "2035-01-01T00:10:00.000Z",
      });
    },
  } as unknown as NonNullable<AppDependencies["evidenceChallengeService"]>;

  const evidenceUploadService = {
    authorize(input: { actor: { actorId: string } }) {
      assertContributor(input.actor.actorId);
      if (flow.claim !== "CAPTURE_ACTIVE") throw new Error("upload authorization was out of order");
      return Promise.resolve({
        evidence_id: EVIDENCE_ID,
        challenge_id: CHALLENGE_ID,
        upload_url: "https://storage.invalid/signed-upload",
      });
    },
  } as unknown as NonNullable<AppDependencies["evidenceUploadService"]>;

  const evidenceCommitService = {
    commit(input: { actor: { actorId: string } }) {
      assertContributor(input.actor.actorId);
      if (flow.claim !== "CAPTURE_ACTIVE") throw new Error("evidence commit was out of order");
      flow.evidence = "COMMITTED";
      flow.claim = "EVIDENCE_COMMITTED";
      flow.refresh = "EVIDENCE_SUBMITTED";
      return Promise.resolve({
        status: 201,
        data: {
          evidence_id: EVIDENCE_ID,
          refresh_id: REFRESH_ID,
          status: "COMMITTED",
        },
      });
    },
  } as unknown as NonNullable<AppDependencies["evidenceCommitService"]>;

  const verificationService = {
    verify(actor: { actorId: string }) {
      if (![REQUESTER_ACTOR_ID, CONTRIBUTOR_ACTOR_ID].includes(actor.actorId)) {
        throw new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
      }
      if (flow.evidence !== "COMMITTED" || flow.refresh !== "EVIDENCE_SUBMITTED") {
        throw new Error("verification preceded committed evidence");
      }
      flow.verified = true;
      flow.refresh = "VERIFIED";
      return Promise.resolve({
        status: 200,
        data: {
          result: "VERIFIED",
          refresh_id: REFRESH_ID,
          verification_result_id: VERIFICATION_ID,
        },
      });
    },
  } as unknown as NonNullable<AppDependencies["verificationService"]>;

  const stateProjectionService = {
    project(refreshId: string, verificationResultId: string) {
      if (
        !flow.verified ||
        refreshId !== REFRESH_ID ||
        verificationResultId !== VERIFICATION_ID
      ) {
        throw new Error("state projection lost verification authority");
      }
      flow.projected = true;
      return Promise.resolve({
        state_id: STATE_ID,
        refresh_id: REFRESH_ID,
        verification_result_id: VERIFICATION_ID,
        freshness: "LIVE",
      });
    },
  } as unknown as NonNullable<AppDependencies["stateProjectionService"]>;

  const paymentStatusService = {
    get(actor: { actorId: string }) {
      if (![REQUESTER_ACTOR_ID, CONTRIBUTOR_ACTOR_ID].includes(actor.actorId)) {
        throw new ApiFault(404, "PAYMENT_NOT_FOUND", "Payment status was not found.");
      }
      if (!flow.settled) throw new Error("payment read preceded settlement");
      return Promise.resolve({
        refresh_id: REFRESH_ID,
        verification_result_id: VERIFICATION_ID,
        settlement_id: "83000000-0000-4000-8000-000000000001",
        settlement_status: "FINALIZED",
        payment_status: "PAID",
        chain_signature: "settlement-signature",
        chain_commitment: "finalized",
        confirmed_at: "2035-01-01T00:11:00.000Z",
        finalized_at: "2035-01-01T00:12:00.000Z",
        updated_at: "2035-01-01T00:12:00.000Z",
      });
    },
  } as unknown as NonNullable<AppDependencies["paymentStatusService"]>;

  const receiptService = {
    get(actor: { actorId: string }) {
      if (![REQUESTER_ACTOR_ID, CONTRIBUTOR_ACTOR_ID].includes(actor.actorId)) {
        throw new ApiFault(404, "RECEIPT_NOT_FOUND", "Receipt was not found.");
      }
      if (!flow.receiptFinal) throw new Error("receipt read preceded finalization");
      return Promise.resolve({
        receipt_id: RECEIPT_ID,
        refresh_id: REFRESH_ID,
        state_id: STATE_ID,
        verification_result_id: VERIFICATION_ID,
        status: "FINAL",
      });
    },
  } as unknown as NonNullable<AppDependencies["receiptService"]>;

  const app = createApp({
    authVerifier,
    identityRepository,
    refreshCoordinator,
    opportunityMatcher,
    claimCoordinator,
    evidenceChallengeService,
    evidenceUploadService,
    evidenceCommitService,
    verificationService,
    stateProjectionService,
    paymentStatusService,
    receiptService,
  });

  const requesterCall = (path: string, init: RequestInit = {}) =>
    app(authenticatedRequest(path, init, "requester-session"));
  const contributorCall = (path: string, init: RequestInit = {}) =>
    app(authenticatedRequest(path, init, "contributor-session"));

  const create = await requesterCall("/v1/refreshes", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "idempotency-key": "two-actor-create",
    },
    body: JSON.stringify({
      state_id: STATE_ID,
      wallet_binding_id: REQUESTER_WALLET_BINDING_ID,
      funding_target_atomic: "1000",
    }),
  });
  if (create.status !== 201) throw new Error("requester could not create refresh");

  const contributorFundingProbe = await contributorCall(
    `/v1/refreshes/${REFRESH_ID}/funding-intent`,
    { method: "POST", headers: { "idempotency-key": "wrong-funder" } },
  );
  if (contributorFundingProbe.status !== 404) {
    throw new Error("contributor crossed requester funding authority");
  }

  await expectSuccess(
    app,
    authenticatedRequest(
      `/v1/refreshes/${REFRESH_ID}/funding-intent`,
      { method: "POST", headers: { "idempotency-key": "two-actor-funding" } },
      "requester-session",
    ),
    200,
  );
  await expectSuccess(
    app,
    authenticatedRequest(
      `/v1/refreshes/${REFRESH_ID}/funding-observe`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": "two-actor-funding-observe",
        },
        body: JSON.stringify({ signature: "requester-funding-signature" }),
      },
      "requester-session",
    ),
    200,
  );

  const requesterDiscovery = await requesterCall(
    "/v1/opportunities/nearby?lat=28&lng=77&radius_m=2000",
  );
  const requesterDiscoveryBody = await requesterDiscovery.json() as {
    data?: { items?: unknown[] };
  };
  if (requesterDiscoveryBody.data?.items?.length !== 0) {
    throw new Error("requester discovered its own opportunity");
  }

  const contributorDiscovery = await contributorCall(
    "/v1/opportunities/nearby?lat=28.01&lng=77&radius_m=2000",
  );
  const contributorDiscoveryBody = await contributorDiscovery.json() as {
    data?: { items?: Array<Record<string, unknown>> };
  };
  if (
    contributorDiscovery.status !== 200 ||
    contributorDiscoveryBody.data?.items?.[0]?.refresh_id !== REFRESH_ID
  ) {
    throw new Error("contributor did not discover funded opportunity");
  }

  const selfClaim = await requesterCall(`/v1/opportunities/${REFRESH_ID}/claim`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "idempotency-key": "self-claim-probe",
    },
    body: JSON.stringify({ wallet_binding_id: REQUESTER_WALLET_BINDING_ID }),
  });
  const selfClaimBody = await selfClaim.json() as {
    error?: { code?: string };
  };
  if (selfClaim.status !== 409 || selfClaimBody.error?.code !== "CLAIM_NOT_AVAILABLE") {
    throw new Error("requester self-claim was not rejected");
  }

  await expectSuccess(
    app,
    authenticatedRequest(
      `/v1/opportunities/${REFRESH_ID}/claim`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": "two-actor-claim",
        },
        body: JSON.stringify({ wallet_binding_id: CONTRIBUTOR_WALLET_BINDING_ID }),
      },
      "contributor-session",
    ),
    201,
  );

  const requesterClaimProbe = await requesterCall(`/v1/claims/${ACCEPTANCE_ID}`);
  if (requesterClaimProbe.status !== 404) {
    throw new Error("requester crossed contributor claim authority");
  }

  await expectSuccess(
    app,
    authenticatedRequest(
      `/v1/claims/${ACCEPTANCE_ID}/observe`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ signature: "contributor-claim-signature" }),
      },
      "contributor-session",
    ),
    200,
  );
  await expectSuccess(
    app,
    authenticatedRequest(
      `/v1/claims/${ACCEPTANCE_ID}/challenge`,
      { method: "POST" },
      "contributor-session",
    ),
    201,
  );
  await expectSuccess(
    app,
    authenticatedRequest(
      `/v1/evidence-challenges/${CHALLENGE_ID}/upload`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          nonce: "integration-nonce",
          media_mime: "image/jpeg",
        }),
      },
      "contributor-session",
    ),
    201,
  );
  await expectSuccess(
    app,
    authenticatedRequest(
      `/v1/evidence/${EVIDENCE_ID}/commit`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": "two-actor-evidence",
        },
        body: JSON.stringify({
          answer_value: { kind: "numeric", scaled_value: "2", scale: 0 },
        }),
      },
      "contributor-session",
    ),
    201,
  );

  const verification = await expectSuccess(
    app,
    authenticatedRequest(
      `/v1/refreshes/${REFRESH_ID}/verify`,
      { method: "POST" },
      "requester-session",
    ),
    200,
  );
  const projection = (verification.data as Record<string, unknown>)
    .state_projection as Record<string, unknown>;
  if (projection.freshness !== "LIVE" || !flow.projected) {
    throw new Error("two-actor verification did not project a LIVE state");
  }

  const settlement = {
    runOnce() {
      if (!flow.verified || !flow.projected) throw new Error("settlement ran before LIVE state");
      flow.settled = true;
      return Promise.resolve({
        prepared: 1,
        signed: 1,
        submitted: 1,
        ambiguous: 0,
        pending: 0,
        confirmed: 0,
        finalized: 1,
        safeRetries: 0,
        conflicts: 0,
        deferred: 0,
      });
    },
  } as unknown as SettlementCoordinator;
  const receipts = {
    runOnce() {
      if (!flow.settled) throw new Error("receipt finalization preceded settlement");
      flow.receiptFinal = true;
      flow.refresh = "COMPLETED";
      return Promise.resolve({ finalized: 1, replayed: 0, conflicts: 0 });
    },
  } as unknown as ReceiptCoordinator;
  const realtime = {
    runOnce() {
      if (!flow.receiptFinal) throw new Error("realtime publication preceded receipt");
      flow.realtimePublished = true;
      return Promise.resolve({ published: 2 });
    },
  } as unknown as RealtimePublisher;

  const worker = createSettlementWorkerHandler(
    new NowWorkerCoordinator(settlement, receipts, realtime),
    WORKER_TOKEN,
  );
  const workerResponse = await worker(
    new Request("https://worker.invalid/", {
      method: "POST",
      headers: { authorization: `Bearer ${WORKER_TOKEN}` },
    }),
  );
  if (workerResponse.status !== 200) throw new Error("two-actor settlement worker failed");

  for (const token of ["requester-session", "contributor-session"]) {
    const payment = await app(
      authenticatedRequest(`/v1/refreshes/${REFRESH_ID}/payment`, {}, token),
    );
    const receipt = await app(
      authenticatedRequest(`/v1/refreshes/${REFRESH_ID}/receipt`, {}, token),
    );
    if (payment.status !== 200 || receipt.status !== 200) {
      throw new Error("authorized lifecycle participant lost payment or receipt visibility");
    }
  }

  if (
    flow.refresh !== "COMPLETED" ||
    !flow.settled ||
    !flow.receiptFinal ||
    !flow.realtimePublished
  ) {
    throw new Error("two-actor lifecycle did not close cleanly");
  }
});

