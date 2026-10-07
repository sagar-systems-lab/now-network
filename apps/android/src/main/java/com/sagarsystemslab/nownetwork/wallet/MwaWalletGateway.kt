package com.sagarsystemslab.nownetwork.wallet

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig
import com.sagarsystemslab.nownetwork.security.SecureSecretStore
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationIntentCreator
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.clientlib.scenario.Scenario
import com.solana.mobilewalletadapter.common.ProtocolContract
import com.solana.mobilewalletadapter.common.protocol.SessionProperties
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AndroidWalletInteractionHost(
    internal val activity: ComponentActivity,
) : WalletInteractionHost {
    private var pendingResult: CompletableDeferred<Int>? = null
    private val activityResults = ArrayDeque<CompletableDeferred<Int>>()

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val completed = activityResults.removeFirstOrNull()
        completed?.complete(result.resultCode)
        if (pendingResult === completed) pendingResult = null
    }

    suspend fun launch(intent: Intent): CompletableDeferred<Int> =
        withContext(Dispatchers.Main.immediate) {
            check(pendingResult == null) {
                "Another wallet activity result is still pending."
            }

            val deferred = CompletableDeferred<Int>()
            pendingResult = deferred
            activityResults.addLast(deferred)
            try {
                launcher.launch(intent)
            } catch (error: ActivityNotFoundException) {
                pendingResult = null
                activityResults.remove(deferred)
                deferred.cancel()
                throw error
            }
            deferred
        }

    suspend fun finishAssociation(result: CompletableDeferred<Int>) =
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            if (pendingResult === result) pendingResult = null
            result.cancel()
            if (!activity.isFinishing && !activity.isDestroyed) {
                runCatching { activity.startActivity(Intent(activity, activity.javaClass).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )) }
            }
        }
}

