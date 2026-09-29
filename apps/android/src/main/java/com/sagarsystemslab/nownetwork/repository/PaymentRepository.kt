package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationDao
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationEntity
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.PaymentApiClient
import com.sagarsystemslab.nownetwork.network.PaymentStatusDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

data class PaymentOutcome(
    val refreshId: String,
    val verificationResultId: String,
    val settlementId: String?,
    val settlementStatus: String,
    val paymentStatus: String,
    val chainSignature: String?,
    val chainCommitment: String?,
    val confirmedAt: String?,
    val finalizedAt: String?,
    val updatedAt: String,
)

sealed class PaymentFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Retryable(cause: Throwable) :
        PaymentFailure("Payment status is temporarily unavailable.", cause)

    class Unavailable(message: String) : PaymentFailure(message)

    class Protocol(message: String, cause: Throwable? = null) :
        PaymentFailure(message, cause)
}

interface PaymentRepository {
    suspend fun prime(refreshId: String)
    suspend fun check(refreshId: String): PaymentOutcome
}

@Singleton
class DefaultPaymentRepository @Inject constructor(
    private val auth: AuthGateway,
    private val api: PaymentApiClient,
    private val operationDao: ActiveOperationDao,
    private val serverClock: ServerClock,
) : PaymentRepository {
    override suspend fun prime(refreshId: String) {
        requireUuid(refreshId, "refresh")
        val operationId = operationId(refreshId)
        val existing = operationDao.get(operationId)
        if (existing != null) return

        val now = serverClock.nowMillis()
        operationDao.upsert(
            ActiveOperationEntity(
                operationId = operationId,
                type = OPERATION_TYPE,
                entityId = refreshId,
                localState = "PENDING",
                remoteState = "NOT_STARTED",
                chainSignature = null,
                lastValidBlockHeight = null,
                idempotencyKey = operationId,
                createdAtMs = now,
                updatedAtMs = now,
            ),
        )
    }

    override suspend fun check(refreshId: String): PaymentOutcome {
        prime(refreshId)

        val dto = withAuthRetry { token ->
            api.paymentStatus(
                refreshId = refreshId,
                accessToken = token,
            )
        }
        validate(dto, refreshId)

        val current = checkNotNull(operationDao.get(operationId(refreshId)))
        operationDao.upsert(
            current.copy(
                localState = dto.paymentStatus,
                remoteState = dto.settlementStatus,
                chainSignature = dto.chainSignature,
                updatedAtMs = serverClock.nowMillis(),
            ),
        )

        return PaymentOutcome(
            refreshId = dto.refreshId,
            verificationResultId = dto.verificationResultId,
            settlementId = dto.settlementId,
            settlementStatus = dto.settlementStatus,
            paymentStatus = dto.paymentStatus,
            chainSignature = dto.chainSignature,
            chainCommitment = dto.chainCommitment,
            confirmedAt = dto.confirmedAt,
            finalizedAt = dto.finalizedAt,
            updatedAt = dto.updatedAt,
        )
    }

    private fun validate(
        dto: PaymentStatusDto,
        refreshId: String,
    ) {
        if (dto.refreshId != refreshId) {
            throw PaymentFailure.Protocol("Payment refresh identity changed.")
        }
        requireUuid(dto.verificationResultId, "verification result")
        dto.settlementId?.let { requireUuid(it, "settlement") }

        val expectedProduct = when (dto.settlementStatus) {
            "NOT_STARTED",
            "ELIGIBLE",
            "BUILDING",
            "SUBMITTING",
            "NOT_SETTLED" -> "PENDING"

            "SUBMITTED",
            "VERIFYING" -> "VERIFYING"

            "CONFIRMED",
            "FINALIZING",
            "FINALIZED" -> "PAID"

            "FAILED" -> "FAILED"
            else -> throw PaymentFailure.Protocol("Unsupported settlement status.")
        }
        if (dto.paymentStatus != expectedProduct) {
            throw PaymentFailure.Protocol("Payment and settlement status disagree.")
        }

        if (
            dto.settlementStatus != "NOT_STARTED" &&
            dto.settlementId == null
        ) {
            throw PaymentFailure.Protocol("Settlement identity is missing.")
        }

        if (dto.paymentStatus == "PAID") {
            if (
                dto.chainSignature.isNullOrBlank() ||
                dto.chainCommitment !in setOf("confirmed", "finalized") ||
                dto.confirmedAt == null
            ) {
                throw PaymentFailure.Protocol(
                    "Paid state is missing confirmed chain authority.",
                )
            }
        }

        if (
            dto.settlementStatus == "FINALIZED" &&
            (dto.chainCommitment != "finalized" || dto.finalizedAt == null)
        ) {
            throw PaymentFailure.Protocol(
                "Finalized payment is missing final chain authority.",
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
            throw PaymentFailure.Retryable(error)
        }

        return try {
            block(session.accessToken)
        } catch (error: ApiFailure.AuthExpired) {
            try {
                block(auth.refreshIfNeeded().accessToken)
            } catch (refreshError: CancellationException) {
                throw refreshError
            } catch (refreshError: ApiFailure) {
                throw refreshError.toPaymentFailure()
            } catch (refreshError: Exception) {
                throw PaymentFailure.Retryable(refreshError)
            }
        } catch (error: ApiFailure) {
            throw error.toPaymentFailure()
        }
    }

    private fun ApiFailure.toPaymentFailure(): PaymentFailure =
        when (this) {
            is ApiFailure.NetworkUnavailable,
            is ApiFailure.Timeout,
            is ApiFailure.RateLimited,
            is ApiFailure.ServerFailure ->
                PaymentFailure.Retryable(this)

            is ApiFailure.BusinessError ->
                when (code) {
                    "PAYMENT_NOT_FOUND" ->
                        PaymentFailure.Unavailable("Payment status was not found.")
                    else -> PaymentFailure.Unavailable(message ?: code)
                }

            is ApiFailure.Configuration ->
                PaymentFailure.Protocol(
                    message ?: "Payment configuration is incomplete.",
                    this,
                )

            is ApiFailure.ProtocolError ->
                PaymentFailure.Protocol(
                    message ?: "Payment response contract failed.",
                    this,
                )

            is ApiFailure.AuthExpired ->
                PaymentFailure.Unavailable("Session expired.")
        }

    private fun requireUuid(value: String, label: String) {
        try {
            UUID.fromString(value)
        } catch (error: IllegalArgumentException) {
            throw PaymentFailure.Protocol("Invalid $label identity.", error)
        }
    }

    private fun operationId(refreshId: String): String =
        "payment:$refreshId"

    private companion object {
        const val OPERATION_TYPE = "SETTLEMENT"
    }
}
