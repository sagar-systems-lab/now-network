package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.data.local.PendingEvidenceDao
import com.sagarsystemslab.nownetwork.data.local.PendingEvidenceEntity
import com.sagarsystemslab.nownetwork.data.local.PendingEvidenceStatus
import com.sagarsystemslab.nownetwork.evidence.EvidenceFileStore
import com.sagarsystemslab.nownetwork.evidence.EvidenceLocationSample
import com.sagarsystemslab.nownetwork.evidence.EvidenceObjectUploadFailure
import com.sagarsystemslab.nownetwork.evidence.EvidenceObjectUploader
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.EvidenceChallengeDto
import com.sagarsystemslab.nownetwork.network.EvidenceVideoDto
import com.sagarsystemslab.nownetwork.network.EvidenceCommitDto
import com.sagarsystemslab.nownetwork.network.EvidenceCommitRequest
import com.sagarsystemslab.nownetwork.network.EvidenceLocationSampleDto
import com.sagarsystemslab.nownetwork.network.EvidenceUploadAuthorizeRequest
import com.sagarsystemslab.nownetwork.network.EvidenceApiClient
import com.sagarsystemslab.nownetwork.network.EvidenceReadApiClient
import com.sagarsystemslab.nownetwork.network.OpportunityDto
import com.sagarsystemslab.nownetwork.security.SecureSecretStore
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

data class EvidenceCaptureContext(
    val acceptanceId: String,
    val refreshId: String,
    val question: String,
    val stateType: String,
    val mediaRequired: Boolean,
    val locationRequired: Boolean,
    val claimDeadline: String?,
    val evidenceDeadline: String,
    val videoRequired: Boolean = false,
)

data class EvidenceCaptureDraft(
    val evidenceId: String,
    val acceptanceId: String,
    val refreshId: String,
    val challengeId: String,
    val question: String,
    val stateType: String,
    val mediaRequired: Boolean,
    val locationRequired: Boolean,
    val expiresAt: String,
    val localFile: File,
    val status: PendingEvidenceStatus,
    val answer: String?,
    val captureStartedMonotonicMs: Long?,
    val captureCompletedMonotonicMs: Long?,
    val locationSampleCount: Int,
    val videoRequired: Boolean = false,
    val video: EvidenceVideoDto? = null,
)

sealed interface EvidenceCaptureRecovery {
    data class Ready(val context: EvidenceCaptureContext) : EvidenceCaptureRecovery
    data class Draft(val draft: EvidenceCaptureDraft) : EvidenceCaptureRecovery
    data class Submitted(val evidenceId: String) : EvidenceCaptureRecovery
}

data class EvidenceSubmissionResult(
    val evidence: EvidenceCommitDto,
)

sealed class EvidenceCaptureFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Unavailable(message: String) : EvidenceCaptureFailure(message)
    class Stale(message: String) : EvidenceCaptureFailure(message)
    class Expired : EvidenceCaptureFailure("The evidence capture window expired.")
    class MissingFile : EvidenceCaptureFailure("Captured evidence is missing from this device.")
    class MediaTooLarge :
        EvidenceCaptureFailure("Captured evidence is too large. Please recapture it.")
    class AnswerRequired : EvidenceCaptureFailure("Answer the live question before submitting.")
    class LocationRequired :
        EvidenceCaptureFailure("A precise location sample is required before submitting.")
    class Network(cause: Throwable) :
        EvidenceCaptureFailure("Network is unavailable. Your captured evidence is saved.", cause)
    class Server(message: String) : EvidenceCaptureFailure(message)
    class Protocol(message: String, cause: Throwable? = null) :
        EvidenceCaptureFailure(message, cause)
    class Storage(message: String, cause: Throwable? = null) :
        EvidenceCaptureFailure(message, cause)
    class UploadNotReady(cause: Throwable? = null) :
        EvidenceCaptureFailure("Uploaded evidence is not visible yet.", cause)
}

interface EvidenceCaptureRepository {
    suspend fun load(
        acceptanceId: String,
        refreshId: String,
    ): EvidenceCaptureRecovery

    suspend fun begin(
        acceptanceId: String,
        refreshId: String,
    ): EvidenceCaptureDraft

    suspend fun markCapturing(evidenceId: String): EvidenceCaptureDraft

    suspend fun addLocationSample(
        evidenceId: String,
        sample: EvidenceLocationSample,
    ): EvidenceCaptureDraft

    suspend fun completeCapture(
        evidenceId: String,
        captureStartedMonotonicMs: Long,
        captureCompletedMonotonicMs: Long,
    ): EvidenceCaptureDraft

    suspend fun completeVideo(evidenceId: String, startedMs: Long, completedMs: Long, durationMs: Long): EvidenceCaptureDraft =
        throw EvidenceCaptureFailure.Unavailable("Video capture is unavailable.")

    suspend fun resetVideo(evidenceId: String): EvidenceCaptureDraft =
        throw EvidenceCaptureFailure.Unavailable("Video capture is unavailable.")

    suspend fun updateAnswer(
        evidenceId: String,
        answer: String?,
    ): EvidenceCaptureDraft

    suspend fun resetCapture(evidenceId: String): EvidenceCaptureDraft

