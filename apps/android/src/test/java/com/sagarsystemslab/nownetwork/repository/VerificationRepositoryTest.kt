package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AppSession
import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.VerificationApiClient
import com.sagarsystemslab.nownetwork.network.VerificationResultDto
import com.sagarsystemslab.nownetwork.network.VerificationStateProjectionDto
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationRepositoryTest {
    @Test
    fun verifiedResultRequiresMatchingProjectionIdentity() = runBlocking {
        val auth = FakeAuthGateway()
        val api = FakeVerificationApiClient()
        val repository = DefaultVerificationRepository(auth, api)

        val result = repository.verify(REFRESH_ID)

        assertEquals("VERIFIED", result.result)
        assertEquals("LIVE", result.projection?.freshness)
        assertEquals(STATE_ID, result.projection?.stateId)
        assertEquals(1, api.calls)
    }

    @Test
    fun mismatchedProjectionFailsClosed() = runBlocking {
        val auth = FakeAuthGateway()
        val api = FakeVerificationApiClient(
            response = verifiedDto().copy(
                stateProjection = verifiedDto().stateProjection?.copy(
                    refreshId = OTHER_REFRESH_ID,
                ),
            ),
        )
        val repository = DefaultVerificationRepository(auth, api)

        val error = runCatching {
            repository.verify(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is VerificationFailure.Protocol)
    }

    @Test
    fun expiredAuthRefreshesOnceAndReplaysVerificationSafely() = runBlocking {
        val auth = FakeAuthGateway()
        val api = FakeVerificationApiClient(
            failFirstWithAuthExpired = true,
        )
        val repository = DefaultVerificationRepository(auth, api)

        val result = repository.verify(REFRESH_ID)

        assertEquals("VERIFIED", result.result)
        assertEquals(2, api.calls)
        assertEquals(1, auth.refreshCalls)
        assertEquals("token-refreshed", api.lastToken)
    }

    @Test
    fun replayedVerifiedResultPreservesAuthoritativeIdentity() = runBlocking {
        val api = FakeVerificationApiClient(
            response = verifiedDto().copy(replayed = true),
        )
        val repository = DefaultVerificationRepository(FakeAuthGateway(), api)

        val result = repository.verify(REFRESH_ID)

        assertTrue(result.replayed)
        assertEquals(VERIFICATION_ID, result.verificationResultId)
        assertEquals(STATE_ID, result.projection?.stateId)
    }

    @Test
    fun conflictResultCarriesNoProjection() = runBlocking {
        val response = verifiedDto().copy(
            result = "CONFLICT",
            status = "CONFLICT",
            reasonCodes = listOf("WITNESS_CONFLICT"),
            finalAnswer = null,
            nextStep = "ADDITIONAL_VERIFICATION",
            stateProjection = null,
        )
        val repository = DefaultVerificationRepository(
            FakeAuthGateway(),
            FakeVerificationApiClient(response = response),
        )

        val result = repository.verify(REFRESH_ID)

        assertEquals("CONFLICT", result.result)
        assertEquals(listOf("WITNESS_CONFLICT"), result.reasonCodes)
        assertEquals(null, result.projection)
    }

    @Test
    fun additionalVerificationResultCarriesNoProjection() = runBlocking {
        val response = verifiedDto().copy(
            result = "REQUIRES_ADDITIONAL_VERIFICATION",
            status = "WAITING_FOR_MORE_EVIDENCE",
            reasonCodes = listOf("INSUFFICIENT_WITNESSES"),
            finalAnswer = null,
            nextStep = "ADDITIONAL_VERIFICATION",
            stateProjection = null,
        )
        val repository = DefaultVerificationRepository(
            FakeAuthGateway(),
            FakeVerificationApiClient(response = response),
        )

        val result = repository.verify(REFRESH_ID)

        assertEquals("REQUIRES_ADDITIONAL_VERIFICATION", result.result)
        assertEquals("ADDITIONAL_VERIFICATION", result.nextStep)
        assertEquals(null, result.projection)
    }

    @Test
    fun resultStatusMismatchFailsClosed() = runBlocking {
        val repository = DefaultVerificationRepository(
            FakeAuthGateway(),
            FakeVerificationApiClient(
                response = verifiedDto().copy(status = "CONFLICT"),
            ),
        )

        val error = runCatching {
            repository.verify(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is VerificationFailure.Protocol)
    }

    @Test
    fun nonVerifiedResultCannotSmuggleStateProjection() = runBlocking {
        val repository = DefaultVerificationRepository(
            FakeAuthGateway(),
            FakeVerificationApiClient(
                response = verifiedDto().copy(
                    result = "REJECTED",
                    status = "REJECTED",
                    reasonCodes = listOf("POLICY_INTERNAL_ERROR"),
                    finalAnswer = null,
                    nextStep = "REVIEW",
                ),
            ),
        )

        val error = runCatching {
            repository.verify(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is VerificationFailure.Protocol)
    }

    @Test
    fun transportFailureRemainsExplicitlyRetryable() = runBlocking {
        val repository = DefaultVerificationRepository(
            FakeAuthGateway(),
            object : VerificationApiClient {
                override suspend fun verifyRefresh(
                    refreshId: String,
                    accessToken: String,
                ): VerificationResultDto {
                    throw ApiFailure.NetworkUnavailable(IOException("offline"))
                }
            },
        )

        val error = runCatching {
            repository.verify(REFRESH_ID)
        }.exceptionOrNull()

        assertTrue(error is VerificationFailure.Retryable)
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

    private class FakeVerificationApiClient(
        private val response: VerificationResultDto = verifiedDto(),
        private val failFirstWithAuthExpired: Boolean = false,
    ) : VerificationApiClient {
        var calls = 0
        var lastToken: String? = null

        override suspend fun verifyRefresh(
            refreshId: String,
            accessToken: String,
        ): VerificationResultDto {
            calls += 1
            lastToken = accessToken
            if (failFirstWithAuthExpired && calls == 1) {
                throw ApiFailure.AuthExpired("expired")
            }
            return response
        }
    }

    private companion object {
        const val REFRESH_ID = "22222222-2222-4222-8222-222222222222"
        const val OTHER_REFRESH_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val VERIFICATION_ID = "66666666-6666-4666-8666-666666666666"
        const val STATE_ID = "33333333-3333-4333-8333-333333333333"

        fun verifiedDto(): VerificationResultDto =
            VerificationResultDto(
                verificationResultId = VERIFICATION_ID,
                refreshId = REFRESH_ID,
                result = "VERIFIED",
                status = "VERIFIED",
                reasonCodes = emptyList(),
                evidenceIds = listOf(
                    "55555555-5555-4555-8555-555555555555",
                ),
                finalAnswer = JsonPrimitive(2),
                evidenceSetRevision = 1,
                policyVersion = 1,
                canonicalDigest =
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                completedAt = "2026-09-28T09:00:00Z",
                refreshStatus = "VERIFIED",
                refreshRevision = 10,
                replayed = false,
                nextStep = "PROJECTION",
                stateProjection = VerificationStateProjectionDto(
                    stateId = STATE_ID,
                    refreshId = REFRESH_ID,
                    verificationResultId = VERIFICATION_ID,
                    stateRevision = 8,
                    historyId = "77777777-7777-4777-8777-777777777777",
                    observedAt = "2026-09-28T09:00:00Z",
                    agingAt = "2026-09-28T09:07:00Z",
                    freshUntil = "2026-09-28T09:10:00Z",
                    currentValue = JsonPrimitive(2),
                    projected = true,
                    replayed = false,
                    superseded = false,
                    freshness = "LIVE",
                ),
            )
    }
}
