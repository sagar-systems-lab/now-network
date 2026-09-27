package com.sagarsystemslab.nownetwork.solana

import com.sagarsystemslab.nownetwork.config.RewardDisplayConfig
import com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig
import com.sagarsystemslab.nownetwork.network.FundingAccountsDto
import com.sagarsystemslab.nownetwork.network.FundingIntentDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaTransactionBuilderTest {
    @Test
    fun validFundingIntentBuildsSingleWalletTransaction() = runBlocking {
        val fixture = fixture()
        val result = fixture.builder.buildFunding(
            intent = fixture.intent,
            walletAddress = fixture.wallet.address,
            latestBlockhash = LatestBlockhash(
                blockhash = SolanaPublicKey.fromBytes(ByteArray(32) { 9 }).address,
                lastValidBlockHeight = 900L,
            ),
            expectedAmountAtomic = "450000",
        )

        assertTrue(result.bytes.isNotEmpty())
        assertEquals(900L, result.lastValidBlockHeight)
        assertEquals(
            listOf("create_vault_if_needed", "create_refresh", "contribute"),
            result.metadata.instructionNames,
        )
        assertEquals(fixture.refresh.address, result.metadata.refreshAddress)
    }

    @Test(expected = FundingIntentRejected::class)
    fun programMismatchFailsBeforeWalletSubmission() {
        runBlocking {
            val fixture = fixture()
            fixture.builder.buildFunding(
                intent = fixture.intent.copy(
                    programId = SolanaPublicKey.fromBytes(ByteArray(32) { 8 }).address,
                ),
                walletAddress = fixture.wallet.address,
                latestBlockhash = LatestBlockhash(
                    blockhash = SolanaPublicKey.fromBytes(ByteArray(32) { 9 }).address,
                    lastValidBlockHeight = 900L,
                ),
                expectedAmountAtomic = "450000",
            )
        }
    }

    @Test
    fun base58RoundTripPreservesPublicKeyBytes() {
        val bytes = ByteArray(32) { index -> index.toByte() }
        val encoded = encodeBase58(bytes)
        assertTrue(bytes.contentEquals(decodeBase58(encoded)))
    }

    private fun fixture(): Fixture {
        val program = SolanaPublicKey.fromBytes(ByteArray(32) { 1 })
        val mint = SolanaPublicKey.fromBytes(ByteArray(32) { 2 })
        val wallet = SolanaPublicKey.fromBytes(ByteArray(32) { 3 })
        val chainRefreshId = ByteArray(32) { 4 }

        val configAddress = findProgramAddress(
            seeds = listOf("config".encodeToByteArray()),
            programId = program,
        )
        val refresh = findProgramAddress(
            seeds = listOf("refresh".encodeToByteArray(), chainRefreshId),
            programId = program,
        )
        val contribution = findProgramAddress(
            seeds = listOf(
                "contribution".encodeToByteArray(),
                refresh.bytes,
                wallet.bytes,
            ),
            programId = program,
        )
        val source = findProgramAddress(
            seeds = listOf(
                wallet.bytes,
                SolanaPrograms.token.bytes,
                mint.bytes,
            ),
            programId = SolanaPrograms.associatedToken,
        )
        val vault = findProgramAddress(
            seeds = listOf(
                refresh.bytes,
                SolanaPrograms.token.bytes,
                mint.bytes,
            ),
            programId = SolanaPrograms.associatedToken,
        )

        val runtime = SolanaRuntimeConfig(
            cluster = "devnet",
            rpcUrl = "https://api.devnet.solana.com",
            programId = program.address,
            walletIdentityUri = "https://example.test",
            walletIconUri = "icon.png",
        )
        val rewards = RewardDisplayConfig(
            mint = mint.address,
            symbol = "USDC",
            decimals = 6,
        )
        val builder = SolanaTransactionBuilder(runtime, rewards)
        val hexRefresh = chainRefreshId.joinToString("") { "%02x".format(it) }

        val intent = FundingIntentDto(
            operationId = "11111111-1111-4111-8111-111111111111",
            refreshId = "22222222-2222-4222-8222-222222222222",
            status = "AWAITING_FUNDING",
            cluster = "devnet",
            programId = program.address,
            rewardMint = mint.address,
            amountAtomic = "450000",
            intentCoreHash = "05".repeat(32),
            chainRefreshIdHex = hexRefresh,
            stateIdDigestHex = "06".repeat(32),
            refreshExpiresAtUnix = 2_100_000_000L,
            verificationClass = "FAST",
            requiredWitnesses = 1,
            maxWitnesses = 1,
            payoutRule = "SINGLE_WINNER_ALL",
            creatorWallet = wallet.address,
            accounts = FundingAccountsDto(
                config = configAddress.address,
                refresh = refresh.address,
                contribution = contribution.address,
                sourceTokenAccount = source.address,
                vaultTokenAccount = vault.address,
                tokenProgram = SolanaPrograms.token.address,
                associatedTokenProgram = SolanaPrograms.associatedToken.address,
                systemProgram = SolanaPrograms.system.address,
            ),
            instructionPlan = listOf("create_refresh", "contribute"),
        )

        return Fixture(
            builder = builder,
            intent = intent,
            wallet = wallet,
            refresh = refresh,
        )
    }

    private data class Fixture(
        val builder: SolanaTransactionBuilder,
        val intent: FundingIntentDto,
        val wallet: SolanaPublicKey,
        val refresh: SolanaPublicKey,
    )
}
