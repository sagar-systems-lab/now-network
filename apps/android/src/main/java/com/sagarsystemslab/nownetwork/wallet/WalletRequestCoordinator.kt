package com.sagarsystemslab.nownetwork.wallet

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

@Singleton
class WalletRequestCoordinator @Inject constructor(
    private val gateway: WalletGateway,
) {
    private val walletMutex = Mutex()

    suspend fun connect(host: WalletInteractionHost): WalletResult<WalletAccount> =
        serialized { gateway.connect(host) }

    suspend fun disconnect(host: WalletInteractionHost): WalletResult<Unit> =
        serialized { gateway.disconnect(host) }

    suspend fun signWalletProof(
        host: WalletInteractionHost,
        message: String,
    ): WalletResult<WalletProof> =
        serialized { gateway.signWalletProof(host, message) }

    suspend fun signAndSend(
        host: WalletInteractionHost,
        transaction: ByteArray,
    ): WalletResult<WalletSubmission> =
        serialized { gateway.signAndSend(host, transaction) }

    private suspend fun <T> serialized(
        block: suspend () -> WalletResult<T>,
    ): WalletResult<T> {
        if (!walletMutex.tryLock()) return WalletResult.Busy

        return try {
            block()
        } finally {
            walletMutex.unlock()
        }
    }
}
