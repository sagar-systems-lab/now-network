package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationDao
import com.sagarsystemslab.nownetwork.data.local.ActiveOperationEntity
import com.sagarsystemslab.nownetwork.data.local.WalletSessionMetadataDao
import com.sagarsystemslab.nownetwork.data.local.WalletSessionMetadataEntity
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.CreateRefreshRequest
import com.sagarsystemslab.nownetwork.network.FundingIntentDto
import com.sagarsystemslab.nownetwork.network.FundingObserveRequest
import com.sagarsystemslab.nownetwork.network.NowApiClient
import com.sagarsystemslab.nownetwork.network.RefreshDto
import com.sagarsystemslab.nownetwork.network.WalletBindingChallengeRequest
import com.sagarsystemslab.nownetwork.network.WalletBindingDto
import com.sagarsystemslab.nownetwork.network.WalletBindingVerifyRequest
import com.sagarsystemslab.nownetwork.solana.FundingTransaction
import com.sagarsystemslab.nownetwork.solana.SolanaRpcClient
import com.sagarsystemslab.nownetwork.solana.SolanaTransactionBuilder
import com.sagarsystemslab.nownetwork.wallet.WalletAccount
import com.sagarsystemslab.nownetwork.wallet.WalletInteractionHost
import com.sagarsystemslab.nownetwork.wallet.WalletRequestCoordinator
import com.sagarsystemslab.nownetwork.wallet.WalletResult
import java.util.UUID
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class PreparedRequesterFunding(
    val operation: ActiveOperationEntity,
    val wallet: WalletAccount,
    val refresh: RefreshDto,
    val intent: FundingIntentDto,
    val transaction: FundingTransaction,
)

sealed interface FundingReconciliation {
    data class Available(
        val refresh: RefreshDto,
        val operation: ActiveOperationEntity,
    ) : FundingReconciliation

    data class Confirming(
        val operation: ActiveOperationEntity,
    ) : FundingReconciliation

    data class ReadyForWallet(
        val prepared: PreparedRequesterFunding,
    ) : FundingReconciliation

    data class WalletOutcomeUnknown(val operation: ActiveOperationEntity) : FundingReconciliation

    data object None : FundingReconciliation
}

sealed class RequesterFundingFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class WalletUnavailable : RequesterFundingFailure("No compatible Solana wallet was found.")
    class WalletRejected : RequesterFundingFailure("Wallet request was cancelled.")
    class WalletBusy : RequesterFundingFailure("Another wallet request is already active.")
    class Wallet(message: String) : RequesterFundingFailure(message)
    class WalletMismatch : RequesterFundingFailure("The connected wallet changed during funding.")
    class Configuration(message: String) : RequesterFundingFailure(message)
    class Network(cause: Throwable) :
        RequesterFundingFailure("Network is unavailable. Your funding state is saved.", cause)
    class Server(message: String) : RequesterFundingFailure(message)
    class Protocol(message: String, cause: Throwable? = null) :
        RequesterFundingFailure(message, cause)
}

interface RequesterFundingRepository {
    suspend fun prepareNew(
        host: WalletInteractionHost,
        stateId: String,
        fundingTargetAtomic: String,
    ): PreparedRequesterFunding

    suspend fun submit(
        host: WalletInteractionHost,
        prepared: PreparedRequesterFunding,
    ): FundingReconciliation

    suspend fun recoverLatest(): FundingReconciliation

    suspend fun prepareExisting(
        operationId: String,
    ): PreparedRequesterFunding?
}

