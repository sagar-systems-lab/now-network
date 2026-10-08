package com.sagarsystemslab.nownetwork.feature.capture

import com.sagarsystemslab.nownetwork.data.local.PendingEvidenceDao
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.data.local.PendingEvidenceStatus
import com.sagarsystemslab.nownetwork.evidence.EvidenceLocationFailure
import com.sagarsystemslab.nownetwork.evidence.EvidenceLocationProvider
import com.sagarsystemslab.nownetwork.evidence.EvidenceWorkScheduler
import com.sagarsystemslab.nownetwork.repository.EvidenceCaptureDraft
import com.sagarsystemslab.nownetwork.repository.EvidenceCaptureFailure
import com.sagarsystemslab.nownetwork.repository.EvidenceCaptureRecovery
import com.sagarsystemslab.nownetwork.repository.EvidenceCaptureRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class EvidenceCaptureStage {
    LOADING,
    READY,
    PREPARING,
    CAMERA,
    VIDEO,
    PROCESSING,
    REVIEW,
    SUBMITTING,
    QUEUED,
    SUBMITTED,
    EXPIRED,
    ERROR,
}

data class EvidenceCaptureUiState(
    val stage: EvidenceCaptureStage = EvidenceCaptureStage.LOADING,
    val acceptanceId: String? = null,
    val refreshId: String? = null,
    val evidenceId: String? = null,
    val question: String = "",
    val stateType: String = "",
    val mediaRequired: Boolean = true,
    val locationRequired: Boolean = false,
    val expiresAt: String? = null,
    val localFilePath: String? = null,
    val answer: String = "",
    val locationSampleCount: Int = 0,
    val message: String? = null,
    val nextStep: String? = null,
    val videoRequired: Boolean = false,
    val videoReady: Boolean = false,
    val videoDurationMs: Long? = null,
) {
    val answerReady: Boolean
        get() = when (stateType) {
            "NUMERIC", "BINARY" -> answer.isNotBlank()
            "VISUAL" -> true
            else -> false
        }

    val locationReady: Boolean
        get() = !locationRequired || locationSampleCount > 0

    val canSubmit: Boolean
        get() = stage == EvidenceCaptureStage.REVIEW && answerReady && locationReady && (!videoRequired || videoReady)
}

