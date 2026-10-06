package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationDao
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationEntity
import com.sagarsystemslab.nownetwork.data.local.WalletSessionMetadataDao
import com.sagarsystemslab.nownetwork.data.local.WalletSessionMetadataEntity
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.ClaimIntentDto
import com.sagarsystemslab.nownetwork.network.ClaimObserveRequest
import com.sagarsystemslab.nownetwork.network.ClaimPrepareRequest
import com.sagarsystemslab.nownetwork.network.ClaimStatusDto
import com.sagarsystemslab.nownetwork.network.NowApiClient
import com.sagarsystemslab.nownetwork.network.OpportunityDto
import com.sagarsystemslab.nownetwork.network.WalletBindingChallengeRequest
import com.sagarsystemslab.nownetwork.network.WalletBindingDto
import com.sagarsystemslab.nownetwork.network.WalletBindingVerifyRequest
import com.sagarsystemslab.nownetwork.solana.ClaimTransaction
import com.sagarsystemslab.nownetwork.solana.SolanaRpcClient
import com.sagarsystemslab.nownetwork.solana.SolanaTransactionBuilder
import com.sagarsystemslab.nownetwork.wallet.WalletAccount
import com.sagarsystemslab.nownetwork.wallet.WalletInteractionHost
import com.sagarsystemslab.nownetwork.wallet.WalletRequestCoordinator
import com.sagarsystemslab.nownetwork.wallet.WalletResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

data class PreparedContributorClaim(
    val operation: ActiveOperationEntity,
    val wallet: WalletAccount,
    val intent: ClaimIntentDto,
    val transaction: ClaimTransaction,
)

sealed interface ClaimReconciliation {
    data class Claimed(
        val claim: ClaimStatusDto,
        val operation: ActiveOperationEntity,
    ) : ClaimReconciliation

    data class EvidenceCommitted(val claim: ClaimStatusDto) : ClaimReconciliation

    data class Confirming(
        val operation: ActiveOperationEntity,
    ) : ClaimReconciliation

    data class ReadyForWallet(
        val prepared: PreparedContributorClaim,
    ) : ClaimReconciliation

    data object None : ClaimReconciliation
}

sealed class ContributorClaimFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class WalletUnavailable : ContributorClaimFailure("No compatible Solana wallet was found.")
    class WalletRejected : ContributorClaimFailure("Wallet request was cancelled.")
    class WalletBusy : ContributorClaimFailure("Another wallet request is already active.")
    class Wallet(message: String) : ContributorClaimFailure(message)
    class WalletMismatch : ContributorClaimFailure("The connected wallet changed during claim.")
    class Unavailable(message: String) : ContributorClaimFailure(message)
    class Expired : ContributorClaimFailure("This earning opportunity has expired.")
    class Rejected(message: String) : ContributorClaimFailure(message)
    class Configuration(message: String) : ContributorClaimFailure(message)
    class Network(cause: Throwable) :
        ContributorClaimFailure("Network is unavailable. Your claim state is saved.", cause)
    class Server(message: String) : ContributorClaimFailure(message)
    class Protocol(message: String, cause: Throwable? = null) :
        ContributorClaimFailure(message, cause)
}

interface ContributorClaimRepository {
    suspend fun loadOpportunity(refreshId: String): OpportunityDto

    suspend fun prepareNew(
        host: WalletInteractionHost,
        refreshId: String,
    ): PreparedContributorClaim

    suspend fun submit(
        host: WalletInteractionHost,
        prepared: PreparedContributorClaim,
    ): ClaimReconciliation

    suspend fun recover(refreshId: String): ClaimReconciliation
}

