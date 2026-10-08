package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AppSession
import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.data.local.PendingEvidenceDao
import com.sagarsystemslab.nownetwork.data.local.PendingEvidenceEntity
import com.sagarsystemslab.nownetwork.data.local.PendingEvidenceStatus
import com.sagarsystemslab.nownetwork.evidence.EvidenceFileStore
import com.sagarsystemslab.nownetwork.evidence.EvidenceLocationSample
import com.sagarsystemslab.nownetwork.evidence.EvidenceObjectUploadFailure
import com.sagarsystemslab.nownetwork.evidence.EvidenceObjectUploader
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.ClaimStatusDto
import com.sagarsystemslab.nownetwork.network.EvidenceApiClient
import com.sagarsystemslab.nownetwork.network.EvidenceCapturePolicyDto
import com.sagarsystemslab.nownetwork.network.EvidenceChallengeDto
import com.sagarsystemslab.nownetwork.network.EvidenceCommitDto
import com.sagarsystemslab.nownetwork.network.EvidenceCommitRequest
import com.sagarsystemslab.nownetwork.network.EvidenceCommittedMediaDto
import com.sagarsystemslab.nownetwork.network.EvidenceReadApiClient
import com.sagarsystemslab.nownetwork.network.EvidenceUploadAuthorizationDto
import com.sagarsystemslab.nownetwork.network.EvidenceUploadAuthorizeRequest
import com.sagarsystemslab.nownetwork.network.EvidenceUploadTargetDto
import com.sagarsystemslab.nownetwork.network.OpportunityAvailabilityDto
import com.sagarsystemslab.nownetwork.network.OpportunityDto
import com.sagarsystemslab.nownetwork.network.OpportunityEvidenceSummaryDto
import com.sagarsystemslab.nownetwork.network.OpportunityLocationDto
import com.sagarsystemslab.nownetwork.network.OpportunityRewardDto
import com.sagarsystemslab.nownetwork.security.SecureSecretStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceCaptureRepositoryTest {
    @Test
    fun photoAndVideoUseOneAuthorizationBeforeEitherUpload() = runBlocking {
        val fixture = Fixture()
        val evidenceId = fixture.prepareCapturedVideoEvidence()
        fixture.api.rejectAuthorizationAfterVideo = true
        val result = fixture.repository.submit(evidenceId)
        assertEquals(evidenceId, result.evidence.evidenceId)
        assertEquals(2, fixture.api.authorizeCalls) // Initial reservation, then one submission authorization.
        assertEquals(1, fixture.uploader.videoCalls)
        assertEquals(1, fixture.uploader.photoCalls)
        assertEquals(PendingEvidenceStatus.COMMITTED, fixture.dao.get(evidenceId)?.uploadState)
    }

    @Test
    fun photoUploadRetryAfterRestartPreservesTheCompletedVideo() = runBlocking {
        val fixture = Fixture()
        val evidenceId = fixture.prepareCapturedVideoEvidence()
        fixture.uploader.failPhotoOnce = true
        assertTrue(runCatching { fixture.repository.submit(evidenceId) }.exceptionOrNull() is EvidenceCaptureFailure.Network)
        assertTrue(fixture.remote.videoUploaded)
        assertTrue(!fixture.remote.uploaded)
        val result = fixture.recreatedRepository().resumeSubmission(evidenceId)
        assertNotNull(result)
        assertEquals(1, fixture.uploader.videoCalls)
        assertEquals(2, fixture.uploader.photoCalls)
        assertEquals(PendingEvidenceStatus.COMMITTED, fixture.dao.get(evidenceId)?.uploadState)
    }

    @Test
    fun storageInspectionRetryDoesNotEraseTheVideoUploadAcknowledgement() = runBlocking {
        val fixture = Fixture()
        val evidenceId = fixture.prepareCapturedVideoEvidence()
        fixture.api.storageFailuresRemaining = 2
        assertTrue(runCatching { fixture.repository.submit(evidenceId) }.exceptionOrNull() is EvidenceCaptureFailure.UploadNotReady)
        assertNotNull(fixture.recreatedRepository().resumeSubmission(evidenceId))
        assertEquals(1, fixture.uploader.videoCalls)
        assertEquals(2, fixture.uploader.photoCalls)
        assertEquals(PendingEvidenceStatus.COMMITTED, fixture.dao.get(evidenceId)?.uploadState)
    }

    @Test
    fun ambiguousUploadThatReachedStorageCommitsBeforeAnyResend() = runBlocking {
        val fixture = Fixture()
        val evidenceId = fixture.prepareCapturedEvidence()

        fixture.uploader.mode = UploadMode.WRITE_THEN_NETWORK_ERROR
        val firstError = runCatching {
            fixture.repository.submit(evidenceId)
        }.exceptionOrNull()

        assertTrue(firstError is EvidenceCaptureFailure.Network)
        assertEquals(
            PendingEvidenceStatus.UPLOADING,
            fixture.dao.get(evidenceId)?.uploadState,
        )
        assertTrue(fixture.remote.uploaded)
        assertEquals(1, fixture.uploader.calls)

        val recovered = fixture.recreatedRepository().resumeSubmission(evidenceId)

        assertNotNull(recovered)
        assertEquals(evidenceId, recovered?.evidence?.evidenceId)
        assertEquals(1, fixture.uploader.calls)
        assertEquals(1, fixture.api.commitCalls)
        assertEquals(
            PendingEvidenceStatus.COMMITTED,
            fixture.dao.get(evidenceId)?.uploadState,
        )
    }

    @Test
    fun ambiguousUploadThatDidNotReachStorageReusesReservationAndRetriesUpload() = runBlocking {
        val fixture = Fixture()
        val evidenceId = fixture.prepareCapturedEvidence()

        fixture.uploader.mode = UploadMode.FAIL_BEFORE_WRITE
        val firstError = runCatching {
            fixture.repository.submit(evidenceId)
        }.exceptionOrNull()

        assertTrue(firstError is EvidenceCaptureFailure.Network)
        assertEquals(1, fixture.uploader.calls)
        assertTrue(!fixture.remote.uploaded)

        fixture.uploader.mode = UploadMode.SUCCESS
        val recovered = fixture.recreatedRepository().resumeSubmission(evidenceId)

        assertNotNull(recovered)
        assertEquals(evidenceId, recovered?.evidence?.evidenceId)
        assertEquals(2, fixture.uploader.calls)
        assertEquals(2, fixture.api.commitCalls)
        assertEquals(EVIDENCE_ID, fixture.api.lastAuthorizedEvidenceId)
        assertEquals(
            PendingEvidenceStatus.COMMITTED,
            fixture.dao.get(evidenceId)?.uploadState,
        )
    }

    @Test
    fun ambiguousCommitReplaysWithoutReuploadingMedia() = runBlocking {
        val fixture = Fixture()
        val evidenceId = fixture.prepareCapturedEvidence()

        fixture.api.ambiguousCommitOnce = true
        val firstError = runCatching {
            fixture.repository.submit(evidenceId)
        }.exceptionOrNull()

        assertTrue(firstError is EvidenceCaptureFailure.Network)
        assertTrue(fixture.remote.committed)
        assertEquals(1, fixture.uploader.calls)
        assertEquals(
            PendingEvidenceStatus.COMMITTING,
            fixture.dao.get(evidenceId)?.uploadState,
        )

        val recovered = fixture.recreatedRepository().resumeSubmission(evidenceId)

        assertNotNull(recovered)
        assertTrue(recovered?.evidence?.replayed == true)
        assertEquals(1, fixture.uploader.calls)
        assertEquals(2, fixture.api.commitCalls)
        assertEquals(
            PendingEvidenceStatus.COMMITTED,
            fixture.dao.get(evidenceId)?.uploadState,
        )
    }

    @Test
    fun missingRemoteEvidenceContextStopsRetryAndMarksDraftRejected() = runBlocking {
        val fixture = Fixture()
        val evidenceId = fixture.prepareCapturedEvidence()
        fixture.api.staleOnAuthorize = true

        val error = runCatching {
            fixture.repository.submit(evidenceId)
        }.exceptionOrNull()

        assertTrue(error is EvidenceCaptureFailure.Stale)
        assertEquals(PendingEvidenceStatus.REJECTED, fixture.dao.get(evidenceId)?.uploadState)
        assertEquals("REMOTE_CONTEXT_NOT_FOUND", fixture.dao.get(evidenceId)?.lastError)
        assertEquals(null, fixture.recreatedRepository().resumeSubmission(evidenceId))
        val reopenError = runCatching {
            fixture.repository.load(ACCEPTANCE_ID, REFRESH_ID)
        }.exceptionOrNull()
        assertTrue(reopenError is EvidenceCaptureFailure.Stale)
        assertEquals(0, fixture.uploader.calls)
    }

    @Test
    fun missingRequiredVideoNeverQueuesOrLocksTheDraft() = runBlocking {
        val fixture = Fixture()
        fixture.api.videoRequired = true
        val evidenceId = fixture.prepareCapturedEvidence(requestSubmission = false)
        val queueError = runCatching {
            fixture.repository.requestSubmission(evidenceId)
        }.exceptionOrNull()
        assertTrue(queueError is EvidenceCaptureFailure.Unavailable)
        try {
            fixture.repository.submit(evidenceId)
            throw AssertionError("Photo-only evidence passed a video requirement")
        } catch (_: EvidenceCaptureFailure.Unavailable) { }
        assertEquals(0, fixture.uploader.calls)
        assertEquals(0, fixture.api.commitCalls)
        val restored = fixture.recreatedRepository().load(ACCEPTANCE_ID, REFRESH_ID) as EvidenceCaptureRecovery.Draft
        assertTrue(restored.draft.videoRequired)
        assertTrue(restored.draft.localFile.isFile)
        assertTrue(fixture.repository.resumeSubmission(evidenceId) == null)
        val editable = fixture.repository.resetVideo(evidenceId)
        assertTrue(editable.localFile.isFile)
        assertTrue(editable.video == null)
    }

    @Test
    fun queuedPayloadCannotBeChangedDuringAnAmbiguousSubmission() = runBlocking {
        val fixture = Fixture()
        val evidenceId = fixture.prepareCapturedEvidence()
        val before = fixture.repository.load(ACCEPTANCE_ID, REFRESH_ID) as EvidenceCaptureRecovery.Draft
        assertTrue(before.draft.submissionRequested)
        assertTrue(runCatching { fixture.repository.updateAnswer(evidenceId, "99") }.exceptionOrNull() is EvidenceCaptureFailure.Unavailable)
        assertTrue(runCatching { fixture.repository.resetCapture(evidenceId) }.exceptionOrNull() is EvidenceCaptureFailure.Unavailable)
        assertTrue(runCatching { fixture.repository.markCapturing(evidenceId) }.exceptionOrNull() is EvidenceCaptureFailure.Unavailable)
        val after = fixture.repository.load(ACCEPTANCE_ID, REFRESH_ID) as EvidenceCaptureRecovery.Draft
        assertEquals("2", after.draft.answer)
        assertTrue(after.draft.localFile.isFile)
    }

    @Test
    fun invalidAnswerDoesNotFreezeTheDraftOrQueueAnImpossibleUpload() = runBlocking {
        val fixture = Fixture()
        val evidenceId = fixture.prepareCapturedEvidence(requestSubmission = false)
        fixture.repository.updateAnswer(evidenceId, null)
        assertTrue(runCatching { fixture.repository.requestSubmission(evidenceId) }.exceptionOrNull() is EvidenceCaptureFailure.AnswerRequired)
        val restored = fixture.repository.load(ACCEPTANCE_ID, REFRESH_ID) as EvidenceCaptureRecovery.Draft
        assertTrue(!restored.draft.submissionRequested)
        fixture.repository.updateAnswer(evidenceId, "3")
        fixture.repository.requestSubmission(evidenceId)
        assertEquals(0, fixture.uploader.calls)
    }

    @Test
    fun lostCommitResponseRecoversAfterDeadlineWithoutReadingOrUploadingExpiredMedia() = runBlocking {
        val fixture = Fixture()
        val evidenceId = fixture.prepareCapturedEvidence()
        fixture.api.ambiguousCommitOnce = true
        assertTrue(runCatching { fixture.repository.submit(evidenceId) }.exceptionOrNull() is EvidenceCaptureFailure.Network)
        assertTrue(fixture.remote.committed)
        val saved = fixture.repository.load(ACCEPTANCE_ID, REFRESH_ID) as EvidenceCaptureRecovery.Draft
        saved.draft.localFile.delete()
        fixture.clock.update("2100-01-01T00:00:00Z")
        val result = fixture.recreatedRepository().load(ACCEPTANCE_ID, REFRESH_ID)
        assertTrue(result is EvidenceCaptureRecovery.Submitted)
        assertEquals(1, fixture.uploader.calls)
        assertEquals(PendingEvidenceStatus.COMMITTED, fixture.dao.get(evidenceId)?.uploadState)
    }

    private class Fixture {
        val dao = MemoryPendingEvidenceDao()
        val secrets = MemorySecretStore()
        val remote = RemoteEvidenceState()
        val api = FakeEvidenceApi(remote)
        val uploader = FakeUploader(remote)
        private val fileStore = TempEvidenceFileStore()
        private val auth = FakeAuthGateway()
        private val readApi = FakeEvidenceReadApi()
        val clock = ServerClock()
        private val json = Json { ignoreUnknownKeys = true }

        var repository = newRepository()
            private set

        suspend fun prepareCapturedEvidence(requestSubmission: Boolean = true): String {
            val draft = repository.begin(ACCEPTANCE_ID, REFRESH_ID)
            val file = draft.localFile
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))

            repository.markCapturing(draft.evidenceId)
            repository.addLocationSample(
                draft.evidenceId,
                EvidenceLocationSample(
                    latitude = 30.129,
                    longitude = 77.267,
                    accuracyMeters = 7.5,
                    provider = "fused",
                    mockSignal = false,
                    observedElapsedRealtimeMs = 1_050,
                ),
            )
            repository.completeCapture(
                evidenceId = draft.evidenceId,
                captureStartedMonotonicMs = 1_000,
                captureCompletedMonotonicMs = 1_200,
            )
            repository.updateAnswer(draft.evidenceId, "2")
            if (requestSubmission) repository.requestSubmission(draft.evidenceId)
            return draft.evidenceId
        }

        suspend fun prepareCapturedVideoEvidence(): String {
            api.videoRequired = true
            val evidenceId = prepareCapturedEvidence(requestSubmission = false)
            val draft = repository.load(ACCEPTANCE_ID, REFRESH_ID) as EvidenceCaptureRecovery.Draft
            val bytes = byteArrayOf(10, 20, 30, 40)
            File(draft.draft.localFile.absolutePath + ".mp4").writeBytes(bytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            // Restore a persisted, completed capture without invoking the Android media decoder.
            val key = "evidence.capture.$ACCEPTANCE_ID"
            val saved = json.parseToJsonElement(checkNotNull(secrets.read(key))).jsonObject
            val video = JsonObject(mapOf(
                "sha256" to JsonPrimitive(digest), "size_bytes" to JsonPrimitive(bytes.size),
                "duration_ms" to JsonPrimitive(3_000),
                "capture_started_monotonic_ms" to JsonPrimitive(1_300),
                "capture_completed_monotonic_ms" to JsonPrimitive(4_300),
            ))
            secrets.write(key, JsonObject(saved + ("video" to video)).toString())
            repository.requestSubmission(evidenceId)
            return evidenceId
        }

        fun recreatedRepository(): DefaultEvidenceCaptureRepository {
            repository = newRepository()
            return repository
        }

        private fun newRepository() =
            DefaultEvidenceCaptureRepository(
                auth = auth,
                readApi = readApi,
                evidenceApi = api,
                pendingEvidenceDao = dao,
                secrets = secrets,
                uploader = uploader,
                fileStore = fileStore,
                serverClock = clock,
                json = json,
            )
    }

    private class FakeAuthGateway : AuthGateway {
        private val session = AppSession(
            authSubjectId = "actor-test",
            accessToken = "token-test",
        )

        override suspend fun ensureAnonymousSession(): AppSession = session
        override suspend fun currentSession(): AppSession = session
        override suspend fun refreshIfNeeded(): AppSession = session
        override suspend fun signOutLocal() = Unit
    }

    private class FakeEvidenceReadApi : EvidenceReadApiClient {
        override suspend fun opportunityDetail(
            refreshId: String,
            accessToken: String,
        ): OpportunityDto =
            OpportunityDto(
                refreshId = REFRESH_ID,
                stateId = STATE_ID,
                stateVersion = 1,
                title = "Parking",
                question = "How many spaces are available?",
                stateType = "NUMERIC",
                unitCode = "spaces",
                location = OpportunityLocationDto(
                    locationId = LOCATION_ID,
                    name = "Parking Lot",
                    locationType = "PARKING",
                    displayAddress = "Demo",
                ),
                reward = OpportunityRewardDto(
                    mint = "mint-test",
                    poolAtomic = "1000",
                    payoutRule = "SINGLE_WINNER_ALL",
                ),
                distanceM = 10.0,
                expiresAt = FUTURE,
                evidenceDeadline = FUTURE,
                verificationClass = "FAST",
                evidenceSummary = OpportunityEvidenceSummaryDto(
                    templateKey = "parking.photo.v1",
                    mediaRequired = true,
                    locationRequired = true,
                    requiredWitnesses = 1,
                    maxWitnesses = 1,
                ),
                availability = OpportunityAvailabilityDto(
                    claimable = false,
                    activeClaims = 1,
                    remainingSlots = 0,
                ),
                stateRevision = 1,
                revision = 1,
            )

        override suspend fun claimDetail(
            acceptanceId: String,
            accessToken: String,
        ): ClaimStatusDto =
            ClaimStatusDto(
                acceptanceId = ACCEPTANCE_ID,
                refreshId = REFRESH_ID,
                status = "CAPTURE_ACTIVE",
                claimSlot = 0,
                claimDurationSeconds = 180,
                claimDeadline = FUTURE,
                chainSignature = "sig-test",
                chainStatus = "CONFIRMED",
                refreshStatus = "CAPTURE_IN_PROGRESS",
                refreshExpiresAt = FUTURE,
                evidenceDeadline = FUTURE,
                revision = 4,
                nextStep = "CAPTURE_EVIDENCE",
            )
    }

    private class FakeEvidenceApi(
        private val remote: RemoteEvidenceState,
    ) : EvidenceApiClient {
        var videoRequired = false
        var authorizeCalls = 0
        var commitCalls = 0
        var ambiguousCommitOnce = false
        var staleOnAuthorize = false
        var rejectAuthorizationAfterVideo = false
        var storageFailuresRemaining = 0
        var lastAuthorizedEvidenceId: String? = null

        override suspend fun issueEvidenceChallenge(
            acceptanceId: String,
            accessToken: String,
        ): EvidenceChallengeDto =
            EvidenceChallengeDto(
                challengeId = CHALLENGE_ID,
                refreshId = REFRESH_ID,
                acceptanceId = ACCEPTANCE_ID,
                nonce = "A".repeat(43),
                issuedAt = "2034-12-31T23:59:00Z",
                expiresAt = FUTURE,
                policyVersion = 1,
                capture = EvidenceCapturePolicyDto(
                    mediaRequired = true,
                    videoRequired = videoRequired,
                    locationRequired = true,
                ),
                claimStatus = "CAPTURE_ACTIVE",
                claimRevision = 4,
                refreshStatus = "CAPTURE_IN_PROGRESS",
                refreshRevision = 8,
            )

        override suspend fun authorizeEvidenceUpload(
            challengeId: String,
            request: EvidenceUploadAuthorizeRequest,
            accessToken: String,
        ): EvidenceUploadAuthorizationDto {
            authorizeCalls += 1
            if (rejectAuthorizationAfterVideo && remote.videoUploaded) {
                throw ApiFailure.ServerFailure(statusCode = 503, message = "The resource already exists", code = "EVIDENCE_UPLOAD_UNAVAILABLE")
            }
            if (staleOnAuthorize) {
                throw ApiFailure.BusinessError(
                    statusCode = 404,
                    code = "EVIDENCE_CHALLENGE_INVALID",
                    safeToRetry = false,
                    retryAfterMs = null,
                    message = "Evidence challenge was not found.",
                )
            }
            lastAuthorizedEvidenceId = EVIDENCE_ID
            return EvidenceUploadAuthorizationDto(
                evidenceId = EVIDENCE_ID,
                challengeId = CHALLENGE_ID,
                objectKey = OBJECT_KEY,
                mediaMime = "image/jpeg",
                upload = EvidenceUploadTargetDto(
                    method = "PUT",
                    signedUrl = "https://storage.example/upload?token=test",
                    contentType = "image/jpeg",
                ),
                videoUpload = if (videoRequired) EvidenceUploadTargetDto(
                    method = "PUT", signedUrl = "https://storage.example/video?token=test", contentType = "video/mp4",
                ) else null,
                applicationDeadline = FUTURE,
                replayed = authorizeCalls > 1,
            )
        }

        override suspend fun commitEvidence(
            evidenceId: String,
            request: EvidenceCommitRequest,
            idempotencyKey: String,
            accessToken: String,
        ): EvidenceCommitDto {
            commitCalls += 1
            if (storageFailuresRemaining > 0) {
                storageFailuresRemaining--
                throw ApiFailure.ServerFailure(statusCode = 503, message = "Storage is temporarily unavailable", code = "EVIDENCE_UPLOAD_UNAVAILABLE")
            }
            if (videoRequired && !remote.videoUploaded) {
                throw ApiFailure.ServerFailure(statusCode = 503, message = "Video is missing", code = "EVIDENCE_UPLOAD_UNAVAILABLE")
            }
            if (!remote.uploaded) {
                throw ApiFailure.ServerFailure(
                    statusCode = 503,
                    code = "EVIDENCE_UPLOAD_UNAVAILABLE",
                    message = "Uploaded evidence could not be inspected yet.",
                )
            }

            if (!remote.committed) {
                remote.committed = true
                if (ambiguousCommitOnce) {
                    ambiguousCommitOnce = false
                    throw ApiFailure.NetworkUnavailable(IOException("response lost"))
                }
            }

            return EvidenceCommitDto(
                evidenceId = EVIDENCE_ID,
                refreshId = REFRESH_ID,
                acceptanceId = ACCEPTANCE_ID,
                challengeId = CHALLENGE_ID,
                evidenceStatus = "COMMITTED",
                claimStatus = "EVIDENCE_COMMITTED",
                refreshStatus = "EVIDENCE_SUBMITTED",
                committedAt = FUTURE,
                media = EvidenceCommittedMediaDto(
                    objectKey = OBJECT_KEY,
                    sha256 = request.mediaSha256,
                    sizeBytes = request.mediaSizeBytes,
                    mime = "image/jpeg",
                ),
                replayed = commitCalls > 1 && remote.committed,
                nextStep = "VERIFICATION",
            )
        }
    }

    private enum class UploadMode {
        SUCCESS,
        FAIL_BEFORE_WRITE,
        WRITE_THEN_NETWORK_ERROR,
    }

    private class FakeUploader(
        private val remote: RemoteEvidenceState,
    ) : EvidenceObjectUploader {
        var mode: UploadMode = UploadMode.SUCCESS
        var calls = 0
        var videoCalls = 0
        var photoCalls = 0
        var failPhotoOnce = false

        override suspend fun upload(
            signedUrl: String,
            method: String,
            contentType: String,
            file: File,
        ) {
            calls += 1
            if (contentType == "video/mp4") {
                videoCalls++
                remote.videoUploaded = true
                return
            }
            photoCalls++
            if (failPhotoOnce) {
                failPhotoOnce = false
                throw EvidenceObjectUploadFailure.Network(IOException("photo response lost"))
            }
            when (mode) {
                UploadMode.SUCCESS -> remote.uploaded = true
                UploadMode.FAIL_BEFORE_WRITE ->
                    throw EvidenceObjectUploadFailure.Network(IOException("offline"))
                UploadMode.WRITE_THEN_NETWORK_ERROR -> {
                    remote.uploaded = true
                    throw EvidenceObjectUploadFailure.Network(IOException("response lost"))
                }
            }
        }
    }

    private class RemoteEvidenceState {
        var uploaded = false
        var videoUploaded = false
        var committed = false
    }

    private class MemorySecretStore : SecureSecretStore {
        private val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private class TempEvidenceFileStore : EvidenceFileStore {
        private val root = Files.createTempDirectory("now-evidence-test").toFile()

        override fun fileFor(evidenceId: String): File =
            File(root, "$evidenceId.jpg")

        override suspend fun delete(file: File) {
            file.delete()
        }
    }

    private class MemoryPendingEvidenceDao : PendingEvidenceDao {
        private val values = linkedMapOf<String, PendingEvidenceEntity>()
        private val flow = MutableStateFlow<List<PendingEvidenceEntity>>(emptyList())

        override suspend fun get(evidenceId: String): PendingEvidenceEntity? =
            values[evidenceId]

        override suspend fun getLatestByAcceptanceId(
            acceptanceId: String,
        ): PendingEvidenceEntity? =
            values.values
                .filter { it.acceptanceId == acceptanceId }
                .maxByOrNull { it.updatedAtMs }

        override fun observePending(): Flow<List<PendingEvidenceEntity>> = flow

        override suspend fun upsert(entity: PendingEvidenceEntity) {
            values[entity.evidenceId] = entity
            flow.value = values.values.toList()
        }

        override suspend fun markFailed(
            evidenceId: String,
            status: PendingEvidenceStatus,
            reason: String,
            updatedAtMs: Long,
        ): Int {
            val current = values[evidenceId] ?: return 0
            upsert(
                current.copy(
                    uploadState = status,
                    lastError = reason,
                    updatedAtMs = updatedAtMs,
                ),
            )
            return 1
        }
    }

    private companion object {
        const val REFRESH_ID = "22222222-2222-4222-8222-222222222222"
        const val ACCEPTANCE_ID = "33333333-3333-4333-8333-333333333333"
        const val CHALLENGE_ID = "44444444-4444-4444-8444-444444444444"
        const val EVIDENCE_ID = "55555555-5555-4555-8555-555555555555"
        const val STATE_ID = "66666666-6666-4666-8666-666666666666"
        const val LOCATION_ID = "77777777-7777-4777-8777-777777777777"
        const val OBJECT_KEY =
            "refreshes/$REFRESH_ID/evidence/$EVIDENCE_ID/original"
        const val FUTURE = "2035-01-01T00:10:00Z"
    }
}
