package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.ReceiptApiClient
import com.sagarsystemslab.nownetwork.network.ReceiptDto
import java.math.BigInteger
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

data class FinalReceipt(
    val receiptId: String,
    val refreshId: String,
    val stateId: String,
    val verificationResultId: String,
    val settlementId: String,
    val finalValue: JsonElement,
    val observedAt: String,
    val verificationClass: String,
    val rewardAmountAtomic: String,
    val rewardMint: String,
    val verificationDigest: String,
    val settlementOperationHash: String,
    val receiptDigest: String,
    val settlementSignature: String,
    val finalizedAt: String,
    val revision: Long,
)

sealed interface ReceiptLoadResult {
    data class Finalizing(
        val refreshId: String,
        val reason: String,
    ) : ReceiptLoadResult

    data class Ready(
        val receipt: FinalReceipt,
    ) : ReceiptLoadResult
}

sealed class ReceiptFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Retryable(cause: Throwable) :
        ReceiptFailure("Receipt is temporarily unavailable.", cause)

    class Unavailable(message: String) : ReceiptFailure(message)

    class Protocol(message: String, cause: Throwable? = null) :
        ReceiptFailure(message, cause)
}

interface ReceiptRepository {
    suspend fun load(refreshId: String): ReceiptLoadResult
}

