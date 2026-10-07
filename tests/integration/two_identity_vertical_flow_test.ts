import { type AppDependencies, createApp } from "../../backend/src/app.ts";
import { ApiFault } from "../../backend/src/errors.ts";
import { NowWorkerCoordinator } from "../../backend/src/now-worker-coordinator.ts";
import type { ReceiptCoordinator } from "../../backend/src/receipt-coordinator.ts";
import type { RealtimePublisher } from "../../backend/src/realtime-outbox.ts";
import type { SettlementCoordinator } from "../../backend/src/settlement-coordinator.ts";
import { createSettlementWorkerHandler } from "../../backend/src/settlement-worker.ts";

const REQUESTER_ACTOR = "11000000-0000-4000-8000-000000000001";
const REQUESTER_AUTH = "11000000-0000-4000-8000-000000000002";
const CONTRIBUTOR_ACTOR = "12000000-0000-4000-8000-000000000001";
const CONTRIBUTOR_AUTH = "12000000-0000-4000-8000-000000000002";
const REQUESTER_WALLET = "31000000-0000-4000-8000-000000000001";
const CONTRIBUTOR_WALLET = "32000000-0000-4000-8000-000000000001";
const STATE_ID = "20000000-0000-4000-8000-000000000001";
const REFRESH_ID = "40000000-0000-4000-8000-000000000001";
const ACCEPTANCE_ID = "50000000-0000-4000-8000-000000000001";
const CHALLENGE_ID = "60000000-0000-4000-8000-000000000001";
const EVIDENCE_ID = "70000000-0000-4000-8000-000000000001";
const VERIFICATION_ID = "80000000-0000-4000-8000-000000000001";
const RECEIPT_ID = "90000000-0000-4000-8000-000000000001";
const WORKER_TOKEN = "two-identity-worker-token-0123456789abcdef";

function request(
  path: string,
  token: "requester" | "contributor",
  init: RequestInit = {},
): Request {
  const headers = new Headers(init.headers);
  headers.set("authorization", `Bearer ${token}`);
  return new Request(`https://now.invalid${path}`, { ...init, headers });
}

async function expectStatus(
  response: Response,
  expected: number,
): Promise<Record<string, unknown>> {
  const body = await response.json() as Record<string, unknown>;
  if (response.status !== expected) {
    throw new Error(
      `unexpected response ${response.status}, wanted ${expected}: ${JSON.stringify(body)}`,
    );
  }
  return body;
}

