# Native account and discovery surfaces

The Android client uses a shared light/dark Compose design system, native MapLibre maps,
account-owned activity and an in-app inbox. Funding, claim, evidence, settlement and receipt
repositories remain the authority for transaction actions. A displayed reward pool is distinct
from a finalized personal payout and from the connected wallet balance.

## Deployment order

1. Apply `20261003152110_native_experience.sql`, then
   `20261003161302_experience_notification_events.sql` after the existing migrations.
2. Deploy `now-api` and `now-worker` from the same revision. Retain the existing evidence
   bucket, transaction pooler and worker scheduling configuration.
3. Build Android with the existing public runtime configuration. The original brand resource
   is required for the build, as before.

The migrations add actor profiles/preferences, installations, a durable inbox and push-delivery
records. Their `app` tables have RLS enabled and no client-role grants. Access goes through
authenticated API handlers that scope records to the resolved actor. Device revocation is checked
before every private API operation, including when an access token has not yet expired.

The `now-avatars` bucket is private. The API accepts bounded PNG uploads and strips ancillary
metadata. Avatar and authorized evidence previews use 60-second signed URLs; evidence remains
available only to the submitter, requester or confirmed funding participant. Public state reads
never return evidence object keys or private proof coordinates.

Discovery lists follow server cursors, retain existing cards on a failed tail load, and discard
late pages when the area or actor changes. EARN pins active contributor work above discovery
results and keeps locally saved claims reachable while offline. Existing claim recovery runs
before a new opportunity lookup, since an already claimed refresh is excluded from opportunities.
Committed proof opens verification; an expired or released claim cannot start another signature.
Payout estimates apply the locked witness split and are labeled separately from reward pools.

NOW searches the selected area before pagination and exposes live, aging, stale, unobserved and
conflict filters. EARN applies category and nearest/ending/payout order before its page limit.
Payout order uses the floored required-witness estimate and groups different token mints separately;
it does not convert token units into a shared cash value. Area totals come from the matching
server query. Cursors bind the area and filters, and private opportunity cursors also bind the
actor. Changing a filter starts a new first page. Offline snapshots retain the accepted server
order without presenting an unknown payout estimate as a guaranteed reward.

Map rings identify the selected browse center. They pause during gestures and when motion is
reduced. Pending verification has a blue orbital treatment; finalized proof and receipt reveals
are recorded per result so returning to the same result does not replay the celebration.

## Push configuration

The in-app inbox works without Firebase. To enable device delivery, configure the Android build
with the public values `NOW_FIREBASE_APP_ID`, `NOW_FIREBASE_PROJECT_ID`, `NOW_FIREBASE_API_KEY`
and `NOW_FIREBASE_SENDER_ID` from the matching Android Firebase application. Configure the
server-only `NOW_FCM_SERVICE_ACCOUNT_JSON` secret for the worker and API capability flag.
Never package this service-account JSON, its private key or a Supabase service key in Android.

Delivery additionally requires Android notification permission and an authenticated installation
with an FCM token. Category preferences, selected opportunity areas, quiet hours and timezone
apply on the server. Revoked devices, inactive actors, read notifications and events older than
24 hours are skipped. Transport errors retry with backoff; invalid tokens are removed. Generic
lock-screen messages open the inbox, which fetches content under the current account. The durable
delivery record prevents normal repeated worker ticks from resending a completed delivery;
FCM/network ambiguity is not an exactly-once delivery guarantee.

## Local verification

The normal backend test task covers contracts without requiring a database. The database CI job
also runs the actor-isolation, area-filtering, revocation and notification-delivery integration test
against the freshly migrated PostGIS service:

```bash
NOW_TEST_DB_URL=postgres://postgres:postgres@127.0.0.1:5432/now_test \
  deno test --allow-env --allow-net=127.0.0.1:5432 backend/tests/experience_database_test.ts backend/tests/discovery_database_test.ts
```

Use a disposable test database. The test inserts and removes its own actor-owned fixtures. The
debug-only Experience Lab exposes deterministic transaction states for layout inspection;
fixtures and its launcher are excluded from release builds. Required Android CI runs the existing
unit, persistence and accessibility checks, including nested settings back-stack regression coverage.
Screenshot generation and artifact transport are not part of that job. The optional
`scripts/android/capture-visuals.sh` helper is for a separately provisioned, rendered Android device;
ATD images do not render screenshot evidence.

For a connected phone build, run `bash scripts/android/build-connected.sh`. Android builds default to
`NOW_RUNTIME=hosted` and load `apps/android/hosted-runtime.properties`, which contains only
the hosted endpoint and public client configuration. Gradle properties and environment variables
override those defaults; retain any reward-mint configuration for the selected deployment.
Missing URLs, non-HTTPS endpoints and non-publishable client keys fail Gradle configuration.
The script also rejects APKs missing the hosted public configuration. CI unit and instrumented
test builds explicitly use `NOW_RUNTIME=offline`; use `-PNOW_RUNTIME=offline` for isolated local tests.

The Android job also builds `NOW-connected-debug`, a downloadable APK artifact with its source
commit and SHA-256. APK upload has a three-minute timeout and is optional; a transport failure
does not change test results. On the runner, the connected APK remains at
`$RUNNER_TEMP/now-phone/NOW-connected-debug.apk`. This is a debug phone build, not a signed
release candidate. The required instrumented tests run separately with their normal configuration.

Deploy the matching migrations and API/worker before testing the new account and inbox routes.
ASK also needs all pending migrations, including `20261004164414_dynamic_ask_coverage.sql` and
`20261005090600_ask_custom_questions.sql`, followed by deployment of both `now-api` and `now-worker`
from the same commit. Installing an APK alone does not update these services.
A connected build does not populate the location catalog: real locations and state definitions
must be added before nearby results exist. Device location centers the map independently.
Local appearance and permission settings do not require an account request.

Before shipping, exercise physical camera capture, wallet handoff, device-credential lock,
permission changes, background push delivery and requester/contributor operation on separate
phones. Emulator timings do not establish device smoothness, and a source build does not establish
that the new API and migrations have been deployed.

Short video proof
-----------------

New requests lock a fresh photo followed by a silent 3–15 second video into their proof policy.
Existing funded requests retain their stored capture requirements. Video capture uses the rear
camera, targets 720p at 2 Mbps, stops automatically before 15 seconds and limits the file to 6 MiB.
Contributors can review both captures and retake the video before submission. A lost connection
keeps the original files and reconciles the same evidence identity before any retry.

Apply `20261005102427_evidence_short_video.sql` before deploying the API and worker and installing
the matching Android build. The migration adds immutable clip metadata, exact-video replay
protection and MP4 support to the existing private `now-evidence` bucket. Custom evidence buckets
must also allow `video/mp4`; they must remain private. The server hashes the uploaded video and
validates its MP4 video-track duration before committing the photo and clip together.
Authorized participants receive short-lived photo and video URLs through the existing proof route.
Map thumbnails use only those authorized verified photos; they do not expose private evidence to
other users. The map's external Maps action opens the selected area in an installed maps app.