@HiltViewModel
class EvidenceCaptureViewModel @Inject constructor(
    private val repository: EvidenceCaptureRepository,
    private val location: EvidenceLocationProvider,
    private val workScheduler: EvidenceWorkScheduler,
    private val pendingEvidenceDao: PendingEvidenceDao,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EvidenceCaptureUiState())
    val state: StateFlow<EvidenceCaptureUiState> = mutableState.asStateFlow()

    private var observationJob: Job? = null

    fun open(
        acceptanceId: String,
        refreshId: String,
    ) {
        observationJob?.cancel()
        mutableState.value = EvidenceCaptureUiState(
            stage = EvidenceCaptureStage.LOADING,
            acceptanceId = acceptanceId,
            refreshId = refreshId,
        )
        observationJob = viewModelScope.launch {
            pendingEvidenceDao.observePending().collect { rows ->
                val current = mutableState.value
                val saved = rows.firstOrNull { it.evidenceId == current.evidenceId && it.acceptanceId == acceptanceId }
                if (current.stage in setOf(EvidenceCaptureStage.QUEUED, EvidenceCaptureStage.SUBMITTING) &&
                    saved?.uploadState in setOf(PendingEvidenceStatus.COMMITTED, PendingEvidenceStatus.VERIFYING, PendingEvidenceStatus.VERIFIED)) {
                    mutableState.update { it.copy(stage = EvidenceCaptureStage.SUBMITTED, message = "Evidence committed successfully.") }
                }
            }
        }
        viewModelScope.launch {
            try {
                applyRecovery(withTimeout(OPERATION_WAIT_MS) { repository.load(acceptanceId, refreshId) })
            } catch (_: TimeoutCancellationException) {
                mutableState.update {
                    it.copy(
                        stage = EvidenceCaptureStage.ERROR,
                        message = "Task check timed out after 30 seconds. Try again.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showFailure(error)
            }
        }
    }

    fun beginCapture() {
        val acceptanceId = mutableState.value.acceptanceId ?: return
        val refreshId = mutableState.value.refreshId ?: return

        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    stage = EvidenceCaptureStage.PREPARING,
                    message = "Securing a fresh, one-time evidence challenge…",
                )
            }
            try {
                withTimeout(OPERATION_WAIT_MS) { enterCamera(repository.begin(acceptanceId, refreshId)) }
            } catch (_: TimeoutCancellationException) {
                mutableState.update {
                    it.copy(
                        stage = EvidenceCaptureStage.READY,
                        message = "Proof setup timed out after 30 seconds. Try again; no proof was submitted.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showFailure(error)
            }
        }
    }

    fun permissionDenied() {
        mutableState.update {
            it.copy(
                stage = EvidenceCaptureStage.ERROR,
                message = if (it.locationRequired) {
                    "Camera and precise location permissions are required for this proof."
                } else {
                    "Camera permission is required for this proof."
                },
            )
        }
    }

    fun cameraFailure() {
        mutableState.update {
            it.copy(
                stage = EvidenceCaptureStage.ERROR,
                message = "Camera could not start safely.",
            )
        }
    }

    fun photoCaptured(photo: CapturedPhoto) {
        val evidenceId = mutableState.value.evidenceId ?: return
        val expectedPath = mutableState.value.localFilePath ?: return
        if (photo.file.absolutePath != expectedPath) {
            cameraFailure()
            return
        }

        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    stage = EvidenceCaptureStage.PROCESSING,
                    message = "Securing the capture and computing its integrity hash…",
                )
            }
            try {
                withTimeout(OPERATION_WAIT_MS) {
                    var draft = repository.completeCapture(
                    evidenceId = evidenceId,
                    captureStartedMonotonicMs = photo.captureStartedElapsedMs,
                    captureCompletedMonotonicMs = photo.captureCompletedElapsedMs,
                )

                if (draft.locationRequired) {
                    try {
                        draft = repository.addLocationSample(
                            evidenceId,
                            location.currentSample(),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // A pre-capture sample may already exist.
                    }
                }

                applyDraft(
                    draft,
                    if (draft.videoRequired && draft.video == null) EvidenceCaptureStage.VIDEO else EvidenceCaptureStage.REVIEW,
                    if (draft.locationRequired && draft.locationSampleCount == 0) {
                        "Photo is saved. Refresh precise location before submitting."
                    } else {
                        null
                    },
                )
                }
            } catch (_: TimeoutCancellationException) {
                mutableState.update { it.copy(stage = EvidenceCaptureStage.ERROR, message = "Photo processing timed out. Your photo is saved; check the task to continue.") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showFailure(error)
            }
        }
    }

    fun videoCaptured(video: CapturedVideo) {
        val state = mutableState.value
        val id = state.evidenceId ?: return
        if (video.file.absolutePath != state.localFilePath + ".mp4" || state.stage != EvidenceCaptureStage.VIDEO) return
        viewModelScope.launch {
            mutableState.update { it.copy(stage=EvidenceCaptureStage.PROCESSING,message="Securing your video…") }
            try {
                withTimeout(OPERATION_WAIT_MS) {
                    var draft = repository.completeVideo(id,video.startedMs,video.completedMs,video.durationMs)
                if (draft.locationRequired) try { draft=repository.addLocationSample(id,location.currentSample()) }
                    catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                applyDraft(draft,EvidenceCaptureStage.REVIEW,null)
                }
            } catch (_: TimeoutCancellationException) {
                mutableState.update { it.copy(stage = EvidenceCaptureStage.ERROR, message = "Video processing timed out. Check the saved task to continue.") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if(error is EvidenceCaptureFailure.Expired) showFailure(error) else mutableState.update { it.copy(stage=EvidenceCaptureStage.VIDEO,message=error.message) } }
        }
    }

    fun retakeVideo() {
        val id=mutableState.value.evidenceId ?: return
        viewModelScope.launch {
            try { applyDraft(repository.resetVideo(id),EvidenceCaptureStage.VIDEO,null) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { showFailure(error,preferReview=true) }
        }
    }

    fun updateAnswer(value: String) {
        if (mutableState.value.stage != EvidenceCaptureStage.REVIEW) return
        val evidenceId = mutableState.value.evidenceId ?: return
        val stateType = mutableState.value.stateType

        val normalized = when (stateType) {
            "NUMERIC" -> value.filter { it.isDigit() || it == '-' }.take(16)
            "BINARY" -> value.uppercase().take(64)
            else -> value.take(500)
        }
        mutableState.update { it.copy(answer = normalized) }

        viewModelScope.launch {
            try {
                repository.updateAnswer(evidenceId, normalized)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(message = "Answer is visible but could not be saved locally yet.")
                }
            }
        }
    }

    fun refreshLocation() {
        if (mutableState.value.stage != EvidenceCaptureStage.REVIEW) return
        val evidenceId = mutableState.value.evidenceId ?: return
        viewModelScope.launch {
            try {
                val draft = repository.addLocationSample(
                    evidenceId,
                    location.currentSample(),
                )
                applyDraft(
                    draft = draft,
                    stage = mutableState.value.stage,
                    message = "Precise location ready.",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: EvidenceLocationFailure.Permission) {
                mutableState.update {
                    it.copy(message = "Precise location permission is required.")
                }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(message = "A fresh location fix is not available yet. Try again.")
                }
            }
        }
    }

    fun recapture() {
        val evidenceId = mutableState.value.evidenceId ?: return
        viewModelScope.launch {
            try {
                withTimeout(OPERATION_WAIT_MS) { enterCamera(repository.resetCapture(evidenceId)) }
            } catch (_: TimeoutCancellationException) {
                mutableState.update { it.copy(stage = EvidenceCaptureStage.ERROR, message = "Camera setup timed out. Check the task to retry.") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showFailure(error)
            }
        }
    }

    fun submit() {
        val evidenceId = mutableState.value.evidenceId ?: return
        if (!mutableState.value.canSubmit) return
        mutableState.update { it.copy(stage = EvidenceCaptureStage.SUBMITTING) }

        viewModelScope.launch {
            try {
                repository.requestSubmission(evidenceId)
                workScheduler.schedule(evidenceId)

                mutableState.update {
                    it.copy(
                        stage = EvidenceCaptureStage.SUBMITTING,
                        message = "Uploading the exact captured bytes and committing proof…",
                    )
                }

                val result = withTimeout(OPERATION_WAIT_MS) { repository.submit(evidenceId) }
                workScheduler.cancel(evidenceId)

                mutableState.update {
                    it.copy(
                        stage = EvidenceCaptureStage.SUBMITTED,
                        message = if (result.evidence.replayed) {
                            "Evidence was already committed safely."
                        } else {
                            "Evidence committed successfully."
                        },
                        nextStep = result.evidence.nextStep,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                mutableState.update {
                    it.copy(
                        stage = EvidenceCaptureStage.QUEUED,
                        message = "Submission check timed out after 30 seconds. Your proof is saved and retry remains queued.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: EvidenceCaptureFailure.Network) {
                mutableState.update {
                    it.copy(
                        stage = EvidenceCaptureStage.QUEUED,
                        message = "Evidence is saved. Upload will retry when connectivity is available.",
                    )
                }
            } catch (_: EvidenceCaptureFailure.Server) {
                mutableState.update {
                    it.copy(
                        stage = EvidenceCaptureStage.QUEUED,
                        message = "Evidence is saved. Server retry is queued.",
                    )
                }
            } catch (_: EvidenceCaptureFailure.UploadNotReady) {
                mutableState.update {
                    it.copy(
                        stage = EvidenceCaptureStage.QUEUED,
                        message = "Evidence is saved. Storage confirmation will retry safely.",
                    )
                }
            } catch (_: EvidenceCaptureFailure.Storage) {
                mutableState.update {
                    it.copy(
                        stage = EvidenceCaptureStage.QUEUED,
                        message = "Evidence is saved. Storage retry is queued.",
                    )
                }
            } catch (error: Exception) {
                showFailure(error, preferReview = true)
            }
        }
    }

    fun retry() {
        val current = mutableState.value
        if (current.stage in setOf(EvidenceCaptureStage.SUBMITTING, EvidenceCaptureStage.LOADING)) return
        val acceptanceId = current.acceptanceId ?: return
        val refreshId = current.refreshId ?: return
        val queuedEvidenceId = current.evidenceId.takeIf { current.stage == EvidenceCaptureStage.QUEUED }
        mutableState.update {
            it.copy(
                stage = if (queuedEvidenceId != null) EvidenceCaptureStage.SUBMITTING else EvidenceCaptureStage.LOADING,
                message = "Checking the saved submission…",
            )
        }

        viewModelScope.launch {
            try {
                withTimeout(OPERATION_WAIT_MS) {
                    val submitted = queuedEvidenceId?.let { evidenceId ->
                        workScheduler.schedule(evidenceId)
                        repository.resumeSubmission(evidenceId)
                    }
                    if (submitted != null) {
                        workScheduler.cancel(checkNotNull(queuedEvidenceId))
                        mutableState.update {
                            it.copy(
                                stage = EvidenceCaptureStage.SUBMITTED,
                                message = "Evidence committed successfully.",
                                nextStep = submitted.evidence.nextStep,
                            )
                        }
                    } else {
                        applyRecovery(repository.load(acceptanceId, refreshId))
                    }
                }
            } catch (_: TimeoutCancellationException) {
                mutableState.update {
                    it.copy(
                        stage = if (queuedEvidenceId != null) EvidenceCaptureStage.QUEUED else EvidenceCaptureStage.ERROR,
                        message = "Submission check timed out after 30 seconds. Your proof is saved; try again.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (queuedEvidenceId != null && (error is EvidenceCaptureFailure.Network ||
                    error is EvidenceCaptureFailure.Server || error is EvidenceCaptureFailure.UploadNotReady ||
                    error is EvidenceCaptureFailure.Storage)) {
                    mutableState.update { it.copy(stage = EvidenceCaptureStage.QUEUED, message = "Submission could not finish yet. Your proof is saved; tap Check submission to retry.") }
                } else {
                    showFailure(error)
                }
            }
        }
    }

    private suspend fun enterCamera(initial: EvidenceCaptureDraft) {
        var draft = initial
        if (draft.locationRequired && draft.locationSampleCount == 0) {
            try {
                draft = repository.addLocationSample(
                    draft.evidenceId,
                    location.currentSample(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Capture can proceed; submission remains blocked until location is ready.
            }
        }

        draft = repository.markCapturing(draft.evidenceId)
        applyDraft(
            draft = draft,
            stage = EvidenceCaptureStage.CAMERA,
            message = if (draft.locationRequired && draft.locationSampleCount == 0) {
                "Camera is ready. Precise location is still required for submission."
            } else {
                null
            },
        )
    }

    private fun applyRecovery(recovery: EvidenceCaptureRecovery) {
        when (recovery) {
            is EvidenceCaptureRecovery.Ready -> {
                mutableState.update {
                    it.copy(
                        stage = EvidenceCaptureStage.READY,
                        acceptanceId = recovery.context.acceptanceId,
                        refreshId = recovery.context.refreshId,
                        question = recovery.context.question,
                        stateType = recovery.context.stateType,
                        mediaRequired = recovery.context.mediaRequired,
                        videoRequired = recovery.context.videoRequired,
                        locationRequired = recovery.context.locationRequired,
                        expiresAt = recovery.context.claimDeadline
                            ?: recovery.context.evidenceDeadline,
                        message = null,
                    )
                }
            }

            is EvidenceCaptureRecovery.Draft -> {
                val status = recovery.draft.status
                val stage = if (recovery.draft.submissionRequested && status in setOf(
                        PendingEvidenceStatus.CAPTURED_LOCAL, PendingEvidenceStatus.HASHING,
                        PendingEvidenceStatus.UPLOAD_READY)) EvidenceCaptureStage.QUEUED else when (status) {
                    PendingEvidenceStatus.CHALLENGE_ISSUED,
                    PendingEvidenceStatus.CAPTURING ->
                        EvidenceCaptureStage.CAMERA

                    PendingEvidenceStatus.CAPTURED_LOCAL,
                    PendingEvidenceStatus.HASHING,
                    PendingEvidenceStatus.UPLOAD_READY ->
                        EvidenceCaptureStage.REVIEW

                    PendingEvidenceStatus.UPLOADING,
                    PendingEvidenceStatus.UPLOADED,
                    PendingEvidenceStatus.COMMITTING ->
                        EvidenceCaptureStage.QUEUED

                    PendingEvidenceStatus.COMMITTED,
                    PendingEvidenceStatus.VERIFYING,
                    PendingEvidenceStatus.VERIFIED ->
                        EvidenceCaptureStage.SUBMITTED

                    PendingEvidenceStatus.EXPIRED -> EvidenceCaptureStage.EXPIRED

                    PendingEvidenceStatus.REJECTED,
                    PendingEvidenceStatus.CONFLICT,
                    PendingEvidenceStatus.FAILED ->
                        EvidenceCaptureStage.ERROR
                }

                if (stage == EvidenceCaptureStage.QUEUED) {
                    workScheduler.schedule(recovery.draft.evidenceId)
                }
                applyDraft(recovery.draft, if (stage == EvidenceCaptureStage.REVIEW && recovery.draft.videoRequired && recovery.draft.video == null) EvidenceCaptureStage.VIDEO else stage, null)
            }

            is EvidenceCaptureRecovery.Submitted -> {
                mutableState.update {
                    it.copy(
                        stage = EvidenceCaptureStage.SUBMITTED,
                        evidenceId = recovery.evidenceId,
                        message = "Evidence was already submitted.",
                    )
                }
            }
        }
    }

    private fun applyDraft(
        draft: EvidenceCaptureDraft,
        stage: EvidenceCaptureStage,
        message: String?,
    ) {
        mutableState.update {
            it.copy(
                stage = stage,
                acceptanceId = draft.acceptanceId,
                refreshId = draft.refreshId,
                evidenceId = draft.evidenceId,
                question = draft.question,
                stateType = draft.stateType,
                mediaRequired = draft.mediaRequired,
                videoRequired = draft.videoRequired,
                videoReady = draft.video != null,
                videoDurationMs = draft.video?.durationMs,
                locationRequired = draft.locationRequired,
                expiresAt = draft.expiresAt,
                localFilePath = draft.localFile.absolutePath,
                answer = draft.answer.orEmpty(),
                locationSampleCount = draft.locationSampleCount,
                message = message,
            )
        }
    }

    private companion object {
        const val OPERATION_WAIT_MS = 30_000L
    }

    private fun showFailure(
        error: Exception,
        preferReview: Boolean = false,
    ) {
        val current = mutableState.value
        val stage = when {
            error is EvidenceCaptureFailure.Expired -> EvidenceCaptureStage.EXPIRED
            error is EvidenceCaptureFailure.Stale -> EvidenceCaptureStage.ERROR
            preferReview && current.evidenceId != null -> EvidenceCaptureStage.REVIEW
            else -> EvidenceCaptureStage.ERROR
        }

        val message = when (error) {
            is EvidenceCaptureFailure,
            is EvidenceLocationFailure -> error.message
            else -> "Evidence capture could not continue safely."
        }

        mutableState.update {
            it.copy(
                stage = stage,
                message = message,
            )
        }
    }
}
