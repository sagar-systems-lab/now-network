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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceCaptureRepositoryTest {
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
    fun missingRequiredVideoNeverStartsAnUpload() = runBlocking {
        val fixture = Fixture()
        fixture.api.videoRequired = true
        val evidenceId = fixture.prepareCapturedEvidence()
        try {
            fixture.repository.submit(evidenceId)
            throw AssertionError("Photo-only evidence passed a video requirement")
        } catch (_: EvidenceCaptureFailure.Unavailable) { }
        assertEquals(0, fixture.uploader.calls)
        assertEquals(0, fixture.api.commitCalls)
        val restored = fixture.recreatedRepository().load(ACCEPTANCE_ID, REFRESH_ID) as EvidenceCaptureRecovery.Draft
        assertTrue(restored.draft.videoRequired)
        assertTrue(restored.draft.localFile.isFile)
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
        private val clock = ServerClock()
        private val json = Json { ignoreUnknownKeys = true }

        var repository = newRepository()
            private set

        suspend fun prepareCapturedEvidence(): String {
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
            repository.requestSubmission(draft.evidenceId)
            return draft.evidenceId
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

        override suspend fun upload(
            signedUrl: String,
            method: String,
            contentType: String,
            file: File,
        ) {
            calls += 1
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