@Singleton
class MwaWalletGateway @Inject constructor(
    private val config: SolanaRuntimeConfig,
    private val secrets: SecureSecretStore,
) : WalletGateway {
    override suspend fun connect(
        host: WalletInteractionHost,
    ): WalletResult<WalletAccount> =
        transact(host) { _, auth ->
            auth.toWalletAccount()
        }

    override suspend fun disconnect(
        host: WalletInteractionHost,
    ): WalletResult<Unit> {
        val authToken = secrets.read(authTokenKey()) ?: return WalletResult.Success(Unit)
        val androidHost = host.androidHostOrFailure()
            ?: return WalletResult.AssociationFailure(
                "Wallet interaction requires the active Android screen.",
            )

        return try {
            withTimeout(WALLET_PROCESS_TIMEOUT_MS) {
                associate(androidHost) { client, _ ->
                    await(client.deauthorize(authToken))
                }
            }
            secrets.remove(authTokenKey())
            WalletResult.Success(Unit)
        } catch (error: TimeoutCancellationException) {
            WalletResult.AssociationFailure("Wallet request timed out after 30 seconds.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: ActivityNotFoundException) {
            WalletResult.NoWalletFound
        } catch (error: WalletUserCancelled) {
            WalletResult.UserRejected
        } catch (error: Exception) {
            mapException(error)
        }
    }

    override suspend fun signWalletProof(
        host: WalletInteractionHost,
        message: String,
    ): WalletResult<WalletProof> =
        transact(host) { client, auth ->
            val account = auth.accounts.first()
            val signed = await(
                client.signMessagesDetached(
                    arrayOf(message.encodeToByteArray()),
                    arrayOf(account.publicKey),
                ),
            ).messages.single()
            val signature = signed.signatures.single()

            WalletProof(
                account = account.toWalletAccount(),
                signatureBase64 = Base64.encodeToString(signature, Base64.NO_WRAP),
            )
        }

    override suspend fun signAndSend(
        host: WalletInteractionHost,
        transaction: ByteArray,
    ): WalletResult<WalletSubmission> =
        transact(host) { client, auth ->
            val account = auth.accounts.first()
            val signature = await(
                client.signAndSendTransactions(
                    arrayOf(transaction),
                    null,
                    "confirmed",
                    false,
                    3,
                    true,
                ),
            ).signatures.single()

            WalletSubmission(
                account = account.toWalletAccount(),
                signatureBase58 = base58(signature),
            )
        }

    private suspend fun <T> transact(
        host: WalletInteractionHost,
        operation: suspend (
            MobileWalletAdapterClient,
            MobileWalletAdapterClient.AuthorizationResult,
        ) -> T,
    ): WalletResult<T> {
        val androidHost = host.androidHostOrFailure()
            ?: return WalletResult.AssociationFailure(
                "Wallet interaction requires the active Android screen.",
            )

        return try {
            val payload = withTimeout(WALLET_PROCESS_TIMEOUT_MS) {
                associate(androidHost) { client, sessionProperties ->
                    val auth = authorize(
                        client = client,
                        protocolVersion = sessionProperties.protocolVersion,
                    )
                    secrets.write(authTokenKey(), auth.authToken)
                    secrets.remove(LEGACY_AUTH_TOKEN_KEY)
                    operation(client, auth)
                }
            }
            WalletResult.Success(payload)
        } catch (error: TimeoutCancellationException) {
            WalletResult.AssociationFailure(
                "Wallet request timed out after 30 seconds. Return to NOW and retry.",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: ActivityNotFoundException) {
            WalletResult.NoWalletFound
        } catch (error: WalletUserCancelled) {
            WalletResult.UserRejected
        } catch (error: Exception) {
            if (isAuthorizationFailure(error)) {
                secrets.remove(authTokenKey())
            }
            mapException(error)
        }
    }

    private suspend fun authorize(
        client: MobileWalletAdapterClient,
        protocolVersion: SessionProperties.ProtocolVersion,
    ): MobileWalletAdapterClient.AuthorizationResult {
        val existingToken = secrets.read(authTokenKey())

        return try {
            authorizeOnce(
                client = client,
                protocolVersion = protocolVersion,
                authToken = existingToken,
            )
        } catch (error: Exception) {
            if (existingToken != null && isAuthorizationFailure(error)) {
                secrets.remove(authTokenKey())
                authorizeOnce(
                    client = client,
                    protocolVersion = protocolVersion,
                    authToken = null,
                )
            } else {
                throw error
            }
        }
    }

    private suspend fun authorizeOnce(
        client: MobileWalletAdapterClient,
        protocolVersion: SessionProperties.ProtocolVersion,
        authToken: String?,
    ): MobileWalletAdapterClient.AuthorizationResult {
        val identityUri = Uri.parse(config.walletIdentityUri)
        val iconUri = Uri.parse(config.walletIconUri)

        return if (protocolVersion == SessionProperties.ProtocolVersion.V1) {
            await(
                client.authorize(
                    identityUri,
                    iconUri,
                    "NOW Network",
                    modernChain(),
                    authToken,
                    null,
                    null,
                    null,
                ),
            )
        } else {
            if (authToken != null) {
                await(
                    client.reauthorize(
                        identityUri,
                        iconUri,
                        "NOW Network",
                        authToken,
                    ),
                )
            } else {
                await(
                    client.authorize(
                        identityUri,
                        iconUri,
                        "NOW Network",
                        legacyCluster(),
                    ),
                )
            }
        }
    }

    private suspend fun <T> associate(
        host: AndroidWalletInteractionHost,
        operation: suspend (
            MobileWalletAdapterClient,
            SessionProperties,
        ) -> T,
    ): T = coroutineScope {
        if (!LocalAssociationIntentCreator.isWalletEndpointAvailable(host.activity.packageManager)) {
            throw ActivityNotFoundException("No compatible wallet found.")
        }

        val scenario = LocalAssociationScenario(Scenario.DEFAULT_CLIENT_TIMEOUT_MS)
        val intent = LocalAssociationIntentCreator.createAssociationIntent(
            null,
            scenario.port,
            scenario.session,
        )
        val activityResult = host.launch(intent)

        val clientDeferred = async(Dispatchers.IO) {
            runInterruptible {
                scenario.start().get(
                    ASSOCIATION_START_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
        }

        val client: MobileWalletAdapterClient = try {
            select<MobileWalletAdapterClient> {
                clientDeferred.onAwait { connectedClient ->
                    connectedClient
                }
                activityResult.onAwait { resultCode ->
                    if (resultCode == Activity.RESULT_CANCELED) {
                        clientDeferred.cancel()
                        throw WalletUserCancelled()
                    }
                    clientDeferred.await()
                }
            }
        } catch (error: Exception) {
            clientDeferred.cancel()
            closeScenario(scenario)
            host.finishAssociation(activityResult)
            throw error
        }

        try {
            operation(
                client,
                scenario.session.sessionProperties,
            )
        } finally {
            closeScenario(scenario)
            host.finishAssociation(activityResult)
        }
    }

    private suspend fun closeScenario(
        scenario: LocalAssociationScenario,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                scenario.close().get(
                    ASSOCIATION_CLOSE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
        }
    }

    private suspend fun <T> await(future: Future<T>): T =
        runInterruptible(Dispatchers.IO) {
            future.get()
        }

    private fun WalletInteractionHost.androidHostOrFailure(): AndroidWalletInteractionHost? =
        this as? AndroidWalletInteractionHost

    private fun MobileWalletAdapterClient.AuthorizationResult.toWalletAccount(): WalletAccount =
        accounts.first().toWalletAccount()

    private fun MobileWalletAdapterClient.AuthorizationResult.AuthorizedAccount.toWalletAccount():
        WalletAccount =
        WalletAccount(
            address = base58(publicKey),
            label = accountLabel.orEmpty(),
        )

    private fun modernChain(): String =
        when (config.cluster.lowercase()) {
            "devnet" -> ProtocolContract.CHAIN_SOLANA_DEVNET
            "testnet" -> ProtocolContract.CHAIN_SOLANA_TESTNET
            "mainnet", "mainnet-beta" -> ProtocolContract.CHAIN_SOLANA_MAINNET
            else -> throw IllegalStateException("Unsupported Solana cluster")
        }

    private fun legacyCluster(): String =
        when (config.cluster.lowercase()) {
            "devnet" -> ProtocolContract.CLUSTER_DEVNET
            "testnet" -> ProtocolContract.CLUSTER_TESTNET
            "mainnet", "mainnet-beta" -> ProtocolContract.CLUSTER_MAINNET_BETA
            else -> throw IllegalStateException("Unsupported Solana cluster")
        }

    private fun isAuthorizationFailure(error: Throwable): Boolean {
        val remote = unwrapRemote(error) ?: return false
        return remote.code == ProtocolContract.ERROR_AUTHORIZATION_FAILED
    }

    private fun mapException(error: Exception): WalletResult<Nothing> {
        val remote = unwrapRemote(error)
        if (remote != null) {
            return when (remote.code) {
                ProtocolContract.ERROR_NOT_SIGNED -> WalletResult.UserRejected
                ProtocolContract.ERROR_AUTHORIZATION_FAILED ->
                    WalletResult.ProtocolFailure("Wallet authorization is no longer valid.")
                ProtocolContract.ERROR_TOO_MANY_PAYLOADS ->
                    WalletResult.ProtocolFailure("Wallet rejected the payload count.")
                ProtocolContract.ERROR_INVALID_PAYLOADS ->
                    WalletResult.ProtocolFailure("Wallet rejected this transaction before submission. Reconnect the wallet on ${config.cluster} and retry.")
                ProtocolContract.ERROR_NOT_SUBMITTED ->
                    WalletResult.ProtocolFailure("Wallet did not submit this transaction. Nothing was funded; retry from NOW.")
                else -> WalletResult.ProtocolFailure(
                    remote.message ?: "Wallet protocol request failed.",
                )
            }
        }

        return when (error) {
            is TimeoutException ->
                WalletResult.AssociationFailure("Wallet association timed out.")
            is IOException ->
                WalletResult.AssociationFailure("Wallet association failed.")
            is ExecutionException ->
                WalletResult.ProtocolFailure(
                    error.cause?.message ?: "Wallet operation failed.",
                )
            else ->
                WalletResult.UnknownFailure(error.message ?: "Wallet request failed.")
        }
    }

    private fun unwrapRemote(
        error: Throwable,
    ): JsonRpc20Client.JsonRpc20RemoteException? {
        val cause = if (error is ExecutionException) error.cause else error
        return cause as? JsonRpc20Client.JsonRpc20RemoteException
    }

    private fun base58(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var zeroCount = 0
        while (zeroCount < bytes.size && bytes[zeroCount].toInt() == 0) {
            zeroCount += 1
        }

        var number = BigInteger(1, bytes)
        val encoded = StringBuilder()
        val base = BigInteger.valueOf(58L)
        while (number > BigInteger.ZERO) {
            val result = number.divideAndRemainder(base)
            encoded.append(BASE58[result[1].toInt()])
            number = result[0]
        }
        repeat(zeroCount) { encoded.append('1') }
        return encoded.reverse().toString()
    }

    private fun authTokenKey(): String =
        "mwa.auth.token.v2." + config.cluster.lowercase()

    private class WalletUserCancelled : Exception()

    private companion object {
        const val LEGACY_AUTH_TOKEN_KEY = "mwa.auth.token.v1"
        const val ASSOCIATION_START_TIMEOUT_SECONDS = 30L
        const val ASSOCIATION_CLOSE_TIMEOUT_SECONDS = 3L
        const val WALLET_PROCESS_TIMEOUT_MS = 30_000L
        const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    }
}
