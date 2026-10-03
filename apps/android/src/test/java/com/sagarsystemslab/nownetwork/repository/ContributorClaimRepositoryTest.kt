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
import com.sagarsystemslab.nownetwork.network.ClaimAccountsDto
import com.sagarsystemslab.nownetwork.network.ClaimInstructionDto
import com.sagarsystemslab.nownetwork.network.ClaimIntentDto
import com.sagarsystemslab.nownetwork.network.ClaimObserveRequest
import com.sagarsystemslab.nownetwork.network.ClaimPrepareRequest
import com.sagarsystemslab.nownetwork.network.ClaimStatusDto
import com.sagarsystemslab.nownetwork.network.CreateRefreshRequest
import com.sagarsystemslab.nownetwork.network.FundingIntentDto
import com.sagarsystemslab.nownetwork.network.FundingObserveRequest
import com.sagarsystemslab.nownetwork.network.MeDto
import com.sagarsystemslab.nownetwork.network.NearbyOpportunitiesDto
import com.sagarsystemslab.nownetwork.network.NearbyOpportunityQuery
import com.sagarsystemslab.nownetwork.network.NearbyStateQuery
import com.sagarsystemslab.nownetwork.network.NearbyStatesDto
import com.sagarsystemslab.nownetwork.network.NowApiClient
import com.sagarsystemslab.nownetwork.network.OpportunityDto
import com.sagarsystemslab.nownetwork.network.RefreshDto
import com.sagarsystemslab.nownetwork.network.StateDetailDto
import com.sagarsystemslab.nownetwork.network.WalletBindingChallengeDto
import com.sagarsystemslab.nownetwork.network.WalletBindingChallengeRequest
import com.sagarsystemslab.nownetwork.network.WalletBindingDto
import com.sagarsystemslab.nownetwork.network.WalletBindingVerifyDto
import com.sagarsystemslab.nownetwork.network.WalletBindingVerifyRequest
import com.sagarsystemslab.nownetwork.solana.LatestBlockhash
import com.sagarsystemslab.nownetwork.solana.SolanaPrograms
import com.sagarsystemslab.nownetwork.solana.SolanaPublicKey
import com.sagarsystemslab.nownetwork.solana.SolanaRpcClient
import com.sagarsystemslab.nownetwork.solana.SolanaTransactionBuilder
import com.sagarsystemslab.nownetwork.solana.findProgramAddress
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributorClaimRepositoryTest {
    @Test
    fun submittedSignatureStaysDurableWhileClaimConfirmationIsUnknown() = runBlocking {
        val fixture = fixture(
            walletSubmission = WalletResult.Success(
                WalletSubmission(
                    account = WalletAccount(WALLET_ADDRESS, "Contributor"),
                    signatureBase58 = "signature-a",
                ),
            ),
            observeUnknown = true,
        )

        val prepared = fixture.repository.prepareNew(HOST, REFRESH_ID)
        val result = fixture.repository.submit(HOST, prepared)

        assertTrue(result is ClaimReconciliation.Confirming)
        val persisted = requireNotNull(
            fixture.operationDao.get(prepared.operation.operationId),
        )
        assertEquals("CONFIRMING", persisted.localState)
        assertEquals("signature-a", persisted.chainSignature)
        assertEquals(1, fixture.api.observeCalls)
        assertEquals(1, fixture.wallet.signCalls)
    }

    @Test
    fun ambiguousWalletCallbackNeverResendsDuringRecovery() = runBlocking {
        val fixture = fixture(
            walletSubmission = WalletResult.UnknownFailure("wallet callback lost"),
            observeUnknown = false,
        )

        val prepared = fixture.repository.prepareNew(HOST, REFRESH_ID)
        val first = fixture.repository.submit(HOST, prepared)
        val recovered = fixture.repository.recover(REFRESH_ID)

        assertTrue(first is ClaimReconciliation.Confirming)
        assertTrue(recovered is ClaimReconciliation.Confirming)
        assertEquals(1, fixture.wallet.signCalls)
        assertTrue(fixture.api.detailCalls >= 2)
    }

    @Test
    fun differentDefaultPayoutWalletStopsBeforePreparingOrSigningAClaim() = runBlocking {
        val fixture = fixture(WalletResult.UnknownFailure("unused"), observeUnknown = false)
        fixture.api.preferredWallet = "55555555-5555-4555-8555-555555555555"
        val failure = runCatching { fixture.repository.prepareNew(HOST, REFRESH_ID) }.exceptionOrNull()
        assertTrue(failure != null)
        assertEquals(0, fixture.api.prepareCalls)
        assertEquals(0, fixture.wallet.signCalls)
    }

    private fun fixture(
        walletSubmission: WalletResult<WalletSubmission>,
        observeUnknown: Boolean,
    ): Fixture {
        val program = SolanaPublicKey.fromBytes(ByteArray(32) { 1 })
        val mint = SolanaPublicKey.fromBytes(ByteArray(32) { 2 })
        val walletKey = SolanaPublicKey.parse(WALLET_ADDRESS)
        val chainRefreshId = ByteArray(32) { 4 }

        val configAddress = findProgramAddress(
            seeds = listOf("config".encodeToByteArray()),
            programId = program,
        )
        val refreshAddress = findProgramAddress(
            seeds = listOf("refresh".encodeToByteArray(), chainRefreshId),
            programId = program,
        )
        val rewardAccount = findProgramAddress(
            seeds = listOf(
                walletKey.bytes,
                SolanaPrograms.token.bytes,
                mint.bytes,
            ),
            programId = SolanaPrograms.associatedToken,
        )
        val refreshHex = chainRefreshId.joinToString("") { "%02x".format(it) }

        val intent = ClaimIntentDto(
            acceptanceId = ACCEPTANCE_ID,
            refreshId = REFRESH_ID,
            status = "WALLET_PENDING",
            claimDurationSeconds = 300L,
            refreshStatus = "AVAILABLE",
            refreshExpiresAt = "2035-01-01T00:10:00Z",
            evidenceDeadline = "2035-01-01T00:08:00Z",
            revision = 1,
            nextStep = "SIGN_OR_OBSERVE_CLAIM",
            cluster = "devnet",
            programId = program.address,
            walletAddress = WALLET_ADDRESS,
            rewardMint = mint.address,
            chainRefreshIdHex = refreshHex,
            accounts = ClaimAccountsDto(
                claimant = WALLET_ADDRESS,
                config = configAddress.address,
                refresh = refreshAddress.address,
                rewardMint = mint.address,
                claimantRewardTokenAccount = rewardAccount.address,
            ),
            instruction = ClaimInstructionDto(
                name = "claim_witness",
                refreshIdHex = refreshHex,
                claimDurationSeconds = 300L,
            ),
        )

        val api = ClaimApi(
            intent = intent,
            observeUnknown = observeUnknown,
        )
        val operationDao = ClaimOperationDao()
        val metadataDao = ClaimWalletMetadataDao()
        val walletGateway = ClaimWalletGateway(WALLET_ADDRESS, walletSubmission)
        val config = SolanaRuntimeConfig(
            cluster = "devnet",
            rpcUrl = "https://api.devnet.solana.com",
            programId = program.address,
            walletIdentityUri = "https://example.test",
            walletIconUri = "icon.png",
        )

        val repository = DefaultContributorClaimRepository(
            auth = ClaimAuth(),
            api = api,
            wallet = WalletRequestCoordinator(walletGateway),
            walletMetadataDao = metadataDao,
            operationDao = operationDao,
            rpc = ClaimRpc(),
            transactionBuilder = SolanaTransactionBuilder(
                config = config,
                rewardConfig = RewardDisplayConfig(
                    mint = mint.address,
                    symbol = "USDC",
                    decimals = 6,
                ),
            ),
            serverClock = ServerClock(),
            solanaConfig = config,
        )

        return Fixture(
            repository = repository,
            api = api,
            operationDao = operationDao,
            wallet = walletGateway,
        )
    }

    private data class Fixture(
        val repository: DefaultContributorClaimRepository,
        val api: ClaimApi,
        val operationDao: ClaimOperationDao,
        val wallet: ClaimWalletGateway,
    )

    private companion object {
        val HOST = object : WalletInteractionHost {}
        const val REFRESH_ID = "22222222-2222-4222-8222-222222222222"
        const val ACCEPTANCE_ID = "11111111-1111-4111-8111-111111111111"
        val WALLET_ADDRESS = SolanaPublicKey.fromBytes(ByteArray(32) { 3 }).address
    }
}