@Singleton
class DefaultContributorClaimRepository @Inject constructor(
    private val auth: AuthGateway,
    private val api: NowApiClient,
    private val wallet: WalletRequestCoordinator,
    private val walletMetadataDao: WalletSessionMetadataDao,
    private val operationDao: ActiveOperationDao,
    private val rpc: SolanaRpcClient,
    private val transactionBuilder: SolanaTransactionBuilder,
    private val serverClock: ServerClock,
    private val solanaConfig: SolanaRuntimeConfig,
) : ContributorClaimRepository {
    override suspend fun loadOpportunity(refreshId: String): OpportunityDto =
        withAuthRetry { token ->
            api.opportunityDetail(refreshId, token)
        }

    override suspend fun prepareNew(
        host: WalletInteractionHost,
        refreshId: String,
    ): PreparedContributorClaim {
        val account = wallet.connect(host).requireWalletValue()
        val binding = ensureBinding(host, account)
        val preferred = withAuthRetry { api.payoutWalletBindingId(it) }
        if (preferred != null && preferred != binding.walletBindingId) {
            throw ContributorClaimFailure.Protocol("Choose your default payout wallet in the wallet app, or update Payout preferences before claiming. Existing claims keep their original wallet.")
        }
        val idempotencyKey = claimPrepareKey(binding.walletBindingId)
        val now = serverClock.nowMillis()
        val provisional = ActiveOperationEntity(
            operationId = UUID.randomUUID().toString(),
            type = OPERATION_TYPE,
            entityId = refreshId,
            localState = STATE_PREPARING_REMOTE,
            remoteState = null,
            chainSignature = null,
            lastValidBlockHeight = null,
            idempotencyKey = idempotencyKey,
            createdAtMs = now,
            updatedAtMs = now,
        )
        operationDao.upsert(provisional)

        val intent = withAuthRetry { token ->
            api.prepareClaim(
                refreshId = refreshId,
                request = ClaimPrepareRequest(
                    walletBindingId = binding.walletBindingId,
                ),
                idempotencyKey = idempotencyKey,
                accessToken = token,
            )
        }

        if (intent.refreshId != refreshId) {
            throw ContributorClaimFailure.Protocol("Claim response refresh identity mismatch.")
        }

        var operation = migratePreparedOperation(
            operation = provisional,
            intent = intent,
        )
        if (intent.status == "CLAIMED") {
            operation = operation.copy(
                localState = STATE_ACKNOWLEDGED,
                updatedAtMs = serverClock.nowMillis(),
            )
            operationDao.upsert(operation)
            throw ContributorClaimFailure.Protocol(
                "Claim is already confirmed; reconcile before preparing another transaction.",
            )
        }

        val latest = rpc.latestBlockhash()
        val transaction = transactionBuilder.buildClaim(
            intent = intent,
            walletAddress = account.address,
            latestBlockhash = latest,
        )

        operation = operation.copy(
            localState = STATE_READY_FOR_WALLET,
            lastValidBlockHeight = latest.lastValidBlockHeight,
            updatedAtMs = serverClock.nowMillis(),
        )
        operationDao.upsert(operation)

        return PreparedContributorClaim(
            operation = operation,
            wallet = account,
            intent = intent,
            transaction = transaction,
        )
    }

    override suspend fun submit(
        host: WalletInteractionHost,
        prepared: PreparedContributorClaim,
    ): ClaimReconciliation {
        val walletPending = prepared.operation.copy(
            localState = STATE_WALLET_PENDING,
            updatedAtMs = serverClock.nowMillis(),
        )
        operationDao.upsert(walletPending)

        return when (val result = wallet.signAndSend(host, prepared.transaction.bytes)) {
            is WalletResult.Success -> {
                if (result.value.account.address != prepared.wallet.address) {
                    val uncertain = walletPending.copy(
                        localState = STATE_RECONCILING,
                        updatedAtMs = serverClock.nowMillis(),
                    )
                    operationDao.upsert(uncertain)
                    throw ContributorClaimFailure.WalletMismatch()
                }

                val submitted = walletPending.copy(
                    localState = STATE_SUBMITTED,
                    chainSignature = result.value.signatureBase58,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(submitted)

                try {
                    observeSignature(submitted, result.value.signatureBase58)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ContributorClaimFailure) {
                    if (error is ContributorClaimFailure.Rejected) throw error
                    val confirming = submitted.copy(
                        localState = STATE_CONFIRMING,
                        updatedAtMs = serverClock.nowMillis(),
                    )
                    operationDao.upsert(confirming)
                    ClaimReconciliation.Confirming(confirming)
                }
            }

            WalletResult.UserRejected -> {
                val ready = walletPending.copy(
                    localState = STATE_READY_FOR_WALLET,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(ready)
                throw ContributorClaimFailure.WalletRejected()
            }

            WalletResult.NoWalletFound -> {
                val ready = walletPending.copy(
                    localState = STATE_READY_FOR_WALLET,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(ready)
                throw ContributorClaimFailure.WalletUnavailable()
            }

            WalletResult.Busy -> {
                val ready = walletPending.copy(
                    localState = STATE_READY_FOR_WALLET,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(ready)
                throw ContributorClaimFailure.WalletBusy()
            }

            is WalletResult.ProtocolFailure -> {
                val ready = walletPending.copy(
                    localState = STATE_READY_FOR_WALLET,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(ready)
                throw ContributorClaimFailure.Wallet(result.reason)
            }

            is WalletResult.AssociationFailure,
            is WalletResult.UnknownFailure -> {
                val reconciling = walletPending.copy(
                    localState = STATE_RECONCILING,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(reconciling)
                reconcileWithoutResend(reconciling)
            }
        }
    }

    override suspend fun recover(refreshId: String): ClaimReconciliation {
        val operation = operationDao.listAll()
            .asSequence()
            .filter { it.type == OPERATION_TYPE && it.entityId == refreshId }
            .maxByOrNull { it.updatedAtMs }
            ?: return ClaimReconciliation.None

        if (operation.localState == STATE_ACKNOWLEDGED) {
            return claimDetailResult(operation)
        }

        operation.chainSignature?.let { signature ->
            return try {
                observeSignature(operation, signature)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ContributorClaimFailure.Rejected) {
                throw error
            } catch (error: Exception) {
                val confirming = operation.copy(
                    localState = STATE_CONFIRMING,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(confirming)
                ClaimReconciliation.Confirming(confirming)
            }
        }

        return when (operation.localState) {
            STATE_PREPARING_REMOTE,
            STATE_PREPARING_TRANSACTION,
            STATE_READY_FOR_WALLET -> {
                val prepared = prepareExisting(operation)
                if (prepared != null) {
                    ClaimReconciliation.ReadyForWallet(prepared)
                } else {
                    val migrated = operationDao.getByIdempotencyKey(operation.idempotencyKey)
                        ?: return ClaimReconciliation.None
                    claimDetailResult(migrated)
                }
            }

            STATE_WALLET_PENDING,
            STATE_RECONCILING,
            STATE_CONFIRMING,
            STATE_SUBMITTED -> reconcileWithoutResend(operation)

            else -> ClaimReconciliation.None
        }
    }

    private suspend fun prepareExisting(
        operation: ActiveOperationEntity,
    ): PreparedContributorClaim? {
        val account = activeBoundWallet() ?: return null
        val expectedBindingId = bindingIdFromPrepareKey(operation.idempotencyKey)
        val binding = activeBinding(
            walletAddress = account.address,
            expectedBindingId = expectedBindingId,
        ) ?: return null

        val intent = withAuthRetry { token ->
            api.prepareClaim(
                refreshId = operation.entityId,
                request = ClaimPrepareRequest(binding.walletBindingId),
                idempotencyKey = operation.idempotencyKey,
                accessToken = token,
            )
        }
        val migrated = migratePreparedOperation(
            operation = operation,
            intent = intent,
        )
        if (intent.status == "CLAIMED") {
            operationDao.upsert(
                migrated.copy(
                    localState = STATE_ACKNOWLEDGED,
                    updatedAtMs = serverClock.nowMillis(),
                ),
            )
            return null
        }

        val latest = rpc.latestBlockhash()
        val transaction = transactionBuilder.buildClaim(
            intent = intent,
            walletAddress = account.address,
            latestBlockhash = latest,
        )
        val ready = migrated.copy(
            localState = STATE_READY_FOR_WALLET,
            remoteState = intent.status,
            lastValidBlockHeight = latest.lastValidBlockHeight,
            updatedAtMs = serverClock.nowMillis(),
        )
        operationDao.upsert(ready)

        return PreparedContributorClaim(
            operation = ready,
            wallet = account,
            intent = intent,
            transaction = transaction,
        )
    }

    private suspend fun observeSignature(
        operation: ActiveOperationEntity,
        signature: String,
    ): ClaimReconciliation {
        return try {
            val claim = withAuthRetryApi { token ->
                api.observeClaim(
                    acceptanceId = operation.operationId,
                    request = ClaimObserveRequest(signature),
                    accessToken = token,
                )
            }

            resolvedClaim(claim, operation)?.let { return it }
            if (claim.status != "CLAIMED") {
                val confirming = operation.copy(
                    localState = STATE_CONFIRMING,
                    remoteState = claim.status,
                    chainSignature = signature,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(confirming)
                return ClaimReconciliation.Confirming(confirming)
            }

            val completed = operation.copy(
                localState = STATE_ACKNOWLEDGED,
                remoteState = claim.status,
                chainSignature = signature,
                updatedAtMs = serverClock.nowMillis(),
            )
            operationDao.upsert(completed)
            ClaimReconciliation.Claimed(claim, completed)
        } catch (error: ApiFailure.BusinessError) {
            when {
                error.code == "CLAIM_UNKNOWN" && error.safeToRetry -> {
                    val confirming = operation.copy(
                        localState = STATE_CONFIRMING,
                        chainSignature = signature,
                        updatedAtMs = serverClock.nowMillis(),
                    )
                    operationDao.upsert(confirming)
                    ClaimReconciliation.Confirming(confirming)
                }

                error.code == "CLAIM_REJECTED" -> {
                    val rejected = operation.copy(
                        localState = STATE_REJECTED,
                        chainSignature = signature,
                        updatedAtMs = serverClock.nowMillis(),
                    )
                    operationDao.upsert(rejected)
                    throw ContributorClaimFailure.Rejected(
                        error.message ?: "Claim transaction was rejected.",
                    )
                }

                else -> throw error.toClaimFailure()
            }
        } catch (error: ApiFailure) {
            throw error.toClaimFailure()
        }
    }

    private suspend fun reconcileWithoutResend(
        operation: ActiveOperationEntity,
    ): ClaimReconciliation {
        val claim = try {
            withAuthRetry { token ->
                api.claimDetail(operation.operationId, token)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            val confirming = operation.copy(
                localState = STATE_CONFIRMING,
                updatedAtMs = serverClock.nowMillis(),
            )
            operationDao.upsert(confirming)
            return ClaimReconciliation.Confirming(confirming)
        }

        resolvedClaim(claim, operation)?.let { return it }
        if (claim.status == "CLAIMED") {
            val completed = operation.copy(
                localState = STATE_ACKNOWLEDGED,
                remoteState = claim.status,
                chainSignature = claim.chainSignature ?: operation.chainSignature,
                updatedAtMs = serverClock.nowMillis(),
            )
            operationDao.upsert(completed)
            return ClaimReconciliation.Claimed(claim, completed)
        }

        claim.chainSignature?.let { signature ->
            return observeSignature(
                operation.copy(chainSignature = signature),
                signature,
            )
        }

        val confirming = operation.copy(
            localState = STATE_CONFIRMING,
            remoteState = claim.status,
            updatedAtMs = serverClock.nowMillis(),
        )
        operationDao.upsert(confirming)
        return ClaimReconciliation.Confirming(confirming)
    }

    private suspend fun claimDetailResult(
        operation: ActiveOperationEntity,
    ): ClaimReconciliation {
        val claim = withAuthRetry { token ->
            api.claimDetail(operation.operationId, token)
        }
        resolvedClaim(claim, operation)?.let { return it }

        val confirming = operation.copy(
            localState = STATE_CONFIRMING,
            remoteState = claim.status,
            updatedAtMs = serverClock.nowMillis(),
        )
        operationDao.upsert(confirming)
        return ClaimReconciliation.Confirming(confirming)
    }

    private suspend fun resolvedClaim(claim: ClaimStatusDto, operation: ActiveOperationEntity): ClaimReconciliation? {
        if (claim.refreshId != operation.entityId || claim.acceptanceId != operation.operationId) {
            throw ContributorClaimFailure.Protocol("Recovered claim identity mismatch.")
        }
        if (claim.status in setOf("CLAIMED", "CAPTURE_ACTIVE", "EVIDENCE_COMMITTED")) {
            val acknowledged = operation.copy(localState = STATE_ACKNOWLEDGED, remoteState = claim.status,
                chainSignature = claim.chainSignature ?: operation.chainSignature, updatedAtMs = serverClock.nowMillis())
            operationDao.upsert(acknowledged)
            return if (claim.status == "EVIDENCE_COMMITTED") ClaimReconciliation.EvidenceCommitted(claim)
                else ClaimReconciliation.Claimed(claim, acknowledged)
        }
        if (claim.status in setOf("RELEASED", "EXPIRED", "FAILED")) {
            operationDao.upsert(operation.copy(localState = STATE_REJECTED, remoteState = claim.status,
                updatedAtMs = serverClock.nowMillis()))
            throw ContributorClaimFailure.Rejected("This saved claim is ${claim.status.lowercase()}. Return to EARN for current opportunities.")
        }
        return null
    }

    private suspend fun ensureBinding(
        host: WalletInteractionHost,
        account: WalletAccount,
    ): WalletBindingDto {
        activeBinding(account.address)?.let { binding ->
            persistWalletMetadata(account.address)
            return binding
        }

        val challenge = withAuthRetry { token ->
            api.walletBindingChallenge(
                request = WalletBindingChallengeRequest(
                    walletAddress = account.address,
                    cluster = solanaConfig.cluster,
                ),
                accessToken = token,
            )
        }

        val proof = wallet.signWalletProof(host, challenge.message).requireWalletValue()
        if (proof.account.address != account.address) {
            throw ContributorClaimFailure.WalletMismatch()
        }

        val verified = withAuthRetry { token ->
            api.verifyWalletBinding(
                request = WalletBindingVerifyRequest(
                    challengeId = challenge.challengeId,
                    signature = proof.signatureBase64,
                ),
                accessToken = token,
            )
        }

        if (
            verified.walletBinding.walletAddress != account.address ||
            verified.walletBinding.cluster != solanaConfig.cluster ||
            verified.walletBinding.status != "ACTIVE"
        ) {
            throw ContributorClaimFailure.Protocol(
                "Wallet binding response did not match request.",
            )
        }

        persistWalletMetadata(account.address)
        return verified.walletBinding
    }

    private suspend fun activeBinding(
        walletAddress: String,
        expectedBindingId: String? = null,
    ): WalletBindingDto? {
        val me = withAuthRetry(api::me)
        return me.walletBindings.firstOrNull {
            it.status == "ACTIVE" &&
                it.walletAddress == walletAddress &&
                it.cluster == solanaConfig.cluster &&
                (expectedBindingId == null || it.walletBindingId == expectedBindingId)
        }
    }

    private suspend fun migratePreparedOperation(
        operation: ActiveOperationEntity,
        intent: ClaimIntentDto,
    ): ActiveOperationEntity {
        val migrated = operation.copy(
            operationId = intent.acceptanceId,
            localState = STATE_PREPARING_TRANSACTION,
            remoteState = intent.status,
            chainSignature = intent.chainSignature,
            updatedAtMs = serverClock.nowMillis(),
        )
        if (operation.operationId != migrated.operationId) {
            operationDao.delete(operation.operationId)
        }
        operationDao.upsert(migrated)
        return migrated
    }

    private fun claimPrepareKey(walletBindingId: String): String =
        "claim-prepare:" + walletBindingId + ":" + UUID.randomUUID()

    private fun bindingIdFromPrepareKey(value: String): String? {
        val parts = value.split(':')
        return if (
            parts.size == 3 &&
            parts[0] == "claim-prepare" &&
            parts[1].isNotBlank()
        ) {
            parts[1]
        } else {
            null
        }
    }

    private suspend fun persistWalletMetadata(walletAddress: String) {
        val session = auth.currentSession()
        walletMetadataDao.upsert(
            WalletSessionMetadataEntity(
                profileKey = PROFILE_KEY,
                walletAddress = walletAddress,
                solanaCluster = solanaConfig.cluster,
                authSubjectId = session?.authSubjectId,
                sessionState = "WALLET_BOUND",
                updatedAtMs = serverClock.nowMillis(),
            ),
        )
    }

    private suspend fun activeBoundWallet(): WalletAccount? {
        val metadata = walletMetadataDao.get(PROFILE_KEY) ?: return null
        val address = metadata.walletAddress ?: return null
        if (metadata.solanaCluster != solanaConfig.cluster) return null
        return WalletAccount(address = address, label = "")
    }

    private suspend fun <T> withAuthRetry(
        block: suspend (String) -> T,
    ): T =
        try {
            withAuthRetryApi(block)
        } catch (error: ApiFailure) {
            throw error.toClaimFailure()
        }

    private suspend fun <T> withAuthRetryApi(
        block: suspend (String) -> T,
    ): T {
        val session = try {
            auth.ensureAnonymousSession()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw ContributorClaimFailure.Network(error)
        }

        return try {
            block(session.accessToken)
        } catch (error: ApiFailure.AuthExpired) {
            val refreshed = auth.refreshIfNeeded()
            block(refreshed.accessToken)
        }
    }

    private fun ApiFailure.toClaimFailure(): ContributorClaimFailure =
        when (this) {
            is ApiFailure.Configuration ->
                ContributorClaimFailure.Configuration(message ?: "Configuration is incomplete.")
            is ApiFailure.NetworkUnavailable,
            is ApiFailure.Timeout,
            is ApiFailure.RateLimited ->
                ContributorClaimFailure.Network(this)
            is ApiFailure.ServerFailure ->
                ContributorClaimFailure.Server(message ?: "Claim service is unavailable.")
            is ApiFailure.BusinessError ->
                when (code) {
                    "CLAIM_NOT_AVAILABLE",
                    "CLAIM_CAPACITY_FULL" ->
                        ContributorClaimFailure.Unavailable(
                            message ?: "This opportunity is no longer available.",
                        )
                    "CLAIM_EXPIRED" -> ContributorClaimFailure.Expired()
                    "CLAIM_REJECTED" ->
                        ContributorClaimFailure.Rejected(
                            message ?: "Claim transaction was rejected.",
                        )
                    else -> ContributorClaimFailure.Server(message ?: code)
                }
            is ApiFailure.ProtocolError ->
                ContributorClaimFailure.Protocol(
                    message ?: "Claim response contract failed.",
                    this,
                )
            is ApiFailure.AuthExpired ->
                ContributorClaimFailure.Server("Session expired.")
        }

    private fun <T> WalletResult<T>.requireWalletValue(): T =
        when (this) {
            is WalletResult.Success -> value
            WalletResult.NoWalletFound -> throw ContributorClaimFailure.WalletUnavailable()
            WalletResult.UserRejected -> throw ContributorClaimFailure.WalletRejected()
            WalletResult.Busy -> throw ContributorClaimFailure.WalletBusy()
            is WalletResult.AssociationFailure -> throw ContributorClaimFailure.Wallet(reason)
            is WalletResult.ProtocolFailure -> throw ContributorClaimFailure.Wallet(reason)
            is WalletResult.UnknownFailure -> throw ContributorClaimFailure.Wallet(reason)
        }

    private companion object {
        const val PROFILE_KEY = "default"
        const val OPERATION_TYPE = "CONTRIBUTOR_CLAIM"

        const val STATE_PREPARING_REMOTE = "PREPARING_REMOTE"
        const val STATE_PREPARING_TRANSACTION = "PREPARING_TRANSACTION"
        const val STATE_READY_FOR_WALLET = "READY_FOR_WALLET"
        const val STATE_WALLET_PENDING = "WALLET_PENDING"
        const val STATE_SUBMITTED = "SUBMITTED"
        const val STATE_RECONCILING = "RECONCILING"
        const val STATE_CONFIRMING = "CONFIRMING"
        const val STATE_ACKNOWLEDGED = "ACKNOWLEDGED"
        const val STATE_REJECTED = "REJECTED"
    }
}
