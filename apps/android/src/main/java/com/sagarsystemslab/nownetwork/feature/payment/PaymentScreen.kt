package com.sagarsystemslab.nownetwork.feature.payment

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.experience.*
import com.sagarsystemslab.nownetwork.feature.common.*
import kotlinx.serialization.json.JsonObject

@Composable
fun PaymentScreen(uiState: PaymentUiState, onBack: () -> Unit, onRetry: () -> Unit, onViewReceipt: () -> Unit, onDone: () -> Unit,
    context: JsonObject? = null, personalAmount: String? = null) {
    val paid = uiState.stage == PaymentStage.PAID
    val final = paid && uiState.chainCommitment == "finalized" && uiState.finalizedAt != null
    val submitted = uiState.chainSignature != null
    val verified = paid || submitted || uiState.settlementStatus !in setOf("NOT_STARTED", "NOT_SETTLED")
    TransactionPage("Payment", "Track your payout from start to finish", "screen-payment", onBack, footer = {
        if (paid) NowPrimaryButton(if(final) "View receipt" else "View receipt progress", onViewReceipt, Modifier.fillMaxWidth().testTag("view-final-receipt"))
        else NowPrimaryButton("Check payment status", onRetry, Modifier.fillMaxWidth())
        NowSecondaryButton("Back to activity", onDone, Modifier.fillMaxWidth())
    }) {
        RefreshContextCard(context?.text("title")?.ifBlank { null } ?: "Refresh payment", context?.text("role")?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Authoritative settlement status", personalAmount)
        NowGlassCard {
            TimelineStep("Claim accepted", if (verified) "Claim authority checked before settlement" else "Waiting for verified claim authority", verified)
            TimelineStep("Verification completed", if (verified) "Fresh proof accepted by the verifier" else "Not yet confirmed", verified)
            TimelineStep("Settlement submitted", if(submitted) "Transaction submitted on Solana" else "Not yet submitted", submitted)
            TimelineStep(if(final) "Paid & finalized" else "Paid", (uiState.finalizedAt ?: uiState.confirmedAt)?.let(::displayEventTime) ?: "Not yet confirmed", paid, last = true)
        }
        NowGlassCard(emphasized = true, modifier = Modifier.testTag(if(paid) "payment-paid" else "payment-pending")) {
            ExperienceRow(when { final -> "Settlement complete"; paid -> "Paid · finalizing receipt"; uiState.stage == PaymentStage.FAILED -> "Settlement failed"; else -> "Settlement in progress" }, uiState.message,
                if(paid) Icons.Outlined.CheckCircle else Icons.Outlined.Sync)
            NowStatusChip(uiState.settlementStatus.replace('_', ' '), if(paid) NowStatusTone.LIVE else NowStatusTone.INFO)
            uiState.chainSignature?.let { signature ->
                Text(signature.take(10) + "…" + signature.takeLast(8), style=NowType.BodyS,color=NowColors.Ink600)
                ExplorerActions(signature)
            }
            if (uiState.stage == PaymentStage.ATTENTION || uiState.stage == PaymentStage.FAILED) NowNotice("Check the existing settlement again. This screen never sends a second transfer.", tone=NowNoticeTone.WARNING)
        }
        NowGlassCard {
            NowSectionTitle("Payout summary")
            MetricStrip(listOf((personalAmount ?: "Pending") to "Your finalized payout", com.sagarsystemslab.nownetwork.BuildConfig.SOLANA_CLUSTER to "Network"))
            if(personalAmount == null) Text("Your allocation appears once the receipt and recipient authority are final. The total reward pool is separate.",style=NowType.BodyS,color=NowColors.Ink600)
            if(paid) TextButton(onRetry) { Text("Refresh finalization status") }
        }
    }
}