private class ClaimAuth : AuthGateway {
    override suspend fun ensureAnonymousSession(): AppSession =
        AppSession("actor-a", "token-a")

    override suspend fun currentSession(): AppSession? =
        AppSession("actor-a", "token-a")

    override suspend fun refreshIfNeeded(): AppSession =
        AppSession("actor-a", "token-b")

    override suspend fun signOutLocal() = Unit
}

private class ClaimApi(
    private val intent: ClaimIntentDto,
    private val observeUnknown: Boolean,
) : NowApiClient {
    var observeCalls = 0
    var detailCalls = 0
    var prepareCalls = 0
    var preferredWallet: String? = null

    override suspend fun payoutWalletBindingId(accessToken: String): String? = preferredWallet

    override suspend fun me(accessToken: String): MeDto =
        MeDto(
            actorId = "actor-a",
            status = "ACTIVE",
            walletBindings = listOf(
                WalletBindingDto(
                    walletBindingId = "44444444-4444-4444-8444-444444444444",
                    walletAddress = intent.walletAddress,
                    cluster = intent.cluster,
                    status = "ACTIVE",
                    revision = 1,
                ),
            ),
        )

    override suspend fun prepareClaim(
        refreshId: String,
        request: ClaimPrepareRequest,
        idempotencyKey: String,
        accessToken: String,
    ): ClaimIntentDto { prepareCalls += 1; return intent }

    override suspend fun claimDetail(
        acceptanceId: String,
        accessToken: String,
    ): ClaimStatusDto {
        detailCalls += 1
        return claimStatus(status = "WALLET_PENDING")
    }

    override suspend fun observeClaim(
        acceptanceId: String,
        request: ClaimObserveRequest,
        accessToken: String,
    ): ClaimStatusDto {
        observeCalls += 1
        if (observeUnknown) {
            throw ApiFailure.BusinessError(
                statusCode = 409,
                code = "CLAIM_UNKNOWN",
                safeToRetry = true,
                retryAfterMs = 1_000L,
                message = "Claim is not yet confirmed.",
            )
        }
        return claimStatus(status = "CLAIMED", signature = request.signature)
    }

    private fun claimStatus(
        status: String,
        signature: String? = null,
    ): ClaimStatusDto =
        ClaimStatusDto(
            acceptanceId = intent.acceptanceId,
            refreshId = intent.refreshId,
            status = status,
            claimSlot = if (status == "CLAIMED") 0 else null,
            claimDurationSeconds = intent.claimDurationSeconds,
            claimDeadline = if (status == "CLAIMED") "2035-01-01T00:05:00Z" else null,
            chainSignature = signature,
            chainStatus = if (status == "CLAIMED") "confirmed" else null,
            refreshStatus = intent.refreshStatus,
            refreshExpiresAt = intent.refreshExpiresAt,
            evidenceDeadline = intent.evidenceDeadline,
            revision = 1,
            nextStep = if (status == "CLAIMED") "EVIDENCE_CHALLENGE" else "SIGN_OR_OBSERVE_CLAIM",
        )

    override suspend fun nearbyStates(query: NearbyStateQuery): NearbyStatesDto = error("not used")
    override suspend fun stateDetail(stateId: String): StateDetailDto = error("not used")
    override suspend fun nearbyOpportunities(
        query: NearbyOpportunityQuery,
        accessToken: String,
    ): NearbyOpportunitiesDto = error("not used")
    override suspend fun opportunityDetail(
        refreshId: String,
        accessToken: String,
    ): OpportunityDto = error("not used")
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
    override suspend fun observeFunding(
        refreshId: String,
        request: FundingObserveRequest,
        idempotencyKey: String,
        accessToken: String,
    ): RefreshDto = error("not used")
}