Deno.test("two identities complete ASK-to-receipt while role boundaries stay isolated", async () => {
  const flow = {
    refresh: "NONE",
    claim: "NONE",
    evidence: "NONE",
    verified: false,
    projected: false,
    settled: false,
    receiptFinal: false,
    realtimePublished: false,
    contributorDiscovered: false,
  };

  const requester = { actorId: REQUESTER_ACTOR, status: "ACTIVE" as const, revision: 1 };
  const contributor = { actorId: CONTRIBUTOR_ACTOR, status: "ACTIVE" as const, revision: 1 };

  const authVerifier: AppDependencies["authVerifier"] = {
    verify(req) {
      const token = req.headers.get("authorization");
      if (token === "Bearer requester") {
        return Promise.resolve({
          authUserId: REQUESTER_AUTH,
          principalType: "SUPABASE_ANONYMOUS",
        });
      }
      if (token === "Bearer contributor") {
        return Promise.resolve({
          authUserId: CONTRIBUTOR_AUTH,
          principalType: "SUPABASE_ANONYMOUS",
        });
      }
      throw new ApiFault(401, "AUTH_INVALID", "Unknown integration session.");
    },
  };

  const identityRepository = {
    resolveActor(authUserId: string) {
      if (authUserId === REQUESTER_AUTH) return Promise.resolve(requester);
      if (authUserId === CONTRIBUTOR_AUTH) return Promise.resolve(contributor);
      throw new ApiFault(401, "AUTH_INVALID", "Unknown integration actor.");
    },
    listWalletBindings: () => Promise.resolve([]),
  } as unknown as AppDependencies["identityRepository"];

  const requesterOnly = (actorId: string) => {
    if (actorId !== REQUESTER_ACTOR) {
      throw new ApiFault(404, "REFRESH_NOT_FOUND", "Refresh was not found.");
    }
  };
  const contributorOnly = (actorId: string) => {
    if (actorId !== CONTRIBUTOR_ACTOR) {
      throw new ApiFault(404, "CLAIM_NOT_FOUND", "Claim was not found.");
    }
  };

  const refreshCoordinator = {
    create(input: { actor: { actorId: string }; walletBindingId: string }) {
      requesterOnly(input.actor.actorId);
      if (input.walletBindingId !== REQUESTER_WALLET) {
        throw new Error("requester refresh used the wrong wallet binding");
      }
      if (flow.refresh !== "NONE") throw new Error("refresh was created twice");
      flow.refresh = "DRAFT";
      return Promise.resolve({
        status: 201,
        data: { refresh_id: REFRESH_ID, state_id: STATE_ID, status: flow.refresh },
      });
    },
    fundingIntent(input: { actor: { actorId: string } }) {
      requesterOnly(input.actor.actorId);
      if (flow.refresh !== "DRAFT") throw new Error("funding intent was out of order");
      flow.refresh = "AWAITING_FUNDING";
      return Promise.resolve({
        refresh_id: REFRESH_ID,
        status: flow.refresh,
        operation_id: "41000000-0000-4000-8000-000000000002",
      });
    },
    observeFunding(input: { actor: { actorId: string } }) {
      requesterOnly(input.actor.actorId);
      if (flow.refresh !== "AWAITING_FUNDING") {
        throw new Error("funding observation was out of order");
      }
      flow.refresh = "AVAILABLE";
      return Promise.resolve({ refresh_id: REFRESH_ID, status: flow.refresh });
    },
    get(actor: { actorId: string }) {
      requesterOnly(actor.actorId);
      return Promise.resolve({ refresh_id: REFRESH_ID, status: flow.refresh });
    },
  } as unknown as NonNullable<AppDependencies["refreshCoordinator"]>;

  const opportunityMatcher = {
    nearby(actor: { actorId: string }) {
      if (actor.actorId === REQUESTER_ACTOR) {
        return Promise.resolve({ items: [], next_cursor: null });
      }
      contributorOnly(actor.actorId);
      if (flow.refresh !== "AVAILABLE") throw new Error("opportunity appeared before funding");
      flow.contributorDiscovered = true;
      return Promise.resolve({
        items: [{ refresh_id: REFRESH_ID, state_id: STATE_ID, title: "Parking" }],
        next_cursor: null,
      });
    },
  } as unknown as NonNullable<AppDependencies["opportunityMatcher"]>;

  const claimCoordinator = {
    prepare(input: { actor: { actorId: string }; walletBindingId: string }) {
      if (input.actor.actorId === REQUESTER_ACTOR) {
        throw new ApiFault(409, "CLAIM_NOT_AVAILABLE", "Requester cannot claim own refresh.");
      }
      contributorOnly(input.actor.actorId);
      if (!flow.contributorDiscovered || flow.refresh !== "AVAILABLE") {
        throw new Error("claim bypassed contributor discovery");
      }
      if (input.walletBindingId !== CONTRIBUTOR_WALLET) {
        throw new Error("contributor claim used the wrong wallet binding");
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
      contributorOnly(input.actor.actorId);
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
      contributorOnly(actor.actorId);
      return Promise.resolve({
        acceptance_id: ACCEPTANCE_ID,
        refresh_id: REFRESH_ID,
        status: flow.claim,
      });
    },
  } as unknown as NonNullable<AppDependencies["claimCoordinator"]>;

  const evidenceChallengeService = {
    issue(actor: { actorId: string }) {
      contributorOnly(actor.actorId);
      if (flow.claim !== "CLAIMED") throw new Error("challenge preceded confirmed claim");
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
      contributorOnly(input.actor.actorId);
      if (flow.claim !== "CAPTURE_ACTIVE") throw new Error("upload preceded capture state");
      return Promise.resolve({
        evidence_id: EVIDENCE_ID,
        challenge_id: CHALLENGE_ID,
        upload_url: "https://storage.invalid/signed-upload",
      });
    },
  } as unknown as NonNullable<AppDependencies["evidenceUploadService"]>;

  const evidenceCommitService = {
    commit(input: { actor: { actorId: string } }) {
      contributorOnly(input.actor.actorId);
      if (flow.claim !== "CAPTURE_ACTIVE") throw new Error("commit preceded capture state");
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
      if (![REQUESTER_ACTOR, CONTRIBUTOR_ACTOR].includes(actor.actorId)) {
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

  const participantOnly = (actorId: string, code: string) => {
    if (![REQUESTER_ACTOR, CONTRIBUTOR_ACTOR].includes(actorId)) {
      throw new ApiFault(404, code, "Lifecycle record was not found.");
    }
  };

  const paymentStatusService = {
    get(actor: { actorId: string }) {
      participantOnly(actor.actorId, "PAYMENT_NOT_FOUND");
      if (!flow.settled) throw new Error("payment was read before settlement");
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
      participantOnly(actor.actorId, "RECEIPT_NOT_FOUND");
      if (!flow.receiptFinal) throw new Error("receipt was read before finalization");
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

  await expectStatus(
    await app(
      request("/v1/refreshes", "requester", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": "requester-create",
        },
        body: JSON.stringify({
          state_id: STATE_ID,
          wallet_binding_id: REQUESTER_WALLET,
          funding_target_atomic: "1000",
        }),
      }),
    ),
    201,
  );

  await expectStatus(
    await app(
      request(`/v1/refreshes/${REFRESH_ID}/funding-intent`, "contributor", {
        method: "POST",
        headers: { "idempotency-key": "wrong-funder" },
      }),
    ),
    404,
  );

  await expectStatus(
    await app(
      request(`/v1/refreshes/${REFRESH_ID}/funding-intent`, "requester", {
        method: "POST",
        headers: { "idempotency-key": "requester-funding" },
      }),
    ),
    200,
  );
  await expectStatus(
    await app(
      request(`/v1/refreshes/${REFRESH_ID}/funding-observe`, "requester", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": "requester-funding-observe",
        },
        body: JSON.stringify({ signature: "requester-funding-signature" }),
      }),
    ),
    200,
  );

  const ownDiscovery = await expectStatus(
    await app(
      request("/v1/opportunities/nearby?lat=28&lng=77&radius_m=2000", "requester"),
    ),
    200,
  );
  const ownItems = (ownDiscovery.data as { items?: unknown[] }).items ?? [];
  if (ownItems.length !== 0) throw new Error("requester discovered its own opportunity");

  const discovery = await expectStatus(
    await app(
      request("/v1/opportunities/nearby?lat=28.01&lng=77&radius_m=2000", "contributor"),
    ),
    200,
  );
  const items = (discovery.data as { items?: Array<Record<string, unknown>> }).items ?? [];
  if (items[0]?.refresh_id !== REFRESH_ID) {
    throw new Error("contributor did not discover the funded opportunity");
  }

  const selfClaim = await expectStatus(
    await app(
      request(`/v1/opportunities/${REFRESH_ID}/claim`, "requester", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": "self-claim",
        },
        body: JSON.stringify({ wallet_binding_id: REQUESTER_WALLET }),
      }),
    ),
    409,
  );
  if ((selfClaim.error as { code?: string }).code !== "CLAIM_NOT_AVAILABLE") {
    throw new Error("self-claim failed with the wrong authority result");
  }

  await expectStatus(
    await app(
      request(`/v1/opportunities/${REFRESH_ID}/claim`, "contributor", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": "contributor-claim",
        },
        body: JSON.stringify({ wallet_binding_id: CONTRIBUTOR_WALLET }),
      }),
    ),
    201,
  );

  await expectStatus(
    await app(request(`/v1/claims/${ACCEPTANCE_ID}`, "requester")),
    404,
  );

  await expectStatus(
    await app(
      request(`/v1/claims/${ACCEPTANCE_ID}/observe`, "contributor", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ signature: "contributor-claim-signature" }),
      }),
    ),
    200,
  );
  await expectStatus(
    await app(
      request(`/v1/claims/${ACCEPTANCE_ID}/challenge`, "contributor", {
        method: "POST",
      }),
    ),
    201,
  );
  await expectStatus(
    await app(
      request(`/v1/evidence-challenges/${CHALLENGE_ID}/upload`, "contributor", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          nonce: "integration-nonce",
          media_mime: "image/jpeg",
        }),
      }),
    ),
    201,
  );
  await expectStatus(
    await app(
      request(`/v1/evidence/${EVIDENCE_ID}/commit`, "contributor", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": "contributor-evidence",
        },
        body: JSON.stringify({
          answer_value: { kind: "numeric", scaled_value: "2", scale: 0 },
        }),
      }),
    ),
    201,
  );

  const verification = await expectStatus(
    await app(
      request(`/v1/refreshes/${REFRESH_ID}/verify`, "requester", {
        method: "POST",
      }),
    ),
    200,
  );
  const verifiedData = verification.data as Record<string, unknown>;
  const projection = verifiedData.state_projection as Record<string, unknown>;
  if (projection.freshness !== "LIVE" || !flow.projected) {
    throw new Error("verified evidence did not produce a LIVE state");
  }

  const settlement = {
    runOnce() {
      if (!flow.verified || !flow.projected) throw new Error("settlement preceded verification");
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
  if (workerResponse.status !== 200) throw new Error("settlement worker failed");

  for (const actor of ["requester", "contributor"] as const) {
    await expectStatus(
      await app(request(`/v1/refreshes/${REFRESH_ID}/payment`, actor)),
      200,
    );
    const receipt = await expectStatus(
      await app(request(`/v1/refreshes/${REFRESH_ID}/receipt`, actor)),
      200,
    );
    if ((receipt.data as Record<string, unknown>).status !== "FINAL") {
      throw new Error("authorized participant did not receive a final receipt");
    }
  }

  if (
    flow.refresh !== "COMPLETED" ||
    !flow.settled ||
    !flow.receiptFinal ||
    !flow.realtimePublished
  ) {
    throw new Error("two-identity lifecycle did not close cleanly");
  }
});
