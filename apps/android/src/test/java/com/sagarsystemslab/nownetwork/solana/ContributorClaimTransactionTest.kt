package com.sagarsystemslab.nownetwork.solana

import com.sagarsystemslab.nownetwork.config.RewardDisplayConfig
import com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig
import com.sagarsystemslab.nownetwork.network.ClaimAccountsDto
import com.sagarsystemslab.nownetwork.network.ClaimInstructionDto
import com.sagarsystemslab.nownetwork.network.ClaimIntentDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributorClaimTransactionTest {
    @Test
    fun validClaimIntentBuildsAtaAndClaimTransaction() = runBlocking {
        val fixture = fixture()

        val result = fixture.builder.buildClaim(
            intent = fixture.intent,
            walletAddress = fixture.wallet.address,
            latestBlockhash = LatestBlockhash(
                blockhash = SolanaPublicKey.fromBytes(ByteArray(32) { 9 }).address,
                lastValidBlockHeight = 1_200L,
            ),
        )

        assertTrue(result.bytes.isNotEmpty())
        assertEquals(1_200L, result.lastValidBlockHeight)
        assertEquals(fixture.refresh.address, result.metadata.refreshAddress)
        assertEquals(
            listOf(
                "create_claimant_reward_account_if_needed",
                "claim_witness",
            ),
            result.metadata.instructionNames,
        )
    }

    @Test(expected = ClaimIntentRejected::class)
    fun claimantMismatchFailsBeforeWalletSubmission() {
        runBlocking {
            val fixture = fixture()
            fixture.builder.buildClaim(
                intent = fixture.intent.copy(
                    walletAddress = SolanaPublicKey.fromBytes(ByteArray(32) { 7 }).address,
                ),
                walletAddress = fixture.wallet.address,
                latestBlockhash = LatestBlockhash(
                    blockhash = SolanaPublicKey.fromBytes(ByteArray(32) { 9 }).address,
                    lastValidBlockHeight = 1_200L,
                ),
            )
        }
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
        val rewardAccount = findProgramAddress(
            seeds = listOf(
                wallet.bytes,
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
        val builder = SolanaTransactionBuilder(
            config = runtime,
            rewardConfig = RewardDisplayConfig(
                mint = mint.address,
                symbol = "USDC",
                decimals = 6,
            ),
        )
        val refreshHex = chainRefreshId.joinToString("") { "%02x".format(it) }

        return Fixture(
            builder = builder,
            wallet = wallet,
            refresh = refresh,
            intent = ClaimIntentDto(
                acceptanceId = "11111111-1111-4111-8111-111111111111",
                refreshId = "22222222-2222-4222-8222-222222222222",
                status = "WALLET_PENDING",
                claimDurationSeconds = 300L,
                refreshStatus = "AVAILABLE",
                refreshExpiresAt = "2035-01-01T00:10:00Z",
                evidenceDeadline = "2035-01-01T00:08:00Z",
                revision = 1,
                nextStep = "SIGN_OR_OBSERVE_CLAIM",
                cluster = "devnet",
                programId = program.address,
                walletAddress = wallet.address,
                rewardMint = mint.address,
                chainRefreshIdHex = refreshHex,
                accounts = ClaimAccountsDto(
                    claimant = wallet.address,
                    config = configAddress.address,
                    refresh = refresh.address,
                    rewardMint = mint.address,
                    claimantRewardTokenAccount = rewardAccount.address,
                ),
                instruction = ClaimInstructionDto(
                    name = "claim_witness",
                    refreshIdHex = refreshHex,
                    claimDurationSeconds = 300L,
                ),
            ),
        )
    }

    private data class Fixture(
        val builder: SolanaTransactionBuilder,
        val wallet: SolanaPublicKey,
        val refresh: SolanaPublicKey,
        val intent: ClaimIntentDto,
    )
}