private class ClaimOperationDao : ActiveOperationDao {
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

private class ClaimWalletMetadataDao : WalletSessionMetadataDao {
    private var row: WalletSessionMetadataEntity? = null

    override suspend fun get(profileKey: String): WalletSessionMetadataEntity? = row

    override suspend fun upsert(entity: WalletSessionMetadataEntity) {
        row = entity
    }

    override suspend fun delete(profileKey: String) {
        row = null
    }
}

private class ClaimRpc : SolanaRpcClient {
    override suspend fun latestBlockhash(): LatestBlockhash =
        LatestBlockhash(
            blockhash = SolanaPublicKey.fromBytes(ByteArray(32) { 9 }).address,
            lastValidBlockHeight = 1_200L,
        )

    override suspend fun recentSignatures(address: String, limit: Int): List<String> =
        emptyList()
}

private class ClaimWalletGateway(
    private val walletAddress: String,
    private val submission: WalletResult<WalletSubmission>,
) : WalletGateway {
    var signCalls = 0

    override suspend fun connect(host: WalletInteractionHost): WalletResult<WalletAccount> =
        WalletResult.Success(
            WalletAccount(
                address = walletAddress,
                label = "Contributor",
            ),
        )

    override suspend fun disconnect(host: WalletInteractionHost): WalletResult<Unit> =
        WalletResult.Success(Unit)

    override suspend fun signWalletProof(
        host: WalletInteractionHost,
        message: String,
    ): WalletResult<WalletProof> = error("not used")

    override suspend fun signAndSend(
        host: WalletInteractionHost,
        transaction: ByteArray,
    ): WalletResult<WalletSubmission> {
        signCalls += 1
        return submission
    }
}
