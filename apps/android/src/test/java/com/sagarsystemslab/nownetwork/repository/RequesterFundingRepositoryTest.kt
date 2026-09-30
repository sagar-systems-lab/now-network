package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AppSession
import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.config.RewardDisplayConfig
import com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationDao
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationEntity
import com.sagarsystemslab.nownetwork.data.local.WalletSessionMetadataDao
import com.sagarsystemslab.nownetwork.data.local.WalletSessionMetadataEntity
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.CreateRefreshRequest
import com.sagarsystemslab.nownetwork.network.FundingAccountsDto
import com.sagarsystemslab.nownetwork.network.FundingIntentDto
import com.sagarsystemslab.nownetwork.network.FundingObserveRequest
import com.sagarsystemslab.nownetwork.network.MeDto
import com.sagarsystemslab.nownetwork.network.NearbyOpportunitiesDto
import com.sagarsystemslab.nownetwork.network.NearbyOpportunityQuery
import com.sagarsystemslab.nownetwork.network.NearbyStateQuery
import com.sagarsystemslab.nownetwork.network.NearbyStatesDto
import com.sagarsystemslab.nownetwork.network.NowApiClient
import com.sagarsystemslab.nownetwork.network.RefreshDto
import com.sagarsystemslab.nownetwork.network.StateDetailDto
import com.sagarsystemslab.nownetwork.network.WalletBindingChallengeDto
import com.sagarsystemslab.nownetwork.network.WalletBindingChallengeRequest
import com.sagarsystemslab.nownetwork.network.WalletBindingVerifyDto
import com.sagarsystemslab.nownetwork.network.WalletBindingVerifyRequest
import com.sagarsystemslab.nownetwork.solana.FundingTransaction
import com.sagarsystemslab.nownetwork.solana.FundingTransactionMetadata
import com.sagarsystemslab.nownetwork.solana.LatestBlockhash
import com.sagarsystemslab.nownetwork.solana.SolanaRpcClient
import com.sagarsystemslab.nownetwork.solana.SolanaTransactionBuilder
import com.sagarsystemslab.nownetwork.wallet.WalletAccount
import com.sagarsystemslab.nownetwork.wallet.WalletGateway
import com.sagarsystemslab.nownetwork.wallet.WalletInteractionHost
import com.sagarsystemslab.nownetwork.wallet.WalletProof
import com.sagarsystemslab.nownetwork.wallet.WalletRequestCoordinator
import com.sagarsystemslab.nownetwork.wallet.WalletResult
import com.sagarsystemslab.nownetwork.wallet.WalletSubmission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequesterFundingRepositoryTest {
    @Test
    fun submittedWalletTransactionStaysConfirmingWhenBackendIsNotCertainYet() = runBlocking {
        val operationDao = FundingOperationDao()
        val api = FundingApi()
        val walletAddress = "wallet-a"
        val wallet = WalletRequestCoordinator(
            object : WalletGateway {
                override suspend fun connect(
                    host: WalletInteractionHost,
                ): WalletResult<WalletAccount> =
                    WalletResult.Success(WalletAccount(walletAddress, "Requester"))

                override suspend fun disconnect(
                    host: WalletInteractionHost,
                ): WalletResult<Unit> = WalletResult.Success(Unit)

                override suspend fun signWalletProof(
                    host: WalletInteractionHost,
                    message: String,
                ): WalletResult<WalletProof> =
                    error("not used")

                override suspend fun signAndSend(
                    host: WalletInteractionHost,
                    transaction: ByteArray,
                ): WalletResult<WalletSubmission> =
                    WalletResult.Success(
                        WalletSubmission(
                            account = WalletAccount(walletAddress, "Requester"),
                            signatureBase58 = "signature-a",
                        ),
                    )
            },
        )

        val config = SolanaRuntimeConfig(
            cluster = "devnet",
            rpcUrl = "https://api.devnet.solana.com",
            programId = "program-a",
            walletIdentityUri = "https://example.test",
            walletIconUri = "icon.png",
        )
        val repository = DefaultRequesterFundingRepository(
            auth = FundingAuth(),
            api = api,
            wallet = wallet,
            walletMetadataDao = FundingWalletMetadataDao(),
            operationDao = operationDao,
            rpc = FundingRpc(),
            transactionBuilder = SolanaTransactionBuilder(
                config = config,
                rewardConfig = RewardDisplayConfig(
                    mint = "mint-a",
                    symbol = "USDC",
                    decimals = 6,
                ),
            ),
            serverClock = ServerClock(),
            solanaConfig = config,
        )

        val prepared = prepared(walletAddress)
        operationDao.upsert(prepared.operation)

        val result = repository.submit(
            host = object : WalletInteractionHost {},
            prepared = prepared,
        )

        assertTrue(result is FundingReconciliation.Confirming)
        val persisted = requireNotNull(operationDao.get(prepared.operation.operationId))
        assertEquals("CONFIRMING", persisted.localState)
        assertEquals("signature-a", persisted.chainSignature)
        assertEquals(1, api.observeCalls)
    }

    @Test
    fun processRestartAfterSubmissionReconcilesPersistedSignatureWithoutWalletResend() = runBlocking {
        val operationDao = FundingOperationDao()
        val api = FundingApi()
        val walletAddress = "wallet-a"
        var walletSendCalls = 0
        val wallet = WalletRequestCoordinator(
            object : WalletGateway {
                override suspend fun connect(
                    host: WalletInteractionHost,
                ): WalletResult<WalletAccount> =
                    WalletResult.Success(WalletAccount(walletAddress, "Requester"))

                override suspend fun disconnect(
                    host: WalletInteractionHost,
                ): WalletResult<Unit> = WalletResult.Success(Unit)

                override suspend fun signWalletProof(
                    host: WalletInteractionHost,
                    message: String,
                ): WalletResult<WalletProof> =
                    error("not used")

                override suspend fun signAndSend(
                    host: WalletInteractionHost,
                    transaction: ByteArray,
                ): WalletResult<WalletSubmission> {
                    walletSendCalls += 1
                    return WalletResult.Success(
                        WalletSubmission(
                            account = WalletAccount(walletAddress, "Requester"),
                            signatureBase58 = "signature-a",
                        ),
                    )
                }
            },
        )

        val config = SolanaRuntimeConfig(
            cluster = "devnet",
            rpcUrl = "https://api.devnet.solana.com",
            programId = "program-a",
            walletIdentityUri = "https://example.test",
            walletIconUri = "icon.png",
        )

        fun repository() = DefaultRequesterFundingRepository(
            auth = FundingAuth(),
            api = api,
            wallet = wallet,
            walletMetadataDao = FundingWalletMetadataDao(),
            operationDao = operationDao,
            rpc = FundingRpc(),
            transactionBuilder = SolanaTransactionBuilder(
                config = config,
                rewardConfig = RewardDisplayConfig(
                    mint = "mint-a",
                    symbol = "USDC",
                    decimals = 6,
                ),
            ),
            serverClock = ServerClock(),
            solanaConfig = config,
        )

        val prepared = prepared(walletAddress)
        operationDao.upsert(prepared.operation)

        val beforeRestart = repository().submit(
            host = object : WalletInteractionHost {},
            prepared = prepared,
        )

        assertTrue(beforeRestart is FundingReconciliation.Confirming)
        assertEquals(1, walletSendCalls)
        assertEquals(1, api.observeCalls)

        val afterRestart = repository().recoverLatest()

        assertTrue(afterRestart is FundingReconciliation.Confirming)
        assertEquals(1, walletSendCalls)
        assertEquals(2, api.observeCalls)

        val persisted = requireNotNull(operationDao.get(prepared.operation.operationId))
        assertEquals("CONFIRMING", persisted.localState)
        assertEquals("signature-a", persisted.chainSignature)
    }

    private fun prepared(walletAddress: String): PreparedRequesterFunding {
        val operation = ActiveOperationEntity(
            operationId = "11111111-1111-4111-8111-111111111111",
            type = "REFRESH_FUNDING",
            entityId = "22222222-2222-4222-8222-222222222222",
            localState = "READY_FOR_WALLET",
            remoteState = "AWAITING_FUNDING",
            chainSignature = null,
            lastValidBlockHeight = 900L,
            idempotencyKey = "funding-intent:test-1234",
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
        )

        val refresh = RefreshDto(
            refreshId = operation.entityId,
            stateId = "33333333-3333-4333-8333-333333333333",
            stateVersion = 1,
            status = "AWAITING_FUNDING",
            verificationClass = "FAST",
            requiredWitnesses = 1,
            maxWitnesses = 1,
            payoutRule = "SINGLE_WINNER_ALL",
            proofPolicy = buildJsonObject {},
            proofPolicyDigest = "00",
            intentCoreHash = "00",
            refreshExpiresAt = "2035-01-01T00:10:00Z",
            evidenceDeadline = "2035-01-01T00:08:00Z",
            rewardMint = "mint-a",
            fundingTargetAtomic = "450000",
            fundingOperationId = operation.operationId,
            chainTotalFundedAtomic = "0",
            chainRefreshAddress = "refresh-chain-a",
            chainStatus = "AWAITING_FUNDING",
            chainObservedAt = null,
            revision = 2,
            nextStep = null,
        )

        val intent = FundingIntentDto(
            operationId = operation.operationId,
            refreshId = refresh.refreshId,
            status = "AWAITING_FUNDING",
            cluster = "devnet",
            programId = "program-a",
            rewardMint = "mint-a",
            amountAtomic = "450000",
            intentCoreHash = "00",
            chainRefreshIdHex = "00",
            stateIdDigestHex = "00",
            refreshExpiresAtUnix = 2_100_000_000L,
            verificationClass = "FAST",
            requiredWitnesses = 1,
            maxWitnesses = 1,
            payoutRule = "SINGLE_WINNER_ALL",
            creatorWallet = walletAddress,
            accounts = FundingAccountsDto(
                config = "config-a",
                refresh = "refresh-chain-a",
                contribution = "contribution-a",
                sourceTokenAccount = "source-a",
                vaultTokenAccount = "vault-a",
                tokenProgram = "token-a",
                associatedTokenProgram = "ata-a",
                systemProgram = "system-a",
            ),
            instructionPlan = listOf("create_refresh", "contribute"),
        )

        return PreparedRequesterFunding(
            operation = operation,
            wallet = WalletAccount(walletAddress, "Requester"),
            refresh = refresh,
            intent = intent,
            transaction = FundingTransaction(
                bytes = byteArrayOf(1, 2, 3),
                lastValidBlockHeight = 900L,
                metadata = FundingTransactionMetadata(
                    refreshId = refresh.refreshId,
                    refreshAddress = "refresh-chain-a",
                    programId = "program-a",
                    rewardMint = "mint-a",
                    amountAtomic = "450000",
                    instructionNames = listOf(
                        "create_vault_if_needed",
                        "create_refresh",
                        "contribute",
                    ),
                ),
            ),
        )
    }
}

