package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AppSession
import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationDao
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationEntity
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.PaymentApiClient
import com.sagarsystemslab.nownetwork.network.PaymentStatusDto
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentRepositoryTest {
    @Test
    fun primePersistsRecoverablePendingOperation() = runBlocking {
        val fixture = Fixture()

        fixture.repository.prime(REFRESH_ID)

        val operation = fixture.dao.get("payment:$REFRESH_ID")
        assertEquals("SETTLEMENT", operation?.type)
        assertEquals("PENDING", operation?.localState)
        assertEquals("NOT_STARTED", operation?.remoteState)
    }

    @Test
    fun ambiguousSettlementPersistsVerifyingWithoutResendState() = runBlocking {
        val fixture = Fixture(
            response = paymentDto(
                settlementId = SETTLEMENT_ID,
                settlementStatus = "VERIFYING",
                paymentStatus = "VERIFYING",
                chainSignature = "signature-a",
            ),
        )

        val outcome = fixture.repository.check(REFRESH_ID)

        assertEquals("VERIFYING", outcome.paymentStatus)
        val operation = fixture.dao.get("payment:$REFRESH_ID")
        assertEquals("VERIFYING", operation?.localState)
        assertEquals("VERIFYING", operation?.remoteState)
        assertEquals("signature-a", operation?.chainSignature)
    }

    @Test
    fun retryableReadFailurePreservesLastKnownDurableState() = runBlocking {
        val fixture = Fixture()
        fixture.repository.prime(REFRESH_ID)
        fixture.api.failure = ApiFailure.NetworkUnavailable(IOException("offline"))

        val error = runCatching {
            fixture.repository.check(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is PaymentFailure.Retryable)
        val operation = fixture.dao.get("payment:$REFRESH_ID")
        assertEquals("PENDING", operation?.localState)
        assertEquals("NOT_STARTED", operation?.remoteState)
    }

    @Test
    fun missingPaymentStatusIsTerminalLocally() = runBlocking {
        val fixture = Fixture()
        fixture.api.failure = ApiFailure.BusinessError(
            statusCode = 404,
            code = "PAYMENT_NOT_FOUND",
            safeToRetry = false,
            retryAfterMs = null,
            message = "Payment status was not found.",
        )

        val error = runCatching {
            fixture.repository.check(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is PaymentFailure.NotFound)
        val operation = fixture.dao.get("payment:$REFRESH_ID")
        assertEquals("CANCELLED", operation?.localState)
        assertEquals("NOT_FOUND", operation?.remoteState)
    }

    @Test
    fun paidStateWithoutConfirmedAuthorityFailsClosedBeforePersistence() = runBlocking {
        val fixture = Fixture(
            response = paymentDto(
                settlementId = SETTLEMENT_ID,
                settlementStatus = "CONFIRMED",
                paymentStatus = "PAID",
                chainSignature = null,
                chainCommitment = null,
                confirmedAt = null,
            ),
        )
        fixture.repository.prime(REFRESH_ID)

        val error = runCatching {
            fixture.repository.check(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is PaymentFailure.Protocol)
        val operation = fixture.dao.get("payment:$REFRESH_ID")
        assertEquals("PENDING", operation?.localState)
        assertEquals("NOT_STARTED", operation?.remoteState)
    }

    @Test
    fun provenAbsentSettlementReturnsToPendingWithoutLosingIdentity() = runBlocking {
        val fixture = Fixture(
            response = paymentDto(
                settlementId = SETTLEMENT_ID,
                settlementStatus = "NOT_SETTLED",
                paymentStatus = "PENDING",
                chainSignature = "signature-old",
            ),
        )

        val outcome = fixture.repository.check(REFRESH_ID)

        assertEquals("PENDING", outcome.paymentStatus)
        assertEquals("NOT_SETTLED", outcome.settlementStatus)
        val operation = fixture.dao.get("payment:$REFRESH_ID")
        assertEquals("PENDING", operation?.localState)
        assertEquals("NOT_SETTLED", operation?.remoteState)
        assertEquals("signature-old", operation?.chainSignature)
    }

    @Test
    fun finalizedSettlementPersistsPaidTerminalState() = runBlocking {
        val fixture = Fixture(
            response = paymentDto(
                settlementId = SETTLEMENT_ID,
                settlementStatus = "FINALIZED",
                paymentStatus = "PAID",
                chainSignature = "signature-final",
                chainCommitment = "finalized",
                confirmedAt = "2026-09-29T10:00:00Z",
                finalizedAt = "2026-09-29T10:00:05Z",
            ),
        )

        val outcome = fixture.repository.check(REFRESH_ID)

        assertEquals("PAID", outcome.paymentStatus)
        val operation = fixture.dao.get("payment:$REFRESH_ID")
        assertEquals("PAID", operation?.localState)
        assertEquals("FINALIZED", operation?.remoteState)
        assertEquals("signature-final", operation?.chainSignature)
    }

    @Test
    fun productAndSettlementStatusMismatchFailsClosedBeforePersistence() = runBlocking {
        val fixture = Fixture(
            response = paymentDto(
                settlementId = SETTLEMENT_ID,
                settlementStatus = "VERIFYING",
                paymentStatus = "PAID",
                chainSignature = "signature-a",
            ),
        )
        fixture.repository.prime(REFRESH_ID)

        val error = runCatching {
            fixture.repository.check(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is PaymentFailure.Protocol)
        val operation = fixture.dao.get("payment:$REFRESH_ID")
        assertEquals("PENDING", operation?.localState)
        assertEquals("NOT_STARTED", operation?.remoteState)
    }

    @Test
    fun reopeningPrimeDoesNotOverwriteRecoveredSettlementState() = runBlocking {
        val fixture = Fixture(
            response = paymentDto(
                settlementId = SETTLEMENT_ID,
                settlementStatus = "VERIFYING",
                paymentStatus = "VERIFYING",
                chainSignature = "signature-a",
            ),
        )
        fixture.repository.check(REFRESH_ID)

        fixture.repository.prime(REFRESH_ID)

        val operation = fixture.dao.get("payment:$REFRESH_ID")
        assertEquals("VERIFYING", operation?.localState)
        assertEquals("VERIFYING", operation?.remoteState)
        assertEquals("signature-a", operation?.chainSignature)
    }

    @Test
    fun expiredAuthRefreshesOnceForSafePaymentRead() = runBlocking {
        val fixture = Fixture()
        fixture.api.failFirstWithAuthExpired = true

        val outcome = fixture.repository.check(REFRESH_ID)

        assertEquals("PENDING", outcome.paymentStatus)
        assertEquals(2, fixture.api.calls)
        assertEquals(1, fixture.auth.refreshCalls)
        assertEquals("token-refreshed", fixture.api.lastToken)
    }

    private class Fixture(
        response: PaymentStatusDto = paymentDto(),
    ) {
        val auth = FakeAuthGateway()
        val api = FakePaymentApiClient(response)
        val dao = MemoryActiveOperationDao()
        val repository = DefaultPaymentRepository(
            auth = auth,
            api = api,
            operationDao = dao,
            serverClock = ServerClock(),
        )
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

    private class FakePaymentApiClient(
        private val response: PaymentStatusDto,
    ) : PaymentApiClient {
        var calls = 0
        var lastToken: String? = null
        var failFirstWithAuthExpired = false
        var failure: ApiFailure? = null

        override suspend fun paymentStatus(
            refreshId: String,
            accessToken: String,
        ): PaymentStatusDto {
            calls += 1
            lastToken = accessToken
            if (failFirstWithAuthExpired && calls == 1) {
                throw ApiFailure.AuthExpired("expired")
            }
            failure?.let { throw it }
            return response
        }
    }

    private class MemoryActiveOperationDao : ActiveOperationDao {
        private val values = linkedMapOf<String, ActiveOperationEntity>()
        private val flow = MutableStateFlow<List<ActiveOperationEntity>>(emptyList())

        override suspend fun get(operationId: String): ActiveOperationEntity? =
            values[operationId]

        override suspend fun getByIdempotencyKey(
            idempotencyKey: String,
        ): ActiveOperationEntity? =
            values.values.firstOrNull { it.idempotencyKey == idempotencyKey }

        override suspend fun listAll(): List<ActiveOperationEntity> =
            values.values.toList()

        override fun observeAll(): Flow<List<ActiveOperationEntity>> = flow

        override suspend fun upsert(entity: ActiveOperationEntity) {
            values[entity.operationId] = entity
            flow.value = values.values.toList()
        }

        override suspend fun delete(operationId: String) {
            values.remove(operationId)
            flow.value = values.values.toList()
        }
    }

    private companion object {
        const val REFRESH_ID = "81000000-0000-4000-8000-000000000001"
        const val VERIFICATION_ID = "82000000-0000-4000-8000-000000000001"
        const val SETTLEMENT_ID = "83000000-0000-4000-8000-000000000001"

        fun paymentDto(
            settlementId: String? = null,
            settlementStatus: String = "NOT_STARTED",
            paymentStatus: String = "PENDING",
            chainSignature: String? = null,
            chainCommitment: String? = null,
            confirmedAt: String? = null,
            finalizedAt: String? = null,
        ): PaymentStatusDto =
            PaymentStatusDto(
                refreshId = REFRESH_ID,
                verificationResultId = VERIFICATION_ID,
                settlementId = settlementId,
                settlementStatus = settlementStatus,
                paymentStatus = paymentStatus,
                chainSignature = chainSignature,
                chainCommitment = chainCommitment,
                confirmedAt = confirmedAt,
                finalizedAt = finalizedAt,
                updatedAt = "2026-09-29T10:00:00Z",
            )
    }
}