    suspend fun requestSubmission(evidenceId: String)

    suspend fun submit(evidenceId: String): EvidenceSubmissionResult

    suspend fun resumeSubmission(evidenceId: String): EvidenceSubmissionResult?
}

@Serializable
private data class EvidenceDraftSecret(
    val acceptanceId: String,
    val refreshId: String,
    val challengeId: String,
    val nonce: String,
    val expiresAt: String,
    val question: String,
    val stateType: String,
    val mediaRequired: Boolean,
    val locationRequired: Boolean,
    val evidenceId: String? = null,
    val objectKey: String? = null,
    val localFilePath: String? = null,
    val answer: String? = null,
    val captureStartedMonotonicMs: Long? = null,
    val captureCompletedMonotonicMs: Long? = null,
    val locations: List<EvidenceLocationSecret> = emptyList(),
    val submissionRequested: Boolean = false,
    val videoRequired: Boolean = false,
    val video: EvidenceVideoDto? = null,
    val videoUploaded: Boolean = false,
)

@Serializable
private data class EvidenceLocationSecret(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double? = null,
    val provider: String? = null,
    val mockSignal: Boolean? = null,
    val observedElapsedRealtimeMs: Long,
)

@Singleton
class DefaultEvidenceCaptureRepository @Inject constructor(
    private val auth: AuthGateway,
    private val readApi: EvidenceReadApiClient,
    private val evidenceApi: EvidenceApiClient,
    private val pendingEvidenceDao: PendingEvidenceDao,
    private val secrets: SecureSecretStore,
    private val uploader: EvidenceObjectUploader,
    private val fileStore: EvidenceFileStore,
    private val serverClock: ServerClock,
    private val json: Json,
) : EvidenceCaptureRepository {
    private val submissionMutex = Mutex()

    override suspend fun load(
        acceptanceId: String,
        refreshId: String,
    ): EvidenceCaptureRecovery {
        val pending = pendingEvidenceDao.getLatestByAcceptanceId(acceptanceId)
        if (pending != null) {
            if (
                pending.uploadState in setOf(
                    PendingEvidenceStatus.COMMITTED,
                    PendingEvidenceStatus.VERIFYING,
                    PendingEvidenceStatus.VERIFIED,
                )
            ) {
                return EvidenceCaptureRecovery.Submitted(pending.evidenceId)
            }
            if (pending.uploadState == PendingEvidenceStatus.EXPIRED) {
                throw EvidenceCaptureFailure.Expired()
            }
            if (
                pending.uploadState in setOf(
                    PendingEvidenceStatus.REJECTED,
                    PendingEvidenceStatus.CONFLICT,
                    PendingEvidenceStatus.FAILED,
                )
            ) {
                throw EvidenceCaptureFailure.Stale(
                    "This saved proof is no longer active. Return to EARN for current tasks.",
                )
            }

            readSecret(acceptanceId)?.let { secret ->
                if (isExpired(secret.expiresAt)) {
                    markExpired(pending)
                    throw EvidenceCaptureFailure.Expired()
                }
                return EvidenceCaptureRecovery.Draft(toDraft(pending, secret))
            }
        }

        val stored = readSecret(acceptanceId)
        if (stored != null) {
            if (stored.refreshId != refreshId || isExpired(stored.expiresAt)) {
                secrets.remove(secretKey(acceptanceId))
                if (isExpired(stored.expiresAt)) {
                    throw EvidenceCaptureFailure.Expired()
                }
                throw EvidenceCaptureFailure.Protocol("Saved evidence identity does not match task.")
            }
            return EvidenceCaptureRecovery.Draft(reserveAndPersist(stored))
        }

        val context = loadContext(acceptanceId, refreshId)
        return EvidenceCaptureRecovery.Ready(context)
    }

    override suspend fun begin(
        acceptanceId: String,
        refreshId: String,
    ): EvidenceCaptureDraft {
        pendingEvidenceDao.getLatestByAcceptanceId(acceptanceId)?.let { pending ->
            readSecret(acceptanceId)?.let { secret ->
                if (isExpired(secret.expiresAt)) {
                    markExpired(pending)
                    throw EvidenceCaptureFailure.Expired()
                }
                return toDraft(pending, secret)
            }
        }

        readSecret(acceptanceId)?.let { secret ->
            if (secret.refreshId != refreshId) {
                secrets.remove(secretKey(acceptanceId))
                throw EvidenceCaptureFailure.Protocol("Saved evidence identity does not match task.")
            }
            if (isExpired(secret.expiresAt)) {
                secrets.remove(secretKey(acceptanceId))
                throw EvidenceCaptureFailure.Expired()
            }
            return reserveAndPersist(secret)
        }

        val context = loadContext(acceptanceId, refreshId)
        val challenge = withAuthRetry { token ->
            evidenceApi.issueEvidenceChallenge(
                acceptanceId = acceptanceId,
                accessToken = token,
            )
        }
        validateChallenge(challenge, acceptanceId, refreshId)

        val secret = EvidenceDraftSecret(
            acceptanceId = acceptanceId,
            refreshId = refreshId,
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            expiresAt = challenge.expiresAt,
            question = context.question,
            stateType = context.stateType,
            mediaRequired = challenge.capture.mediaRequired,
            videoRequired = challenge.capture.videoRequired,
            locationRequired = challenge.capture.locationRequired,
        )
        writeSecret(secret)

        return reserveAndPersist(secret)
    }

    override suspend fun markCapturing(evidenceId: String): EvidenceCaptureDraft {
        val (pending, secret) = loadDraft(evidenceId)
        val updated = pending.copy(
            uploadState = PendingEvidenceStatus.CAPTURING,
            lastError = null,
            updatedAtMs = serverClock.nowMillis(),
        )
        pendingEvidenceDao.upsert(updated)
        return toDraft(updated, secret)
    }

    override suspend fun addLocationSample(
        evidenceId: String,
        sample: EvidenceLocationSample,
    ): EvidenceCaptureDraft {
        val (pending, secret) = loadDraft(evidenceId)
        val nextLocations = (
            secret.locations +
                EvidenceLocationSecret(
                    lat = sample.latitude,
                    lng = sample.longitude,
                    accuracyM = sample.accuracyMeters,
                    provider = sample.provider,
                    mockSignal = sample.mockSignal,
                    observedElapsedRealtimeMs = sample.observedElapsedRealtimeMs,
                )
            )
            .distinctBy { it.observedElapsedRealtimeMs }
            .takeLast(MAX_LOCATION_SAMPLES)

        val updatedSecret = secret.copy(locations = nextLocations)
        writeSecret(updatedSecret)
        return toDraft(pending, updatedSecret)
    }

    override suspend fun completeCapture(
        evidenceId: String,
        captureStartedMonotonicMs: Long,
        captureCompletedMonotonicMs: Long,
    ): EvidenceCaptureDraft {
        require(captureStartedMonotonicMs >= 0L)
        require(captureCompletedMonotonicMs >= captureStartedMonotonicMs)

        val (pending, secret) = loadDraft(evidenceId)
        val file = File(pending.localFilePath)
        if (!file.isFile || file.length() <= 0L) {
            failMissingFile(pending)
        }

        val capturedAt = serverClock.nowMillis()
        var updated = pending.copy(
            capturedAtMs = capturedAt,
            captureElapsedRealtimeMs = captureCompletedMonotonicMs,
            uploadState = PendingEvidenceStatus.HASHING,
            lastError = null,
            updatedAtMs = capturedAt,
        )
        pendingEvidenceDao.upsert(updated)

        val updatedSecret = secret.copy(
            captureStartedMonotonicMs = captureStartedMonotonicMs,
            captureCompletedMonotonicMs = captureCompletedMonotonicMs,
        )
        writeSecret(updatedSecret)

        val digest = hashMedia(file)
        if (digest.sizeBytes > MAX_MEDIA_BYTES) {
            updated = updated.copy(
                uploadState = PendingEvidenceStatus.FAILED,
                lastError = "MEDIA_TOO_LARGE",
                updatedAtMs = serverClock.nowMillis(),
            )
            pendingEvidenceDao.upsert(updated)
            throw EvidenceCaptureFailure.MediaTooLarge()
        }

        updated = updated.copy(
            sha256Hex = digest.sha256Hex,
            mediaSizeBytes = digest.sizeBytes,
            uploadState = PendingEvidenceStatus.UPLOAD_READY,
            lastError = null,
            updatedAtMs = serverClock.nowMillis(),
        )
        pendingEvidenceDao.upsert(updated)

        return toDraft(updated, updatedSecret)
    }

    override suspend fun completeVideo(evidenceId: String, startedMs: Long, completedMs: Long, durationMs: Long): EvidenceCaptureDraft {
        val (pending, secret) = loadDraft(evidenceId)
        checkEditable(pending, secret)
        val photoCompleted = secret.captureCompletedMonotonicMs ?: throw EvidenceCaptureFailure.MissingFile()
        if (durationMs !in 3000L..15000L || startedMs < photoCompleted || completedMs < startedMs ||
            completedMs - startedMs < durationMs - 250 || completedMs - startedMs > 20000L) {
            throw EvidenceCaptureFailure.Unavailable("Record a clear video lasting 3–15 seconds.")
        }
        val file = File(pending.localFilePath + ".mp4")
        if (!file.isFile || file.length() <= 0) throw EvidenceCaptureFailure.MissingFile()
        if (file.length() > 6L * 1024 * 1024) throw EvidenceCaptureFailure.MediaTooLarge()
        val actualDuration = withContext(Dispatchers.IO) {
            val metadata = android.media.MediaMetadataRetriever()
            try { metadata.setDataSource(file.absolutePath)
                metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally { metadata.release() }
        }
        if (actualDuration == null || actualDuration !in 3000L..15000L || kotlin.math.abs(actualDuration - durationMs) > 100) {
            throw EvidenceCaptureFailure.Unavailable("Video is incomplete. Keep the photo and record the clip again.")
        }
        val digest = hashMedia(file)
        val next = secret.copy(video = EvidenceVideoDto(digest.sha256Hex,digest.sizeBytes,actualDuration,startedMs,completedMs),videoUploaded=false)
        writeSecret(next)
        return toDraft(pending,next)
    }

    override suspend fun resetVideo(evidenceId: String): EvidenceCaptureDraft {
        val (pending, secret) = loadDraft(evidenceId)
        checkEditable(pending,secret)
        fileStore.delete(File(pending.localFilePath + ".mp4"))
        val next = secret.copy(video=null,videoUploaded=false)
        writeSecret(next)
        return toDraft(pending,next)
    }

    private fun checkEditable(pending: PendingEvidenceEntity, secret: EvidenceDraftSecret) {
        if (isExpired(secret.expiresAt)) throw EvidenceCaptureFailure.Expired()
        if (secret.submissionRequested || pending.uploadState in setOf(PendingEvidenceStatus.UPLOADING,
            PendingEvidenceStatus.UPLOADED,PendingEvidenceStatus.COMMITTING,PendingEvidenceStatus.COMMITTED)) {
            throw EvidenceCaptureFailure.Unavailable("This proof is already submitting. Resume the saved upload.")
        }
    }

    override suspend fun updateAnswer(
        evidenceId: String,
        answer: String?,
    ): EvidenceCaptureDraft {
        val (pending, secret) = loadDraft(evidenceId)
        val normalized = answer?.trim()?.takeIf { it.isNotEmpty() }
        val updated = secret.copy(answer = normalized)
        writeSecret(updated)
        return toDraft(pending, updated)
    }

    override suspend fun resetCapture(evidenceId: String): EvidenceCaptureDraft {
        val (pending, secret) = loadDraft(evidenceId)
        if (isExpired(secret.expiresAt)) {
            markExpired(pending)
            throw EvidenceCaptureFailure.Expired()
        }

        checkEditable(pending, secret)
        fileStore.delete(File(pending.localFilePath))
        fileStore.delete(File(pending.localFilePath + ".mp4"))

        val updatedSecret = secret.copy(
            captureStartedMonotonicMs = null,
            captureCompletedMonotonicMs = null,
            locations = emptyList(),
            submissionRequested = false,
            video = null,
            videoUploaded = false,
        )
        writeSecret(updatedSecret)

        val updated = pending.copy(
            sha256Hex = null,
            mediaSizeBytes = null,
            capturedAtMs = 0L,
            captureElapsedRealtimeMs = 0L,
            uploadState = PendingEvidenceStatus.CHALLENGE_ISSUED,
            lastError = null,
            updatedAtMs = serverClock.nowMillis(),
        )
        pendingEvidenceDao.upsert(updated)
        return toDraft(updated, updatedSecret)
    }

    override suspend fun requestSubmission(evidenceId: String) {
        val (_, secret) = loadDraft(evidenceId)
        if (secret.videoRequired && secret.video == null) {
            throw EvidenceCaptureFailure.Unavailable("Record the short video before submitting.")
        }
        writeSecret(secret.copy(submissionRequested = true))
    }

    override suspend fun submit(evidenceId: String): EvidenceSubmissionResult =
        submissionMutex.withLock {
            submitWithTerminalCleanup(evidenceId)
        }

    override suspend fun resumeSubmission(evidenceId: String): EvidenceSubmissionResult? =
        submissionMutex.withLock {
            val pending = pendingEvidenceDao.get(evidenceId) ?: return@withLock null
            if (
                pending.uploadState in setOf(
                    PendingEvidenceStatus.COMMITTED,
                    PendingEvidenceStatus.VERIFYING,
                    PendingEvidenceStatus.VERIFIED,
                    PendingEvidenceStatus.REJECTED,
                    PendingEvidenceStatus.CONFLICT,
                    PendingEvidenceStatus.EXPIRED,
                    PendingEvidenceStatus.FAILED,
                )
            ) {
                return@withLock null
            }

            val secret = readSecret(pending.acceptanceId) ?: return@withLock null
            if (!secret.submissionRequested) return@withLock null
            submitWithTerminalCleanup(evidenceId)
        }

    private suspend fun submitWithTerminalCleanup(
        evidenceId: String,
    ): EvidenceSubmissionResult =
        try {
            submitLocked(evidenceId)
        } catch (error: EvidenceCaptureFailure.Expired) {
            pendingEvidenceDao.get(evidenceId)?.let { pending ->
                markExpired(pending)
            }
            throw error
        } catch (error: EvidenceCaptureFailure.Stale) {
            pendingEvidenceDao.get(evidenceId)?.let { pending ->
                pendingEvidenceDao.upsert(
                    pending.copy(
                        uploadState = PendingEvidenceStatus.REJECTED,
                        lastError = "REMOTE_CONTEXT_NOT_FOUND",
                        updatedAtMs = serverClock.nowMillis(),
                    ),
                )
            }
            throw error
        }

    private suspend fun submitLocked(evidenceId: String): EvidenceSubmissionResult {
        var (pending, secret) = loadDraft(evidenceId)

        if (isExpired(secret.expiresAt)) {
            markExpired(pending)
            throw EvidenceCaptureFailure.Expired()
        }

        if (
            pending.uploadState in setOf(
                PendingEvidenceStatus.CHALLENGE_ISSUED,
                PendingEvidenceStatus.CAPTURING,
            )
        ) {
            throw EvidenceCaptureFailure.Unavailable("Capture evidence before submitting.")
        }

        val file = File(pending.localFilePath)
        if (!file.isFile || file.length() <= 0L) {
            failMissingFile(pending)
        }

        if (pending.sha256Hex == null || pending.mediaSizeBytes == null) {
            val digest = hashMedia(file)
            if (digest.sizeBytes > MAX_MEDIA_BYTES) {
                throw EvidenceCaptureFailure.MediaTooLarge()
            }
            pending = pending.copy(
                sha256Hex = digest.sha256Hex,
                mediaSizeBytes = digest.sizeBytes,
                uploadState = PendingEvidenceStatus.UPLOAD_READY,
                lastError = null,
                updatedAtMs = serverClock.nowMillis(),
            )
            pendingEvidenceDao.upsert(pending)
        }

        if (secret.videoRequired && secret.video == null) throw EvidenceCaptureFailure.Unavailable("Record the short video before submitting.")
        val answer = answerValue(secret)
        if (secret.locationRequired && secret.locations.isEmpty()) {
            throw EvidenceCaptureFailure.LocationRequired()
        }
        val captureStarted = secret.captureStartedMonotonicMs
            ?: throw EvidenceCaptureFailure.Protocol("Capture start time is missing.")
        val captureCompleted = secret.captureCompletedMonotonicMs
            ?: throw EvidenceCaptureFailure.Protocol("Capture completion time is missing.")

        secret = secret.copy(submissionRequested = true)
        writeSecret(secret)

        if (pending.uploadState in setOf(PendingEvidenceStatus.UPLOADING,PendingEvidenceStatus.UPLOADED,PendingEvidenceStatus.COMMITTING)) {
            try {
                return commitPrepared(
                    pending = pending,
                    secret = secret,
                    answer = answer,
                    captureStarted = captureStarted,
                    captureCompleted = captureCompleted,
                )
            } catch (error: EvidenceCaptureFailure.UploadNotReady) {
                secret = secret.copy(videoUploaded=false)
                writeSecret(secret)
                pending = pending.copy(
                    uploadState = PendingEvidenceStatus.UPLOAD_READY,
                    lastError = null,
                    updatedAtMs = serverClock.nowMillis(),
                )
                pendingEvidenceDao.upsert(pending)
            }
        }

        if (secret.video != null && !secret.videoUploaded) {
            val clip = secret.video!!
            val clipFile = File(pending.localFilePath + ".mp4")
            if (!clipFile.isFile) throw EvidenceCaptureFailure.MissingFile()
            val digest = hashMedia(clipFile)
            if (digest.sha256Hex != clip.sha256 || digest.sizeBytes != clip.sizeBytes) {
                throw EvidenceCaptureFailure.Unavailable("Saved video changed. This proof cannot be submitted.")
            }
            val authorization = withAuthRetry { token -> evidenceApi.authorizeEvidenceUpload(secret.challengeId,
                EvidenceUploadAuthorizeRequest(secret.nonce, MEDIA_MIME), token) }
            if (authorization.evidenceId != evidenceId || authorization.challengeId != secret.challengeId) {
                throw EvidenceCaptureFailure.Protocol("Video reservation identity changed.")
            }
            val target = authorization.videoUpload ?: throw EvidenceCaptureFailure.Protocol("The server has no video upload support for this claim.")
            if (target.contentType != "video/mp4" || target.method != "PUT") throw EvidenceCaptureFailure.Protocol("Unexpected video upload target.")
            try { uploader.upload(target.signedUrl,target.method,target.contentType,clipFile) }
            catch (error: CancellationException) { throw error }
            catch (error: EvidenceObjectUploadFailure.Network) { throw EvidenceCaptureFailure.Network(error) }
            catch (error: EvidenceObjectUploadFailure) { throw EvidenceCaptureFailure.Storage("Video upload will retry safely.",error) }
            secret = secret.copy(videoUploaded=true)
            writeSecret(secret)
        }



        if (
            pending.uploadState !in setOf(
                PendingEvidenceStatus.UPLOADED,
                PendingEvidenceStatus.COMMITTING,
            )
        ) {
            val authorization = withAuthRetry { token ->
                evidenceApi.authorizeEvidenceUpload(
                    challengeId = secret.challengeId,
                    request = EvidenceUploadAuthorizeRequest(
                        nonce = secret.nonce,
                        mediaMime = MEDIA_MIME,
                    ),
                    accessToken = token,
                )
            }
            if (
                authorization.evidenceId != evidenceId ||
                authorization.challengeId != secret.challengeId ||
                authorization.mediaMime != MEDIA_MIME ||
                authorization.upload.contentType != MEDIA_MIME
            ) {
                throw EvidenceCaptureFailure.Protocol(
                    "Evidence upload reservation identity changed.",
                )
            }

            pending = pending.copy(
                uploadState = PendingEvidenceStatus.UPLOADING,
                lastError = null,
                updatedAtMs = serverClock.nowMillis(),
            )
            pendingEvidenceDao.upsert(pending)

            try {
                uploader.upload(
                    signedUrl = authorization.upload.signedUrl,
                    method = authorization.upload.method,
                    contentType = authorization.upload.contentType,
                    file = file,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: EvidenceObjectUploadFailure.Network) {
                throw EvidenceCaptureFailure.Network(error)
            } catch (error: EvidenceObjectUploadFailure) {
                throw EvidenceCaptureFailure.Storage(
                    error.message ?: "Evidence upload failed.",
                    error,
                )
            }

            pending = pending.copy(
                uploadState = PendingEvidenceStatus.UPLOADED,
                lastError = null,
                updatedAtMs = serverClock.nowMillis(),
            )
            pendingEvidenceDao.upsert(pending)
        }

        return commitPrepared(
            pending = pending,
            secret = secret,
            answer = answer,
            captureStarted = captureStarted,
            captureCompleted = captureCompleted,
        )
    }

    private suspend fun commitPrepared(
        pending: PendingEvidenceEntity,
        secret: EvidenceDraftSecret,
        answer: JsonElement,
        captureStarted: Long,
        captureCompleted: Long,
    ): EvidenceSubmissionResult {
        val committing = pending.copy(
            uploadState = PendingEvidenceStatus.COMMITTING,
            lastError = null,
            updatedAtMs = serverClock.nowMillis(),
        )
        pendingEvidenceDao.upsert(committing)

        val committed = try { withAuthRetry { token ->
            evidenceApi.commitEvidence(
                evidenceId = pending.evidenceId,
                request = EvidenceCommitRequest(
                    nonce = secret.nonce,
                    video = secret.video,
                    mediaSha256 = checkNotNull(pending.sha256Hex),
                    mediaSizeBytes = checkNotNull(pending.mediaSizeBytes),
                    answerValue = answer,
                    captureStartedMonotonicMs = captureStarted,
                    captureCompletedMonotonicMs = captureCompleted,
                    locationSamples = secret.locations.map { location ->
                        EvidenceLocationSampleDto(
                            lat = location.lat,
                            lng = location.lng,
                            accuracyM = location.accuracyM,
                            provider = location.provider,
                            mockSignal = location.mockSignal,
                            capturedOffsetMs =
                                location.observedElapsedRealtimeMs - captureStarted,
                        )
                    },
                ),
                idempotencyKey = commitKey(pending.evidenceId),
                accessToken = token,
            )
        } } catch (error: EvidenceCaptureFailure.UploadNotReady) {
            writeSecret(secret.copy(videoUploaded=false))
            throw error
        }

        if (
            committed.evidenceId != pending.evidenceId ||
            committed.acceptanceId != secret.acceptanceId ||
            committed.refreshId != secret.refreshId ||
            committed.challengeId != secret.challengeId
        ) {
            throw EvidenceCaptureFailure.Protocol("Evidence commit identity mismatch.")
        }

        pendingEvidenceDao.upsert(
            committing.copy(
                uploadState = PendingEvidenceStatus.COMMITTED,
                lastError = null,
                updatedAtMs = serverClock.nowMillis(),
            ),
        )
        secrets.remove(secretKey(secret.acceptanceId))
        return EvidenceSubmissionResult(committed)
    }

    private suspend fun reserveAndPersist(
        source: EvidenceDraftSecret,
    ): EvidenceCaptureDraft {
        val authorization = withAuthRetry { token ->
            evidenceApi.authorizeEvidenceUpload(
                challengeId = source.challengeId,
                request = EvidenceUploadAuthorizeRequest(
                    nonce = source.nonce,
                    mediaMime = MEDIA_MIME,
                ),
                accessToken = token,
            )
        }
        if (authorization.challengeId != source.challengeId) {
            throw EvidenceCaptureFailure.Protocol("Evidence reservation challenge mismatch.")
        }

        source.evidenceId?.let { expected ->
            if (authorization.evidenceId != expected) {
                throw EvidenceCaptureFailure.Protocol("Evidence reservation identity changed.")
            }
        }

        val evidenceId = authorization.evidenceId
        val localFile = evidenceFile(evidenceId)
        val secret = source.copy(
            evidenceId = evidenceId,
            objectKey = authorization.objectKey,
            localFilePath = localFile.absolutePath,
        )
        writeSecret(secret)

        val existing = pendingEvidenceDao.get(evidenceId)
        val now = serverClock.nowMillis()
        val pending = existing ?: PendingEvidenceEntity(
            evidenceId = evidenceId,
            acceptanceId = secret.acceptanceId,
            challengeId = secret.challengeId,
            localFilePath = localFile.absolutePath,
            sha256Hex = null,
            mediaSizeBytes = null,
            capturedAtMs = 0L,
            captureElapsedRealtimeMs = 0L,
            uploadState = PendingEvidenceStatus.CHALLENGE_ISSUED,
            lastError = null,
            createdAtMs = now,
            updatedAtMs = now,
        )
        pendingEvidenceDao.upsert(pending)
        return toDraft(pending, secret)
    }

    private suspend fun loadContext(
        acceptanceId: String,
        refreshId: String,
    ): EvidenceCaptureContext {
        val claim = withAuthRetry { token ->
            readApi.claimDetail(acceptanceId, token)
        }
        if (claim.acceptanceId != acceptanceId || claim.refreshId != refreshId) {
            throw EvidenceCaptureFailure.Protocol("Claim identity does not match task.")
        }
        if (claim.status !in setOf("CLAIMED", "CAPTURE_ACTIVE")) {
            when (claim.status) {
                "EXPIRED" -> throw EvidenceCaptureFailure.Expired()
                "EVIDENCE_COMMITTED" ->
                    throw EvidenceCaptureFailure.Unavailable("Evidence is already submitted.")
                else ->
                    throw EvidenceCaptureFailure.Unavailable("This claim cannot capture evidence.")
            }
        }

        val opportunity = withAuthRetry { token ->
            readApi.opportunityDetail(refreshId, token)
        }
        validateOpportunity(opportunity, refreshId)

        return EvidenceCaptureContext(
            acceptanceId = acceptanceId,
            refreshId = refreshId,
            question = opportunity.question,
            stateType = opportunity.stateType,
            mediaRequired = opportunity.evidenceSummary.mediaRequired,
            videoRequired = opportunity.evidenceSummary.videoRequired,
            locationRequired = opportunity.evidenceSummary.locationRequired,
            claimDeadline = claim.claimDeadline,
            evidenceDeadline = claim.evidenceDeadline,
        )
    }

    private fun validateOpportunity(
        opportunity: OpportunityDto,
        refreshId: String,
    ) {
        if (opportunity.refreshId != refreshId) {
            throw EvidenceCaptureFailure.Protocol("Opportunity identity mismatch.")
        }
        if (opportunity.stateType !in setOf("NUMERIC", "BINARY", "VISUAL")) {
            throw EvidenceCaptureFailure.Protocol("Unsupported evidence answer type.")
        }
    }

    private fun validateChallenge(
        challenge: EvidenceChallengeDto,
        acceptanceId: String,
        refreshId: String,
    ) {
        if (
            challenge.acceptanceId != acceptanceId ||
            challenge.refreshId != refreshId ||
            challenge.nonce.length != 43
        ) {
            throw EvidenceCaptureFailure.Protocol("Evidence challenge identity mismatch.")
        }
        if (isExpired(challenge.expiresAt)) {
            throw EvidenceCaptureFailure.Expired()
        }
    }

    private suspend fun loadDraft(
        evidenceId: String,
    ): Pair<PendingEvidenceEntity, EvidenceDraftSecret> {
        val pending = pendingEvidenceDao.get(evidenceId)
            ?: throw EvidenceCaptureFailure.Unavailable("Evidence draft was not found.")
        val secret = readSecret(pending.acceptanceId)
            ?: throw EvidenceCaptureFailure.Protocol("Evidence recovery metadata is unavailable.")
        if (
            secret.evidenceId != evidenceId ||
            secret.challengeId != pending.challengeId ||
            secret.acceptanceId != pending.acceptanceId
        ) {
            throw EvidenceCaptureFailure.Protocol("Evidence recovery identity mismatch.")
        }
        return pending to secret
    }

    private fun toDraft(
        pending: PendingEvidenceEntity,
        secret: EvidenceDraftSecret,
    ): EvidenceCaptureDraft =
        EvidenceCaptureDraft(
            evidenceId = pending.evidenceId,
            acceptanceId = pending.acceptanceId,
            refreshId = secret.refreshId,
            challengeId = pending.challengeId,
            question = secret.question,
            stateType = secret.stateType,
            mediaRequired = secret.mediaRequired,
            locationRequired = secret.locationRequired,
            expiresAt = secret.expiresAt,
            localFile = File(pending.localFilePath),
            status = pending.uploadState,
            answer = secret.answer,
            captureStartedMonotonicMs = secret.captureStartedMonotonicMs,
            captureCompletedMonotonicMs = secret.captureCompletedMonotonicMs,
            locationSampleCount = secret.locations.size,
            videoRequired = secret.videoRequired,
            video = secret.video,
        )

    private suspend fun markExpired(pending: PendingEvidenceEntity) {
        pendingEvidenceDao.upsert(
            pending.copy(
                uploadState = PendingEvidenceStatus.EXPIRED,
                lastError = "CHALLENGE_EXPIRED",
                updatedAtMs = serverClock.nowMillis(),
            ),
        )
        secrets.remove(secretKey(pending.acceptanceId))
    }

    private suspend fun failMissingFile(pending: PendingEvidenceEntity): Nothing {
        pendingEvidenceDao.upsert(
            pending.copy(
                uploadState = PendingEvidenceStatus.FAILED,
                lastError = "LOCAL_FILE_MISSING",
                updatedAtMs = serverClock.nowMillis(),
            ),
        )
        throw EvidenceCaptureFailure.MissingFile()
    }

    private fun answerValue(secret: EvidenceDraftSecret): JsonElement =
        when (secret.stateType) {
            "NUMERIC" -> {
                val raw = secret.answer ?: throw EvidenceCaptureFailure.AnswerRequired()
                val value = raw.toLongOrNull()
                    ?: throw EvidenceCaptureFailure.AnswerRequired()
                if (value !in -MAX_SAFE_JSON_INTEGER..MAX_SAFE_JSON_INTEGER) {
                    throw EvidenceCaptureFailure.AnswerRequired()
                }
                JsonPrimitive(value)
            }

            "BINARY" -> {
                val value = secret.answer ?: throw EvidenceCaptureFailure.AnswerRequired()
                JsonPrimitive(value)
            }

            "VISUAL" -> JsonPrimitive(secret.answer?.take(500) ?: VISUAL_CAPTURE_VALUE)

            else -> throw EvidenceCaptureFailure.Protocol("Unsupported evidence answer type.")
        }

    private suspend fun <T> withAuthRetry(
        block: suspend (String) -> T,
    ): T {
        val session = try {
            auth.ensureAnonymousSession()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw EvidenceCaptureFailure.Network(error)
        }

        return try {
            block(session.accessToken)
        } catch (error: ApiFailure.AuthExpired) {
            try {
                val refreshed = auth.refreshIfNeeded()
                block(refreshed.accessToken)
            } catch (refreshError: CancellationException) {
                throw refreshError
            } catch (refreshError: ApiFailure) {
                throw refreshError.toEvidenceFailure()
            } catch (refreshError: Exception) {
                throw EvidenceCaptureFailure.Network(refreshError)
            }
        } catch (error: ApiFailure) {
            throw error.toEvidenceFailure()
        }
    }

    private fun ApiFailure.toEvidenceFailure(): EvidenceCaptureFailure =
        when (this) {
            is ApiFailure.Configuration ->
                EvidenceCaptureFailure.Protocol(message ?: "Evidence configuration is incomplete.")
            is ApiFailure.NetworkUnavailable,
            is ApiFailure.Timeout,
            is ApiFailure.RateLimited ->
                EvidenceCaptureFailure.Network(this)
            is ApiFailure.ServerFailure ->
                if (code == "EVIDENCE_UPLOAD_UNAVAILABLE") EvidenceCaptureFailure.UploadNotReady(this)
                else EvidenceCaptureFailure.Server(message ?: "Evidence service is unavailable.")
            is ApiFailure.BusinessError ->
                when (code) {
                    "CHALLENGE_EXPIRED" -> EvidenceCaptureFailure.Expired()
                    "EVIDENCE_ALREADY_COMMITTED" ->
                        EvidenceCaptureFailure.Unavailable("Evidence is already submitted.")
                    "EVIDENCE_UPLOAD_UNAVAILABLE" ->
                        EvidenceCaptureFailure.UploadNotReady(this)
                    "CLAIM_NOT_FOUND",
                    "NOT_FOUND",
                    "EVIDENCE_CHALLENGE_INVALID",
                    "CLAIM_NOT_AVAILABLE",
                    "VERIFICATION_NOT_ELIGIBLE" ->
                        EvidenceCaptureFailure.Stale(
                            message ?: "This saved proof is no longer active.",
                        )
                    "EVIDENCE_MEDIA_INVALID",
                    "EVIDENCE_REPLAY" ->
                        EvidenceCaptureFailure.Unavailable(message ?: "Evidence is not eligible.")
                    else -> EvidenceCaptureFailure.Server(message ?: code)
                }
            is ApiFailure.ProtocolError ->
                EvidenceCaptureFailure.Protocol(
                    message ?: "Evidence response contract failed.",
                    this,
                )
            is ApiFailure.AuthExpired ->
                EvidenceCaptureFailure.Server("Session expired.")
        }

    private suspend fun hashMedia(file: File): MediaDigest =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            MediaDigest(
                sha256Hex = digest.digest().joinToString("") {
                    it.toInt().and(0xff).toString(16).padStart(2, '0')
                },
                sizeBytes = file.length(),
            )
        }

    private fun evidenceFile(evidenceId: String): File =
        fileStore.fileFor(evidenceId)

    private fun writeSecret(value: EvidenceDraftSecret) {
        secrets.write(
            secretKey(value.acceptanceId),
            json.encodeToString(EvidenceDraftSecret.serializer(), value),
        )
    }

    private fun readSecret(acceptanceId: String): EvidenceDraftSecret? =
        secrets.read(secretKey(acceptanceId))?.let { encoded ->
            runCatching {
                json.decodeFromString(EvidenceDraftSecret.serializer(), encoded)
            }.getOrElse {
                secrets.remove(secretKey(acceptanceId))
                null
            }
        }

    private fun isExpired(value: String): Boolean =
        runCatching { Instant.parse(value).toEpochMilli() <= serverClock.nowMillis() }
            .getOrElse {
                throw EvidenceCaptureFailure.Protocol("Evidence deadline is invalid.", it)
            }

    private fun secretKey(acceptanceId: String): String =
        "evidence.capture.$acceptanceId"

    private fun commitKey(evidenceId: String): String =
        "evidence-commit:$evidenceId"

    private data class MediaDigest(
        val sha256Hex: String,
        val sizeBytes: Long,
    )

    private companion object {
        const val MEDIA_MIME = "image/jpeg"
        const val MAX_MEDIA_BYTES = 10_485_760L
        const val MAX_LOCATION_SAMPLES = 12
        const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L
        const val VISUAL_CAPTURE_VALUE = "VISUAL_CAPTURE"
    }
}
