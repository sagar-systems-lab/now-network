package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.VerificationApiClient
import com.sagarsystemslab.nownetwork.network.VerificationResultDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

data class VerificationOutcome(
    val verificationResultId: String,
    val refreshId: String,
    val result: String,
    val status: String,
    val reasonCodes: List<String>,
    val evidenceIds: List<String>,
    val finalAnswer: JsonElement?,
    val evidenceSetRevision: Int,
    val policyVersion: Int,
    val replayed: Boolean,
    val nextStep: String,
    val projection: VerificationProjection?,
)

data class VerificationProjection(
    val stateId: String,
    val stateRevision: Long,
    val currentValue: JsonElement,
    val freshness: String?,
    val observedAt: String,
    val freshUntil: String,
    val replayed: Boolean,
    val superseded: Boolean,
)

sealed class VerificationFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Retryable(cause: Throwable) :
        VerificationFailure("Verification result is temporarily unavailable.", cause)

    class Expired :
        VerificationFailure("This refresh expired before verification completed.")

    class NotReady(message: String) : VerificationFailure(message)

    class Unavailable(message: String) : VerificationFailure(message)

    class Protocol(message: String, cause: Throwable? = null) :
        VerificationFailure(message, cause)
}

interface VerificationRepository {
    suspend fun verify(refreshId: String): VerificationOutcome
}

@Singleton
class DefaultVerificationRepository @Inject constructor(
    private val auth: AuthGateway,
    private val api: VerificationApiClient,
) : VerificationRepository {
    override suspend fun verify(refreshId: String): VerificationOutcome {
        requireUuid(refreshId, "refresh")

        val dto = withAuthRetry { token ->
            api.verifyRefresh(
                refreshId = refreshId,
                accessToken = token,
            )
        }
        validate(dto, refreshId)

        return VerificationOutcome(
            verificationResultId = dto.verificationResultId,
            refreshId = dto.refreshId,
            result = dto.result,
            status = dto.status,
            reasonCodes = dto.reasonCodes,
            evidenceIds = dto.evidenceIds,
            finalAnswer = dto.finalAnswer,
            evidenceSetRevision = dto.evidenceSetRevision,
            policyVersion = dto.policyVersion,
            replayed = dto.replayed,
            nextStep = dto.nextStep,
            projection = dto.stateProjection?.let { projection ->
                VerificationProjection(
                    stateId = projection.stateId,
                    stateRevision = projection.stateRevision,
                    currentValue = projection.currentValue,
                    freshness = projection.freshness,
                    observedAt = projection.observedAt,
                    freshUntil = projection.freshUntil,
                    replayed = projection.replayed,
                    superseded = projection.superseded,
                )
            },
        )
    }

    private fun validate(
        dto: VerificationResultDto,
        refreshId: String,
    ) {
        requireUuid(dto.verificationResultId, "verification result")
        if (dto.refreshId != refreshId) {
            throw VerificationFailure.Protocol("Verification refresh identity changed.")
        }
        if (dto.evidenceSetRevision < 1 || dto.evidenceIds.isEmpty()) {
            throw VerificationFailure.Protocol("Verification evidence set is empty.")
        }
        if (dto.policyVersion < 1) {
            throw VerificationFailure.Protocol("Verification policy version is invalid.")
        }

        val expectedStatus = when (dto.result) {
            "VERIFIED" -> "VERIFIED"
            "CONFLICT" -> "CONFLICT"
            "REQUIRES_ADDITIONAL_VERIFICATION" -> "WAITING_FOR_MORE_EVIDENCE"
            "REJECTED", "EXPIRED" -> "REJECTED"
            else -> throw VerificationFailure.Protocol(
                "Unsupported verification result.",
            )
        }
        if (dto.status != expectedStatus) {
            throw VerificationFailure.Protocol("Verification result/status mismatch.")
        }

        if (dto.result == "VERIFIED") {
            val projection = dto.stateProjection
                ?: throw VerificationFailure.Protocol(
                    "Verified result is missing state projection.",
                )
            if (
                projection.refreshId != refreshId ||
                projection.verificationResultId != dto.verificationResultId
            ) {
                throw VerificationFailure.Protocol(
                    "State projection identity does not match verification.",
                )
            }
            if (projection.superseded && projection.freshness != null) {
                throw VerificationFailure.Protocol(
                    "Superseded projection cannot report current freshness.",
                )
            }
        } else if (dto.stateProjection != null) {
            throw VerificationFailure.Protocol(
                "Non-verified result unexpectedly included a state projection.",
            )
        }
    }

    private suspend fun <T> withAuthRetry(
        block: suspend (String) -> T,
    ): T {
        val session = try {
            auth.ensureAnonymousSession()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw VerificationFailure.Retryable(error)
        }

        return try {
            block(session.accessToken)
        } catch (error: ApiFailure.AuthExpired) {
            try {
                block(auth.refreshIfNeeded().accessToken)
            } catch (refreshError: CancellationException) {
                throw refreshError
            } catch (refreshError: ApiFailure) {
                throw refreshError.toVerificationFailure()
            } catch (refreshError: Exception) {
                throw VerificationFailure.Retryable(refreshError)
            }
        } catch (error: ApiFailure) {
            throw error.toVerificationFailure()
        }
    }

    private fun ApiFailure.toVerificationFailure(): VerificationFailure =
        when (this) {
            is ApiFailure.NetworkUnavailable,
            is ApiFailure.Timeout,
            is ApiFailure.RateLimited,
            is ApiFailure.ServerFailure ->
                VerificationFailure.Retryable(this)

            is ApiFailure.BusinessError ->
                when (code) {
                    "REFRESH_EXPIRED" -> VerificationFailure.Expired()
                    "VERIFICATION_NOT_ELIGIBLE" ->
                        VerificationFailure.NotReady(message ?: "Refresh is not ready for verification.")
                    "REVISION_CONFLICT" ->
                        VerificationFailure.Retryable(this)
                    "REFRESH_NOT_FOUND" ->
                        VerificationFailure.Unavailable("Refresh was not found.")
                    else ->
                        VerificationFailure.Unavailable(message ?: code)
                }

            is ApiFailure.Configuration ->
                VerificationFailure.Protocol(
                    message ?: "Verification configuration is incomplete.",
                    this,
                )

            is ApiFailure.ProtocolError ->
                VerificationFailure.Protocol(
                    message ?: "Verification response contract failed.",
                    this,
                )

            is ApiFailure.AuthExpired ->
                VerificationFailure.Unavailable("Session expired.")
        }

    private fun requireUuid(value: String, label: String) {
        try {
            UUID.fromString(value)
        } catch (error: IllegalArgumentException) {
            throw VerificationFailure.Protocol("Invalid $label identity.", error)
        }
    }
}
