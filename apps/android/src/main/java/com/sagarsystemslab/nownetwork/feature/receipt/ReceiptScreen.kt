package com.sagarsystemslab.nownetwork.feature.receipt

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.experience.*
import com.sagarsystemslab.nownetwork.feature.common.*
import com.sagarsystemslab.nownetwork.feature.state.formatStateValue
import kotlinx.serialization.json.JsonObject

@Composable
fun ReceiptScreen(uiState: ReceiptUiState,onBack:()->Unit,onRetry:()->Unit,onDone:()->Unit,context:JsonObject?=null,personalAmount:String?=null,poolAmount:String?=null,onViewProof:(String)->Unit={}) {
    val androidContext=LocalContext.current; val clipboard=LocalClipboardManager.current
    val receipt=uiState.receipt?.takeIf { uiState.stage==ReceiptStage.READY }
    val contributor = context?.text("role") == "CONTRIBUTOR"
    val evidenceId = context?.text("evidence_id")?.takeIf { it.isNotBlank() }
        ?: (receipt?.finalValue as? JsonObject)?.text("evidence_id")?.takeIf { it.isNotBlank() }
    TransactionPage("Receipt","Your verified result & settlement","screen-receipt",onBack,footer={
        if(receipt!=null) Row(horizontalArrangement=Arrangement.spacedBy(com.sagarsystemslab.nownetwork.designsystem.NowSpacing.Space2)) {
            NowSecondaryButton("Share receipt",{
                val text="NOW Network · finalized receipt\nReceipt: ${receipt.receiptId}\nTransaction: ${receipt.settlementSignature}\nNetwork: ${com.sagarsystemslab.nownetwork.BuildConfig.SOLANA_CLUSTER}\nFinalized: ${receipt.finalizedAt}"
                try { androidContext.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"Share receipt")) }
                catch(_:android.content.ActivityNotFoundException) { clipboard.setText(AnnotatedString(text)) }
            },Modifier.weight(1f))
            NowPrimaryButton("Done",onDone,Modifier.weight(1f))
        } else { NowPrimaryButton("Check receipt",onRetry,Modifier.fillMaxWidth()); NowSecondaryButton("Back to activity",onDone,Modifier.fillMaxWidth()) }
    }) {
        Column(Modifier.fillMaxWidth().testTag(if(receipt!=null) "receipt-final" else "receipt-finalizing"),horizontalAlignment=Alignment.CenterHorizontally) {
            ResultEmblem(receipt!=null,uiState.stage==ReceiptStage.FINALIZING, eventKey = receipt?.receiptId?.let { "receipt:$it" })
            Text(if(receipt!=null) if(contributor) "Reward received" else "Refresh completed" else if(uiState.stage==ReceiptStage.ATTENTION) "Receipt unavailable" else "Finalizing your receipt",style=NowType.TitleXL,color=NowColors.Ink950,textAlign=TextAlign.Center)
            Text(if(receipt!=null) "This contribution and settlement are finalized on Solana." else uiState.message,style=NowType.BodyS,color=NowColors.Ink600,textAlign=TextAlign.Center)
        }
        if(receipt!=null) {
            if (evidenceId != null) NowGlassCard { ProofMediaCard(evidenceId, context?.flag("has_video") == true, { onViewProof(evidenceId) }) }
            RefreshContextCard(context?.text("title")?.ifBlank { null } ?: "Verified refresh", "${receipt.verificationClass.replace('_',' ')} · Final", formatStateValue(receipt.finalValue.toString(),null),stateId=receipt.stateId)
            NowGlassCard {
                ExperienceRow("Date & time",displayEventTime(receipt.finalizedAt),Icons.Outlined.Schedule)
                ExperienceRow(if(contributor) "Your finalized payout" else "Contributor reward",if(contributor) personalAmount ?: "Loading payout…" else poolAmount ?: "Loading reward…",Icons.Outlined.Payments)
                if(context == null || (contributor && personalAmount == null)) NowSecondaryButton("Refresh payment details",onRetry,Modifier.fillMaxWidth())
                if(com.sagarsystemslab.nownetwork.BuildConfig.SOLANA_CLUSTER == "devnet") Text("Devnet test tokens · no dollar value",style=NowType.BodyS,color=NowColors.Ink600)
                ExperienceRow("Total reward pool",poolAmount ?: "${receipt.rewardAmountAtomic} atomic · ${receipt.rewardMint.take(6)}…",Icons.Outlined.AccountBalance)
                ExperienceRow("Verified answer",formatStateValue(receipt.finalValue.toString(),null),Icons.Outlined.Verified)
                ExperienceRow("Transaction signature",receipt.settlementSignature.take(10)+"…"+receipt.settlementSignature.takeLast(8),Icons.Outlined.Link)
                ExplorerActions(receipt.settlementSignature)
                ExperienceRow("Receipt ID",receipt.receiptId,Icons.Outlined.ReceiptLong,{ clipboard.setText(AnnotatedString(receipt.receiptId)) })
                ExperienceRow("Proof of integrity","On-chain finalized · verification and settlement hashes recorded",Icons.Outlined.Shield)
            }
            NowGlassCard {
                var details by remember { mutableStateOf(false) }
                ExperienceRow("Integrity details",icon=Icons.Outlined.Fingerprint,onClick={ details=!details })
                if(details) listOf("Verification digest" to receipt.verificationDigest,"Settlement operation hash" to receipt.settlementOperationHash,"Receipt digest" to receipt.receiptDigest).forEach { (label,value) ->
                    ExperienceRow(label,value,Icons.Outlined.ContentCopy,{ clipboard.setText(AnnotatedString(value)) })
                }
            }
        } else if(uiState.stage==ReceiptStage.FINALIZING) {
            LinearProgressIndicator(Modifier.fillMaxWidth(),color=NowColors.Blue600)
            NowNotice("Payment confirmation and a final receipt are separate milestones. Your existing settlement continues to be tracked.")
        } else NowNotice(uiState.message,tone=NowNoticeTone.WARNING)
    }
}