private class FundingAuth : AuthGateway {
    override suspend fun ensureAnonymousSession(): AppSession =
        AppSession("actor-a", "token-a")

    override suspend fun currentSession(): AppSession? =
        AppSession("actor-a", "token-a")

    override suspend fun refreshIfNeeded(): AppSession =
        AppSession("actor-a", "token-b")

    override suspend fun signOutLocal() = Unit
}

private class FundingApi : NowApiClient {
    var observeCalls = 0

    override suspend fun observeFunding(
        refreshId: String,
        request: FundingObserveRequest,
        idempotencyKey: String,
        accessToken: String,
    ): RefreshDto {
        observeCalls += 1
        throw ApiFailure.BusinessError(
            statusCode = 409,
            code = "FUNDING_UNKNOWN",
            safeToRetry = true,
            retryAfterMs = 1_000L,
            message = "Funding is not yet confirmed.",
        )
    }

    override suspend fun nearbyStates(query: NearbyStateQuery): NearbyStatesDto = error("not used")
    override suspend fun stateDetail(stateId: String): StateDetailDto = error("not used")
    override suspend fun nearbyOpportunities(
        query: NearbyOpportunityQuery,
        accessToken: String,
    ): NearbyOpportunitiesDto = error("not used")
    override suspend fun me(accessToken: String): MeDto = error("not used")
    override suspend fun walletBindingChallenge(
        request: WalletBindingChallengeRequest,
        accessToken: String,
    ): WalletBindingChallengeDto = error("not used")
    override suspend fun verifyWalletBinding(
        request: WalletBindingVerifyRequest,
        accessToken: String,
    ): WalletBindingVerifyDto = error("not used")
    override suspend fun createRefresh(
        request: CreateRefreshRequest,
        idempotencyKey: String,
        accessToken: String,
    ): RefreshDto = error("not used")
    override suspend fun refreshDetail(
        refreshId: String,
        accessToken: String,
    ): RefreshDto = error("not used")
    override suspend fun fundingIntent(
        refreshId: String,
        idempotencyKey: String,
        accessToken: String,
    ): FundingIntentDto = error("not used")