@Singleton
class DefaultRequesterFundingRepository @Inject constructor(
    private val auth: AuthGateway,
    private val api: NowApiClient,
    private val wallet: WalletRequestCoordinator,
    private val walletMetadataDao: WalletSessionMetadataDao,
    private val operationDao: ActiveOperationDao,
    private val rpc: SolanaRpcClient,
    private val transactionBuilder: SolanaTransactionBuilder,
    private val serverClock: ServerClock,
    private val solanaConfig: com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig,
) : RequesterFundingRepository {
    override suspend fun prepareNew(
        host: WalletInteractionHost,
        stateId: String,
        fundingTargetAtomic: String,
    ): PreparedRequesterFunding {
        val account = wallet.connect(host).requireWalletValue()
        val binding = ensureBinding(host, account)
        val createKey = idempotencyKey("refresh-create")

        val refresh = withAuthRetry { token ->
            api.createRefresh(
                request = CreateRefreshRequest(
                    stateId = stateId,
                    walletBindingId = binding.walletBindingId,
                    fundingTargetAtomic = fundingTargetAtomic,
                ),
                idempotencyKey = createKey,
                accessToken = token,
            )
        }

        val intentKey = idempotencyKey("funding-intent")
        val intent = withAuthRetry { token ->
            api.fundingIntent(
                refreshId = refresh.refreshId,
                idempotencyKey = intentKey,
                accessToken = token,
            )
        }

        val now = serverClock.nowMillis()
        var operation = ActiveOperationEntity(
            operationId = intent.operationId,
            type = OPERATION_TYPE,
            entityId = refresh.refreshId,
            localState = STATE_PREPARING_TRANSACTION,
            remoteState = intent.status,
            chainSignature = null,
            lastValidBlockHeight = null,
            idempotencyKey = intentKey,
            createdAtMs = now,
            updatedAtMs = now,
        )
        operationDao.upsert(operation)

        val latest = rpc.latestBlockhash()
        val transaction = transactionBuilder.buildFunding(
            intent = intent,
            walletAddress = account.address,
            latestBlockhash = latest,
            expectedAmountAtomic = fundingTargetAtomic,
        )

        operation = operation.copy(
            localState = STATE_READY_FOR_WALLET,
            lastValidBlockHeight = latest.lastValidBlockHeight,
            updatedAtMs = serverClock.nowMillis(),
        )
        operationDao.upsert(operation)

        return PreparedRequesterFunding(
            operation = operation,
            wallet = account,
            refresh = refresh,
            intent = intent,
            transaction = transaction,
        )
    }

    override suspend fun submit(
        host: WalletInteractionHost,
        prepared: PreparedRequesterFunding,
    ): FundingReconciliation {
        val result = withTimeoutOrNull(WALLET_SIGN_MS) {
            wallet.signAndSend(host, prepared.transaction.bytes)
        } ?: WalletResult.UnknownFailure("Wallet did not respond in time.")

        when (result) {
            is WalletResult.Success -> {
                if (result.value.account.address != prepared.wallet.address) {
                    operationDao.upsert(prepared.operation.copy(
                        localState = STATE_RECONCILING,
                        chainSignature = result.value.signatureBase58,
                        updatedAtMs = serverClock.nowMillis(),
                    ))
                    throw RequesterFundingFailure.WalletMismatch()
                }

                val submitted = prepared.operation.copy(
                    localState = STATE_SUBMITTED,
                    chainSignature = result.value.signatureBase58,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(submitted)
                return try {
                    observeSignature(submitted, result.value.signatureBase58)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val confirming = submitted.copy(
                        localState = STATE_CONFIRMING,
                        updatedAtMs = serverClock.nowMillis(),
                    )
                    operationDao.upsert(confirming)
                    FundingReconciliation.Confirming(confirming)
                }
            }

            WalletResult.UserRejected -> {
                operationDao.upsert(
                    prepared.operation.copy(
                        localState = STATE_READY_FOR_WALLET,
                        updatedAtMs = serverClock.nowMillis(),
                    ),
                )
                throw RequesterFundingFailure.WalletRejected()
            }

            WalletResult.NoWalletFound -> throw RequesterFundingFailure.WalletUnavailable()
            WalletResult.Busy -> throw RequesterFundingFailure.WalletBusy()
            is WalletResult.ProtocolFailure -> {
                val ready = prepared.operation.copy(
                    localState = STATE_READY_FOR_WALLET,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(ready)
                throw RequesterFundingFailure.Wallet(result.reason)
            }

            is WalletResult.AssociationFailure,
            is WalletResult.UnknownFailure -> {
                val reconciling = prepared.operation.copy(
                    localState = STATE_RECONCILING,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(reconciling)
                val recovered = reconcileUnknownSubmission(reconciling)
                if (recovered !is FundingReconciliation.None) {
                    return recovered
                }
                if (operationDao.get(reconciling.operationId)?.localState in TERMINAL_STATES) {
                    return FundingReconciliation.None
                }
                return FundingReconciliation.WalletOutcomeUnknown(reconciling)
            }
        }
    }

    override suspend fun recoverLatest(): FundingReconciliation {
        val operation = operationDao.listAll()
            .asSequence()
            .filter { it.type == OPERATION_TYPE }
            .filter { it.localState !in TERMINAL_STATES }
            .maxByOrNull { it.updatedAtMs }
            ?: return FundingReconciliation.None

        operation.chainSignature?.let { signature ->
            return observeSignature(operation, signature)
        }

        if (operation.localState == STATE_RECONCILING) {
            val recovered = reconcileUnknownSubmission(operation)
            if (recovered !is FundingReconciliation.None) return recovered
            if (serverClock.nowMillis() - operation.updatedAtMs < WALLET_WAIT_MS) {
                return FundingReconciliation.WalletOutcomeUnknown(operation)
            }
        }

        return prepareExisting(operation.operationId)
            ?.let(FundingReconciliation::ReadyForWallet)
            ?: FundingReconciliation.None
    }

    override suspend fun prepareExisting(
        operationId: String,
    ): PreparedRequesterFunding? {
        val operation = operationDao.get(operationId) ?: return null
        if (operation.type != OPERATION_TYPE || operation.localState in TERMINAL_STATES) {
            return null
        }

        val refresh = refreshDetailOrTerminateMissing(operation) ?: return null
        if (refresh.status == "AVAILABLE" || refresh.status == "FUNDED") {
            val done = operation.copy(
                localState = STATE_ACKNOWLEDGED,
                remoteState = refresh.status,
                updatedAtMs = serverClock.nowMillis(),
            )
            operationDao.upsert(done)
            return null
        }
        if (refresh.status in setOf("EXPIRED", "CANCELLED", "FAILED") ||
            runCatching { Instant.parse(refresh.refreshExpiresAt).toEpochMilli() <= serverClock.nowMillis() }
                .getOrDefault(false)
        ) {
            operationDao.upsert(operation.copy(localState = "EXPIRED", remoteState = refresh.status,
                updatedAtMs = serverClock.nowMillis()))
            return null
        }

        val intent = withAuthRetry { token ->
            api.fundingIntent(
                refreshId = refresh.refreshId,
                idempotencyKey = operation.idempotencyKey,
                accessToken = token,
            )
        }

        val account = activeBoundWallet(intent.creatorWallet)
        val latest = rpc.latestBlockhash()
        val transaction = transactionBuilder.buildFunding(
            intent = intent,
            walletAddress = account.address,
            latestBlockhash = latest,
            expectedAmountAtomic = refresh.fundingTargetAtomic,
        )
        val ready = operation.copy(
            localState = STATE_READY_FOR_WALLET,
            remoteState = intent.status,
            lastValidBlockHeight = latest.lastValidBlockHeight,
            updatedAtMs = serverClock.nowMillis(),
        )
        operationDao.upsert(ready)

        return PreparedRequesterFunding(
            operation = ready,
            wallet = account,
            refresh = refresh,
            intent = intent,
            transaction = transaction,
        )
    }

    private suspend fun ensureBinding(
        host: WalletInteractionHost,
        account: WalletAccount,
    ): WalletBindingDto {
        val me = withAuthRetry(api::me)
        me.walletBindings.firstOrNull {
            it.status == "ACTIVE" &&
                it.walletAddress == account.address &&
                it.cluster == solanaConfig.cluster
        }?.let { binding ->
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
            throw RequesterFundingFailure.WalletMismatch()
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
            throw RequesterFundingFailure.Protocol("Wallet binding response did not match request.")
        }

        persistWalletMetadata(account.address)
        return verified.walletBinding
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

    private suspend fun activeBoundWallet(expectedAddress: String): WalletAccount {
        val metadata = walletMetadataDao.get(PROFILE_KEY)
        if (
            metadata == null ||
            metadata.walletAddress != expectedAddress ||
            metadata.solanaCluster != solanaConfig.cluster
        ) {
            throw RequesterFundingFailure.Wallet(
                "Reconnect the requester wallet before resuming this funding operation.",
            )
        }

        return WalletAccount(
            address = expectedAddress,
            label = "",
        )
    }

    private suspend fun observeSignature(
        operation: ActiveOperationEntity,
        signature: String,
    ): FundingReconciliation {
        return try {
            val refresh = withAuthRetryApi { token ->
                api.observeFunding(
                    refreshId = operation.entityId,
                    request = FundingObserveRequest(signature),
                    idempotencyKey = observeKey(operation.operationId, signature),
                    accessToken = token,
                )
            }

            val completed = operation.copy(
                localState = STATE_ACKNOWLEDGED,
                remoteState = refresh.status,
                chainSignature = signature,
                updatedAtMs = serverClock.nowMillis(),
            )
            operationDao.upsert(completed)
            FundingReconciliation.Available(refresh, completed)
        } catch (error: ApiFailure.BusinessError) {
            if (error.code == "FUNDING_UNKNOWN" && error.safeToRetry) {
                val confirming = operation.copy(
                    localState = STATE_CONFIRMING,
                    chainSignature = signature,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(confirming)
                FundingReconciliation.Confirming(confirming)
            } else {
                throw error.toRequesterFailure()
            }
        }
    }

    private suspend fun reconcileUnknownSubmission(
        operation: ActiveOperationEntity,
    ): FundingReconciliation {
        val intent = try {
            val refresh = refreshDetailOrTerminateMissing(operation)
                ?: return FundingReconciliation.None
            if (refresh.status in setOf("EXPIRED", "CANCELLED", "FAILED")) {
                operationDao.upsert(operation.copy(localState = "EXPIRED", remoteState = refresh.status,
                    updatedAtMs = serverClock.nowMillis()))
                return FundingReconciliation.None
            }
            if (refresh.status == "AVAILABLE" || refresh.status == "FUNDED") {
                val done = operation.copy(
                    localState = STATE_ACKNOWLEDGED,
                    remoteState = refresh.status,
                    updatedAtMs = serverClock.nowMillis(),
                )
                operationDao.upsert(done)
                return FundingReconciliation.Available(refresh, done)
            }

            withAuthRetry { token ->
                api.fundingIntent(
                    refreshId = operation.entityId,
                    idempotencyKey = operation.idempotencyKey,
                    accessToken = token,
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return FundingReconciliation.None
        }

        for (signature in rpc.recentSignatures(intent.accounts.refresh)) {
            val result = try {
                observeSignature(operation, signature)
            } catch (error: RequesterFundingFailure) {
                null
            } catch (error: ApiFailure.BusinessError) {
                null
            }
            if (result is FundingReconciliation.Available) return result
        }

        return FundingReconciliation.None
    }

    private suspend fun refreshDetailOrTerminateMissing(
        operation: ActiveOperationEntity,
    ): RefreshDto? =
        try {
            withAuthRetryApi { token ->
                api.refreshDetail(operation.entityId, token)
            }
        } catch (error: ApiFailure.BusinessError) {
            if (error.code != "REFRESH_NOT_FOUND") {
                throw error.toRequesterFailure()
            }
            operationDao.upsert(
                operation.copy(
                    localState = "CANCELLED",
                    remoteState = "NOT_FOUND",
                    updatedAtMs = serverClock.nowMillis(),
                ),
            )
            null
        } catch (error: ApiFailure) {
            throw error.toRequesterFailure()
        }

    private suspend fun <T> withAuthRetry(
        block: suspend (String) -> T,
    ): T =
        try {
            withAuthRetryApi(block)
        } catch (error: ApiFailure) {
            throw error.toRequesterFailure()
        }

    private suspend fun <T> withAuthRetryApi(
        block: suspend (String) -> T,
    ): T {
        val session = try {
            auth.ensureAnonymousSession()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw RequesterFundingFailure.Network(error)
        }

        return try {
            block(session.accessToken)
        } catch (error: ApiFailure.AuthExpired) {
            val refreshed = auth.refreshIfNeeded()
            block(refreshed.accessToken)
        }
    }

    private fun ApiFailure.toRequesterFailure(): RequesterFundingFailure =
        when (this) {
            is ApiFailure.Configuration ->
                RequesterFundingFailure.Configuration(message ?: "Configuration is incomplete.")
            is ApiFailure.NetworkUnavailable,
            is ApiFailure.Timeout,
            is ApiFailure.RateLimited ->
                RequesterFundingFailure.Network(this)
            is ApiFailure.ServerFailure ->
                RequesterFundingFailure.Server(message ?: "Requester service is unavailable.")
            is ApiFailure.BusinessError ->
                RequesterFundingFailure.Server(message ?: code)
            is ApiFailure.ProtocolError ->
                RequesterFundingFailure.Protocol(
                    message ?: "Requester response contract failed.",
                    this,
                )
            is ApiFailure.AuthExpired ->
                RequesterFundingFailure.Server("Session expired.")
        }

    private fun <T> WalletResult<T>.requireWalletValue(): T =
        when (this) {
            is WalletResult.Success -> value
            WalletResult.NoWalletFound -> throw RequesterFundingFailure.WalletUnavailable()
            WalletResult.UserRejected -> throw RequesterFundingFailure.WalletRejected()
            WalletResult.Busy -> throw RequesterFundingFailure.WalletBusy()
            is WalletResult.AssociationFailure -> throw RequesterFundingFailure.Wallet(reason)
            is WalletResult.ProtocolFailure -> throw RequesterFundingFailure.Wallet(reason)
            is WalletResult.UnknownFailure -> throw RequesterFundingFailure.Wallet(reason)
        }

    private fun idempotencyKey(prefix: String): String =
        "$prefix:${UUID.randomUUID()}"

    private fun observeKey(operationId: String, signature: String): String =
        "funding-observe:$operationId:${signature.take(24)}"

    private companion object {
        const val WALLET_SIGN_MS = 30_000L
        const val WALLET_WAIT_MS = 30_000L
        const val PROFILE_KEY = "default"
        const val OPERATION_TYPE = "REFRESH_FUNDING"

        const val STATE_PREPARING_TRANSACTION = "PREPARING_TRANSACTION"
        const val STATE_READY_FOR_WALLET = "READY_FOR_WALLET"
        const val STATE_SUBMITTED = "SUBMITTED"
        const val STATE_RECONCILING = "RECONCILING"
        const val STATE_CONFIRMING = "CONFIRMING"
        const val STATE_ACKNOWLEDGED = "ACKNOWLEDGED"

        val TERMINAL_STATES = setOf(
            STATE_ACKNOWLEDGED,
            "CANCELLED",
            "EXPIRED",
            "REJECTED",
        )
    }
}