@Singleton
class DefaultReceiptRepository @Inject constructor(
    private val paymentRepository: PaymentRepository,
    private val auth: AuthGateway,
    private val api: ReceiptApiClient,
) : ReceiptRepository {
    override suspend fun load(refreshId: String): ReceiptLoadResult {
        requireUuid(refreshId, "refresh")

        val payment = try {
            paymentRepository.check(refreshId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PaymentFailure.Retryable) {
            throw ReceiptFailure.Retryable(error)
        } catch (error: PaymentFailure.Protocol) {
            throw ReceiptFailure.Protocol(
                error.message ?: "Payment authority failed validation.",
                error,
            )
        } catch (error: PaymentFailure) {
            throw ReceiptFailure.Unavailable(
                error.message ?: "Payment authority is unavailable.",
            )
        }

        if (payment.paymentStatus == "FAILED") {
            throw ReceiptFailure.Unavailable(
                "Settlement failed before a final receipt could be created.",
            )
        }

        if (payment.paymentStatus != "PAID") {
            return ReceiptLoadResult.Finalizing(
                refreshId = refreshId,
                reason = "Payment has not confirmed yet.",
            )
        }

        if (
            payment.settlementStatus != "FINALIZED" ||
            payment.chainCommitment != "finalized" ||
            payment.finalizedAt == null
        ) {
            return ReceiptLoadResult.Finalizing(
                refreshId = refreshId,
                reason = "Payment is confirmed. Final receipt is waiting for finalized chain authority.",
            )
        }

        val dto = try {
            withAuthRetry { token ->
                api.receipt(
                    refreshId = refreshId,
                    accessToken = token,
                )
            }
        } catch (error: ReceiptNotReady) {
            return ReceiptLoadResult.Finalizing(
                refreshId = refreshId,
                reason = "Settlement is finalized. Final receipt is being generated.",
            )
        }

        validate(
            dto = dto,
            payment = payment,
            refreshId = refreshId,
        )

        return ReceiptLoadResult.Ready(
            receipt = FinalReceipt(
                receiptId = dto.receiptId,
                refreshId = dto.refreshId,
                stateId = dto.stateId,
                verificationResultId = dto.verificationResultId,
                settlementId = dto.settlementId,
                finalValue = dto.finalValue,
                observedAt = dto.observedAt,
                verificationClass = dto.verificationClass,
                rewardAmountAtomic = dto.rewardAmountAtomic,
                rewardMint = dto.rewardMint,
                verificationDigest = dto.verificationDigest,
                settlementOperationHash = dto.settlementOperationHash,
                receiptDigest = dto.receiptDigest,
                settlementSignature = dto.settlementSignature,
                finalizedAt = dto.finalizedAt,
                revision = dto.revision,
            ),
        )
    }

    private fun validate(
        dto: ReceiptDto,
        payment: PaymentOutcome,
        refreshId: String,
    ) {
        requireUuid(dto.receiptId, "receipt")
        requireUuid(dto.stateId, "state")
        requireUuid(dto.verificationResultId, "verification result")
        requireUuid(dto.settlementId, "settlement")

        if (dto.refreshId != refreshId) {
            throw ReceiptFailure.Protocol("Receipt refresh identity changed.")
        }
        if (dto.verificationResultId != payment.verificationResultId) {
            throw ReceiptFailure.Protocol("Receipt verification identity changed.")
        }
        if (dto.settlementId != payment.settlementId) {
            throw ReceiptFailure.Protocol("Receipt settlement identity changed.")
        }
        if (dto.status != "FINAL") {
            throw ReceiptFailure.Protocol("Receipt is not final.")
        }
        if (dto.chainCommitment != "finalized") {
            throw ReceiptFailure.Protocol("Receipt is missing finalized chain commitment.")
        }
        if (
            dto.settlementSignature.isBlank() ||
            dto.settlementSignature != payment.chainSignature
        ) {
            throw ReceiptFailure.Protocol("Receipt settlement signature changed.")
        }
        if (
            payment.finalizedAt == null ||
            dto.finalizedAt != payment.finalizedAt
        ) {
            throw ReceiptFailure.Protocol("Receipt finalization authority changed.")
        }
        if (
            dto.verificationClass.isBlank() ||
            dto.rewardMint.isBlank() ||
            dto.revision <= 0L
        ) {
            throw ReceiptFailure.Protocol("Receipt authority fields are incomplete.")
        }

        val reward = try {
            BigInteger(dto.rewardAmountAtomic)
        } catch (error: NumberFormatException) {
            throw ReceiptFailure.Protocol("Receipt reward amount is invalid.", error)
        }
        if (reward <= BigInteger.ZERO) {
            throw ReceiptFailure.Protocol("Receipt reward amount is invalid.")
        }

        requireDigest(dto.verificationDigest, "verification")
        requireDigest(dto.settlementOperationHash, "settlement operation")
        requireDigest(dto.receiptDigest, "receipt")
        requireInstant(dto.observedAt, "observation")
        requireInstant(dto.finalizedAt, "finalization")
    }

    private suspend fun <T> withAuthRetry(
        block: suspend (String) -> T,
    ): T {
        val session = try {
            auth.ensureAnonymousSession()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ReceiptFailure.Retryable(error)
        }

        return try {
            block(session.accessToken)
        } catch (error: ApiFailure.AuthExpired) {
            try {
                block(auth.refreshIfNeeded().accessToken)
            } catch (refreshError: CancellationException) {
                throw refreshError
            } catch (refreshError: ApiFailure) {
                throw refreshError.toReceiptFailure()
            } catch (refreshError: Exception) {
                throw ReceiptFailure.Retryable(refreshError)
            }
        } catch (error: ApiFailure) {
            throw error.toReceiptFailure()
        }
    }

    private fun ApiFailure.toReceiptFailure(): Throwable =
        when (this) {
            is ApiFailure.NetworkUnavailable,
            is ApiFailure.Timeout,
            is ApiFailure.RateLimited,
            is ApiFailure.ServerFailure ->
                ReceiptFailure.Retryable(this)

            is ApiFailure.BusinessError ->
                if (code == "RECEIPT_NOT_FOUND") {
                    ReceiptNotReady()
                } else {
                    ReceiptFailure.Unavailable(message ?: code)
                }

            is ApiFailure.Configuration ->
                ReceiptFailure.Protocol(
                    message ?: "Receipt configuration is incomplete.",
                    this,
                )

            is ApiFailure.ProtocolError ->
                ReceiptFailure.Protocol(
                    message ?: "Receipt response contract failed.",
                    this,
                )

            is ApiFailure.AuthExpired ->
                ReceiptFailure.Unavailable("Session expired.")
        }

    private fun requireUuid(value: String, label: String) {
        try {
            UUID.fromString(value)
        } catch (error: IllegalArgumentException) {
            throw ReceiptFailure.Protocol("Invalid $label identity.", error)
        }
    }

    private fun requireDigest(value: String, label: String) {
        if (!HEX_32.matches(value)) {
            throw ReceiptFailure.Protocol("Invalid $label digest.")
        }
    }

    private fun requireInstant(value: String, label: String) {
        try {
            Instant.parse(value)
        } catch (error: DateTimeParseException) {
            throw ReceiptFailure.Protocol("Invalid receipt $label time.", error)
        }
    }

    private class ReceiptNotReady : Exception()

    private companion object {
        val HEX_32 = Regex("^[0-9a-f]{64}$")
    }
}
