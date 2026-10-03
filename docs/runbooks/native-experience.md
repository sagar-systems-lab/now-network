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
fixtures and its launcher are excluded from release builds. The CI artifact `android-visuals`
contains rendered debug APK screenshots and accessibility hierarchies in both themes at 390 dp,
with additional 360/412 dp and 200% font probes. Captures use production composables with read-only
fixtures and do not certify live services, physical camera quality or device frame timings.

Before shipping, exercise physical camera capture, wallet handoff, device-credential lock,
permission changes, background push delivery and requester/contributor operation on separate
phones. Emulator timings do not establish device smoothness, and a source build does not establish
that the new API and migrations have been deployed.