    override suspend fun opportunityDetail(
        refreshId: String,
        accessToken: String,
    ): com.sagarsystemslab.nownetwork.network.OpportunityDto =
        error("not used")

    override suspend fun prepareClaim(
        refreshId: String,
        request: com.sagarsystemslab.nownetwork.network.ClaimPrepareRequest,
        idempotencyKey: String,
        accessToken: String,
    ): com.sagarsystemslab.nownetwork.network.ClaimIntentDto =
        error("not used")

    override suspend fun claimDetail(
        acceptanceId: String,
        accessToken: String,
    ): com.sagarsystemslab.nownetwork.network.ClaimStatusDto =
        error("not used")

    override suspend fun observeClaim(
        acceptanceId: String,
        request: com.sagarsystemslab.nownetwork.network.ClaimObserveRequest,
        accessToken: String,
    ): com.sagarsystemslab.nownetwork.network.ClaimStatusDto =
        error("not used")

}

private class FundingOperationDao : ActiveOperationDao {
    private val rows = MutableStateFlow<List<ActiveOperationEntity>>(emptyList())

    override suspend fun get(operationId: String): ActiveOperationEntity? =
        rows.value.firstOrNull { it.operationId == operationId }

    override suspend fun getByIdempotencyKey(
        idempotencyKey: String,
    ): ActiveOperationEntity? =
        rows.value.firstOrNull { it.idempotencyKey == idempotencyKey }

    override suspend fun listAll(): List<ActiveOperationEntity> = rows.value
    override fun observeAll(): Flow<List<ActiveOperationEntity>> = rows

    override suspend fun upsert(entity: ActiveOperationEntity) {
        rows.value = rows.value.filterNot { it.operationId == entity.operationId } + entity
    }

    override suspend fun delete(operationId: String) {
        rows.value = rows.value.filterNot { it.operationId == operationId }
    }
}

private class FundingWalletMetadataDao : WalletSessionMetadataDao {
    override suspend fun get(profileKey: String): WalletSessionMetadataEntity? = null
    override suspend fun upsert(entity: WalletSessionMetadataEntity) = Unit
    override suspend fun delete(profileKey: String) = Unit
}

private class FundingRpc : SolanaRpcClient {
    override suspend fun latestBlockhash(): LatestBlockhash = error("not used")
    override suspend fun recentSignatures(address: String, limit: Int): List<String> = emptyList()
}
