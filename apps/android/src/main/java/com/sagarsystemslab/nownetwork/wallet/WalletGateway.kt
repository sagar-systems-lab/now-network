package com.sagarsystemslab.nownetwork.wallet

interface WalletInteractionHost

data class WalletAccount(
    val address: String,
    val label: String,
)

sealed interface WalletResult<out T> {
    data class Success<T>(val value: T) : WalletResult<T>
    data object NoWalletFound : WalletResult<Nothing>
    data object UserRejected : WalletResult<Nothing>
    data object Busy : WalletResult<Nothing>
    data class ProtocolFailure(val reason: String) : WalletResult<Nothing>
    data class AssociationFailure(val reason: String) : WalletResult<Nothing>
    data class UnknownFailure(val reason: String) : WalletResult<Nothing>
}

data class WalletProof(
    val account: WalletAccount,
    val signatureBase64: String,
)

data class WalletSubmission(
    val account: WalletAccount,
    val signatureBase58: String,
)

interface WalletGateway {
    suspend fun connect(host: WalletInteractionHost): WalletResult<WalletAccount>
    suspend fun disconnect(host: WalletInteractionHost): WalletResult<Unit>

    suspend fun signWalletProof(
        host: WalletInteractionHost,
        message: String,
    ): WalletResult<WalletProof>

    suspend fun signAndSend(
        host: WalletInteractionHost,
        transaction: ByteArray,
    ): WalletResult<WalletSubmission>
}
