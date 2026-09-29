package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AppSession
import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.ReceiptApiClient
import com.sagarsystemslab.nownetwork.network.ReceiptDto
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptRepositoryTest {
    @Test
    fun confirmedPaymentWaitsForFinalizedAuthorityWithoutReadingReceipt() = runBlocking {
        val fixture = Fixture(
            payment = paymentOutcome(
                settlementStatus = "CONFIRMED",
                chainCommitment = "confirmed",
                finalizedAt = null,
            ),
        )

        val result = fixture.repository.load(REFRESH_ID)

        assertTrue(result is ReceiptLoadResult.Finalizing)
        assertEquals(0, fixture.api.calls)
    }

    @Test
    fun finalizedPaymentReturnsStrictlyBoundFinalReceipt() = runBlocking {
        val fixture = Fixture()

        val result = fixture.repository.load(REFRESH_ID)

        assertTrue(result is ReceiptLoadResult.Ready)
        val receipt = (result as ReceiptLoadResult.Ready).receipt
        assertEquals(RECEIPT_ID, receipt.receiptId)
        assertEquals(SETTLEMENT_ID, receipt.settlementId)
        assertEquals("signature-final", receipt.settlementSignature)
    }

    @Test
    fun finalizedSettlementWithWorkerLagRemainsFinalizing() = runBlocking {
        val fixture = Fixture()
        fixture.api.failure = ApiFailure.BusinessError(
            statusCode = 404,
            code = "RECEIPT_NOT_FOUND",
            safeToRetry = false,
            retryAfterMs = null,
            message = "Receipt was not found.",
        )

        val result = fixture.repository.load(REFRESH_ID)

        assertTrue(result is ReceiptLoadResult.Finalizing)
        assertEquals(1, fixture.api.calls)
    }

    @Test
    fun settlementIdentityMismatchFailsClosed() = runBlocking {
        val fixture = Fixture(
            receipt = receiptDto(settlementId = OTHER_SETTLEMENT_ID),
        )

        val error = runCatching {
            fixture.repository.load(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is ReceiptFailure.Protocol)
    }

    @Test
    fun malformedReceiptDigestFailsClosed() = runBlocking {
        val fixture = Fixture(
            receipt = receiptDto(receiptDigest = "abcd"),
        )

        val error = runCatching {
            fixture.repository.load(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is ReceiptFailure.Protocol)
    }

    @Test
    fun verificationIdentityMismatchFailsClosed() = runBlocking {
        val fixture = Fixture(
            receipt = receiptDto(
                verificationResultId = OTHER_VERIFICATION_ID,
            ),
        )

        val error = runCatching {
            fixture.repository.load(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is ReceiptFailure.Protocol)
    }

    @Test
    fun settlementSignatureMismatchFailsClosed() = runBlocking {
        val fixture = Fixture(
            receipt = receiptDto(
                settlementSignature = "different-signature",
            ),
        )

        val error = runCatching {
            fixture.repository.load(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is ReceiptFailure.Protocol)
    }

    @Test
    fun settlementFinalizationTimestampMismatchFailsClosed() = runBlocking {
        val fixture = Fixture(
            receipt = receiptDto(
                finalizedAt = "2026-09-29T11:00:01Z",
            ),
        )

        val error = runCatching {
            fixture.repository.load(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is ReceiptFailure.Protocol)
    }

    @Test
    fun invalidRewardAuthorityFailsClosed() = runBlocking {
        val fixture = Fixture(
            receipt = receiptDto(
                rewardAmountAtomic = "0",
            ),
        )

        val error = runCatching {
            fixture.repository.load(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is ReceiptFailure.Protocol)
    }

    @Test
    fun paymentProtocolFailureRemainsReceiptProtocolFailure() = runBlocking {
        val repository = DefaultReceiptRepository(
            paymentRepository = object : PaymentRepository {
                override suspend fun prime(refreshId: String) = Unit

                override suspend fun check(refreshId: String): PaymentOutcome {
                    throw PaymentFailure.Protocol("payment identity mismatch")
                }
            },
            auth = FakeAuthGateway(),
            api = FakeReceiptApiClient(receiptDto()),
        )

        val error = runCatching {
            repository.load(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is ReceiptFailure.Protocol)
    }

    @Test
    fun retryableReceiptReadFailureDoesNotBecomeFinal() = runBlocking {
        val fixture = Fixture()
        fixture.api.failure = ApiFailure.NetworkUnavailable(IOException("offline"))

        val error = runCatching {
            fixture.repository.load(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is ReceiptFailure.Retryable)
    }

    @Test
    fun expiredAuthRefreshesOnceForSafeReceiptRead() = runBlocking {
        val fixture = Fixture()
        fixture.api.failFirstWithAuthExpired = true

        val result = fixture.repository.load(REFRESH_ID)

        assertTrue(result is ReceiptLoadResult.Ready)
        assertEquals(2, fixture.api.calls)
        assertEquals(1, fixture.auth.refreshCalls)
        assertEquals("token-refreshed", fixture.api.lastToken)
    }

    private class Fixture(
        payment: PaymentOutcome = paymentOutcome(),
        receipt: ReceiptDto = receiptDto(),
    ) {
        val paymentRepository = FakePaymentRepository(payment)
        val auth = FakeAuthGateway()
        val api = FakeReceiptApiClient(receipt)
        val repository = DefaultReceiptRepository(
            paymentRepository = paymentRepository,
            auth = auth,
            api = api,
        )
    }

    private class FakePaymentRepository(
        private val outcome: PaymentOutcome,
    ) : PaymentRepository {
        override suspend fun prime(refreshId: String) = Unit

        override suspend fun check(refreshId: String): PaymentOutcome = outcome
    }

    private class FakeAuthGateway : AuthGateway {
        var refreshCalls = 0

        override suspend fun ensureAnonymousSession(): AppSession =
            AppSession(
                authSubjectId = "actor-test",
                accessToken = "token-initial",
            )

        override suspend fun currentSession(): AppSession? =
            AppSession(
                authSubjectId = "actor-test",
                accessToken = "token-initial",
            )

        override suspend fun refreshIfNeeded(): AppSession {
            refreshCalls += 1
            return AppSession(
                authSubjectId = "actor-test",
                accessToken = "token-refreshed",
            )
        }

        override suspend fun signOutLocal() = Unit
    }

    private class FakeReceiptApiClient(
        private val response: ReceiptDto,
    ) : ReceiptApiClient {
        var calls = 0
        var lastToken: String? = null
        var failFirstWithAuthExpired = false
        var failure: ApiFailure? = null

        override suspend fun receipt(
            refreshId: String,
            accessToken: String,
        ): ReceiptDto {
            calls += 1
            lastToken = accessToken
            if (failFirstWithAuthExpired && calls == 1) {
                throw ApiFailure.AuthExpired("expired")
            }
            failure?.let { throw it }
            return response
        }
    }

    private companion object {
        const val REFRESH_ID = "91000000-0000-4000-8000-000000000001"
        const val STATE_ID = "92000000-0000-4000-8000-000000000001"
        const val VERIFICATION_ID = "93000000-0000-4000-8000-000000000001"
        const val OTHER_VERIFICATION_ID = "93000000-0000-4000-8000-000000000002"
        const val SETTLEMENT_ID = "94000000-0000-4000-8000-000000000001"
        const val OTHER_SETTLEMENT_ID = "94000000-0000-4000-8000-000000000002"
        const val RECEIPT_ID = "95000000-0000-4000-8000-000000000001"
        const val FINALIZED_AT = "2026-09-29T11:00:00Z"
        val DIGEST = "ab".repeat(32)

        fun paymentOutcome(
            settlementStatus: String = "FINALIZED",
            chainCommitment: String? = "finalized",
            finalizedAt: String? = FINALIZED_AT,
        ): PaymentOutcome =
            PaymentOutcome(
                refreshId = REFRESH_ID,
                verificationResultId = VERIFICATION_ID,
                settlementId = SETTLEMENT_ID,
                settlementStatus = settlementStatus,
                paymentStatus = "PAID",
                chainSignature = "signature-final",
                chainCommitment = chainCommitment,
                confirmedAt = "2026-09-29T10:59:58Z",
                finalizedAt = finalizedAt,
                updatedAt = FINALIZED_AT,
            )

        fun receiptDto(
            settlementId: String = SETTLEMENT_ID,
            verificationResultId: String = VERIFICATION_ID,
            receiptDigest: String = DIGEST,
            settlementSignature: String = "signature-final",
            finalizedAt: String = FINALIZED_AT,
            rewardAmountAtomic: String = "500000",
        ): ReceiptDto =
            ReceiptDto(
                receiptId = RECEIPT_ID,
                refreshId = REFRESH_ID,
                stateId = STATE_ID,
                verificationResultId = verificationResultId,
                settlementId = settlementId,
                status = "FINAL",
                finalValue = buildJsonObject {
                    put("kind", "numeric")
                    put("value", 12)
                },
                observedAt = "2026-09-29T10:58:00Z",
                verificationClass = "FAST",
                rewardAmountAtomic = rewardAmountAtomic,
                rewardMint = "So11111111111111111111111111111111111111112",
                verificationDigest = DIGEST,
                settlementOperationHash = DIGEST,
                receiptDigest = receiptDigest,
                settlementSignature = settlementSignature,
                chainCommitment = "finalized",
                finalizedAt = finalizedAt,
                revision = 1L,
            )
    }
}
