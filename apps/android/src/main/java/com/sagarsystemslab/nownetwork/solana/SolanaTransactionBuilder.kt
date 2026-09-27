package com.sagarsystemslab.nownetwork.solana

import com.sagarsystemslab.nownetwork.config.RewardDisplayConfig
import com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig
import com.sagarsystemslab.nownetwork.network.FundingIntentDto
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class FundingTransaction(
    val bytes: ByteArray,
    val lastValidBlockHeight: Long,
    val metadata: FundingTransactionMetadata,
)

data class FundingTransactionMetadata(
    val refreshId: String,
    val refreshAddress: String,
    val programId: String,
    val rewardMint: String,
    val amountAtomic: String,
    val instructionNames: List<String>,
)

class FundingIntentRejected(message: String) : IllegalArgumentException(message)

@Singleton
class SolanaTransactionBuilder @Inject constructor(
    private val config: SolanaRuntimeConfig,
    private val rewardConfig: RewardDisplayConfig,
) {
    suspend fun buildFunding(
        intent: FundingIntentDto,
        walletAddress: String,
        latestBlockhash: LatestBlockhash,
        expectedAmountAtomic: String,
    ): FundingTransaction {
        validateIntent(
            intent = intent,
            walletAddress = walletAddress,
            expectedAmountAtomic = expectedAmountAtomic,
        )

        val program = publicKey(config.programId, "configured program")
        val wallet = publicKey(walletAddress, "wallet")
        val mint = publicKey(intent.rewardMint, "reward mint")
        val configAccount = publicKey(intent.accounts.config, "config account")
        val refresh = publicKey(intent.accounts.refresh, "refresh account")
        val contribution = publicKey(intent.accounts.contribution, "contribution account")
        val sourceToken = publicKey(intent.accounts.sourceTokenAccount, "source token account")
        val vault = publicKey(intent.accounts.vaultTokenAccount, "vault token account")

        val createVault = SolanaInstruction(
            programId = SolanaPrograms.associatedToken,
            accounts = listOf(
                SolanaAccountMeta(wallet, isSigner = true, isWritable = true),
                SolanaAccountMeta(vault, isSigner = false, isWritable = true),
                SolanaAccountMeta(refresh, isSigner = false, isWritable = false),
                SolanaAccountMeta(mint, isSigner = false, isWritable = false),
                SolanaAccountMeta(SolanaPrograms.system, isSigner = false, isWritable = false),
                SolanaAccountMeta(SolanaPrograms.token, isSigner = false, isWritable = false),
                SolanaAccountMeta(SolanaPrograms.rent, isSigner = false, isWritable = false),
            ),
            data = byteArrayOf(1),
        )

        val createRefresh = SolanaInstruction(
            programId = program,
            accounts = listOf(
                SolanaAccountMeta(wallet, isSigner = true, isWritable = true),
                SolanaAccountMeta(configAccount, isSigner = false, isWritable = false),
                SolanaAccountMeta(refresh, isSigner = false, isWritable = true),
                SolanaAccountMeta(mint, isSigner = false, isWritable = false),
                SolanaAccountMeta(vault, isSigner = false, isWritable = false),
                SolanaAccountMeta(SolanaPrograms.token, isSigner = false, isWritable = false),
                SolanaAccountMeta(SolanaPrograms.system, isSigner = false, isWritable = false),
            ),
            data = createRefreshData(intent),
        )

        val contribute = SolanaInstruction(
            programId = program,
            accounts = listOf(
                SolanaAccountMeta(wallet, isSigner = true, isWritable = true),
                SolanaAccountMeta(configAccount, isSigner = false, isWritable = false),
                SolanaAccountMeta(refresh, isSigner = false, isWritable = true),
                SolanaAccountMeta(contribution, isSigner = false, isWritable = true),
                SolanaAccountMeta(sourceToken, isSigner = false, isWritable = true),
                SolanaAccountMeta(vault, isSigner = false, isWritable = true),
                SolanaAccountMeta(mint, isSigner = false, isWritable = false),
                SolanaAccountMeta(SolanaPrograms.token, isSigner = false, isWritable = false),
                SolanaAccountMeta(SolanaPrograms.system, isSigner = false, isWritable = false),
            ),
            data = contributeData(
                chainRefreshId = hex32(intent.chainRefreshIdHex, "chain refresh id"),
                amountAtomic = parseU64(intent.amountAtomic, "funding amount"),
            ),
        )

        val transaction = try {
            serializeLegacyTransaction(
                feePayer = wallet,
                recentBlockhash = latestBlockhash.blockhash,
                instructions = listOf(
                    createVault,
                    createRefresh,
                    contribute,
                ),
            )
        } catch (error: IllegalArgumentException) {
            throw FundingIntentRejected(
                error.message ?: "Funding transaction serialization failed",
            )
        }

        return FundingTransaction(
            bytes = transaction,
            lastValidBlockHeight = latestBlockhash.lastValidBlockHeight,
            metadata = FundingTransactionMetadata(
                refreshId = intent.refreshId,
                refreshAddress = intent.accounts.refresh,
                programId = intent.programId,
                rewardMint = intent.rewardMint,
                amountAtomic = intent.amountAtomic,
                instructionNames = listOf(
                    "create_vault_if_needed",
                    "create_refresh",
                    "contribute",
                ),
            ),
        )
    }

    private fun validateIntent(
        intent: FundingIntentDto,
        walletAddress: String,
        expectedAmountAtomic: String,
    ) {
        if (!config.configured) {
            throw FundingIntentRejected("Solana runtime is not configured")
        }
        if (intent.cluster != config.cluster) {
            throw FundingIntentRejected("Funding intent cluster mismatch")
        }
        if (intent.programId != config.programId) {
            throw FundingIntentRejected("Funding intent program mismatch")
        }
        if (!rewardConfig.valid || rewardConfig.mint.isBlank()) {
            throw FundingIntentRejected("Reward mint is not configured")
        }
        if (intent.rewardMint != rewardConfig.mint) {
            throw FundingIntentRejected("Funding intent reward mint mismatch")
        }
        if (intent.creatorWallet != walletAddress) {
            throw FundingIntentRejected("Funding intent wallet mismatch")
        }
        if (intent.amountAtomic != expectedAmountAtomic) {
            throw FundingIntentRejected("Funding intent amount mismatch")
        }
        if (intent.instructionPlan != listOf("create_refresh", "contribute")) {
            throw FundingIntentRejected("Funding instruction plan mismatch")
        }
        if (intent.accounts.tokenProgram != SolanaPrograms.token.address) {
            throw FundingIntentRejected("Unexpected token program")
        }
        if (intent.accounts.associatedTokenProgram != SolanaPrograms.associatedToken.address) {
            throw FundingIntentRejected("Unexpected associated token program")
        }
        if (intent.accounts.systemProgram != SolanaPrograms.system.address) {
            throw FundingIntentRejected("Unexpected system program")
        }

        parseU64(intent.amountAtomic, "funding amount")
        hex32(intent.intentCoreHash, "intent core hash")
        val chainRefreshId = hex32(intent.chainRefreshIdHex, "chain refresh id")
        hex32(intent.stateIdDigestHex, "state id digest")

        val program = publicKey(intent.programId, "program")
        val mint = publicKey(intent.rewardMint, "reward mint")
        val wallet = publicKey(walletAddress, "wallet")

        val expectedConfig = findProgramAddress(
            seeds = listOf("config".encodeToByteArray()),
            programId = program,
        )
        val expectedRefresh = findProgramAddress(
            seeds = listOf("refresh".encodeToByteArray(), chainRefreshId),
            programId = program,
        )
        val expectedContribution = findProgramAddress(
            seeds = listOf(
                "contribution".encodeToByteArray(),
                expectedRefresh.bytes,
                wallet.bytes,
            ),
            programId = program,
        )
        val expectedSource = findProgramAddress(
            seeds = listOf(
                wallet.bytes,
                SolanaPrograms.token.bytes,
                mint.bytes,
            ),
            programId = SolanaPrograms.associatedToken,
        )
        val expectedVault = findProgramAddress(
            seeds = listOf(
                expectedRefresh.bytes,
                SolanaPrograms.token.bytes,
                mint.bytes,
            ),
            programId = SolanaPrograms.associatedToken,
        )

        requireAddress("config", intent.accounts.config, expectedConfig.address)
        requireAddress("refresh", intent.accounts.refresh, expectedRefresh.address)
        requireAddress("contribution", intent.accounts.contribution, expectedContribution.address)
        requireAddress("source token", intent.accounts.sourceTokenAccount, expectedSource.address)
        requireAddress("vault", intent.accounts.vaultTokenAccount, expectedVault.address)
    }

    private fun createRefreshData(intent: FundingIntentDto): ByteArray {
        val verificationClass = when (intent.verificationClass) {
            "FAST" -> 0
            "CORROBORATED" -> 1
            "STRICT" -> 2
            else -> throw FundingIntentRejected("Unsupported verification class")
        }
        val payoutRule = when (intent.payoutRule) {
            "SINGLE_WINNER_ALL" -> 0
            "EQUAL_SPLIT_REQUIRED_WITNESSES" -> 1
            else -> throw FundingIntentRejected("Unsupported payout rule")
        }
        if (intent.requiredWitnesses !in 1..3 || intent.maxWitnesses !in 1..3) {
            throw FundingIntentRejected("Invalid witness bounds")
        }

        return concat(
            discriminator("create_refresh"),
            hex32(intent.chainRefreshIdHex, "chain refresh id"),
            hex32(intent.stateIdDigestHex, "state id digest"),
            hex32(intent.intentCoreHash, "intent core hash"),
            i64Le(intent.refreshExpiresAtUnix),
            byteArrayOf(
                verificationClass.toByte(),
                intent.requiredWitnesses.toByte(),
                intent.maxWitnesses.toByte(),
                payoutRule.toByte(),
            ),
        )
    }

    private fun contributeData(
        chainRefreshId: ByteArray,
        amountAtomic: BigInteger,
    ): ByteArray =
        concat(
            discriminator("contribute"),
            chainRefreshId,
            u64Le(amountAtomic),
        )

    private fun discriminator(name: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("global:$name".encodeToByteArray())
            .copyOfRange(0, 8)

    private fun parseU64(value: String, label: String): BigInteger {
        val parsed = runCatching { BigInteger(value) }
            .getOrElse { throw FundingIntentRejected("$label is invalid") }
        if (parsed <= BigInteger.ZERO || parsed > U64_MAX) {
            throw FundingIntentRejected("$label is outside u64")
        }
        return parsed
    }

    private fun u64Le(value: BigInteger): ByteArray {
        var remaining = value
        return ByteArray(8) {
            val next = remaining.and(BigInteger.valueOf(255L)).toByte()
            remaining = remaining.shiftRight(8)
            next
        }
    }

    private fun i64Le(value: Long): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(value)
            .array()

    private fun hex32(value: String, label: String): ByteArray {
        if (value.length != 64 || value.any { it.digitToIntOrNull(16) == null }) {
            throw FundingIntentRejected("$label must be 32-byte hex")
        }
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun publicKey(value: String, label: String): SolanaPublicKey =
        try {
            SolanaPublicKey.parse(value)
        } catch (error: IllegalArgumentException) {
            throw FundingIntentRejected("$label is not a valid Solana key")
        }

    private fun requireAddress(
        label: String,
        actual: String,
        expected: String,
    ) {
        if (actual != expected) {
            throw FundingIntentRejected("$label account mismatch")
        }
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val output = ByteArray(parts.sumOf(ByteArray::size))
        var offset = 0
        parts.forEach { part ->
            part.copyInto(output, destinationOffset = offset)
            offset += part.size
        }
        return output
    }

    private companion object {
        val U64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    }
}
