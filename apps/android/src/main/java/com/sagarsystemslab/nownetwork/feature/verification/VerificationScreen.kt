package com.sagarsystemslab.nownetwork.feature.verification

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.feature.common.*
import com.sagarsystemslab.nownetwork.feature.state.formatStateValue

@Composable
fun VerificationScreen(uiState: VerificationUiState, onBack: () -> Unit, onRetry: () -> Unit, onTrackPayment: () -> Unit, onDone: () -> Unit) {
    val verified=uiState.stage == VerificationStage.VERIFIED
    val checking=uiState.stage == VerificationStage.VERIFYING
    TransactionPage("VERIFICATION RESULT", if(checking) "Your evidence is being reviewed" else "Your evidence has been reviewed", "screen-verification", onBack, footer={
        if(verified) NowPrimaryButton("Track payment",onTrackPayment,Modifier.fillMaxWidth())
        else if(!checking) NowPrimaryButton("Check verification",onRetry,Modifier.fillMaxWidth())
        NowSecondaryButton("Done",onDone,Modifier.fillMaxWidth())
    }) {
        Column(Modifier.fillMaxWidth().testTag(if(checking) "verification-progress" else if(verified) "verification-live-result" else "verification-result"),horizontalAlignment=Alignment.CenterHorizontally) {
            ResultEmblem(verified,checking)
            NowStatusChip(uiState.stage.name.replace('_',' '),when { verified -> NowStatusTone.LIVE; checking -> NowStatusTone.INFO; else -> NowStatusTone.AGING })
            Text(when(uiState.stage) {
                VerificationStage.VERIFIED -> if(uiState.projectionSuperseded) "Proof verified" else "State updated"
                VerificationStage.VERIFYING -> "Checking fresh proof"
                VerificationStage.MORE_EVIDENCE -> "More proof is needed"
                VerificationStage.CONFLICT -> "Evidence needs review"
                VerificationStage.REJECTED -> "Proof not accepted"
                VerificationStage.EXPIRED -> "Refresh expired"
                VerificationStage.ERROR -> "Result unavailable"
            },style=NowType.TitleXL,color=NowColors.Ink950,textAlign=TextAlign.Center)
            Text(uiState.message ?: if(verified) "Your contribution has been verified by the network." else "The result will appear after policy checks finish.",style=NowType.BodyS,color=NowColors.Ink600,textAlign=TextAlign.Center)
        }
        if(checking) LinearProgressIndicator(Modifier.fillMaxWidth(),color=NowColors.Blue600)
        if(verified) NowGlassCard(emphasized=true) {
            Text(if(uiState.projectionSuperseded) "Accepted observation" else "Verified answer",style=NowType.LabelM,color=NowColors.Ink600)
            Text(formatStateValue((if(uiState.projectionSuperseded) uiState.finalAnswer else uiState.projectedValue ?: uiState.finalAnswer)?.toString(),null),style=NowType.DataLarge,color=NowColors.Ink950)
            if(uiState.projectionSuperseded) Text("A newer observation is already current. This contribution does not replace it.",style=NowType.BodyS,color=NowColors.Ink600)
            else uiState.projectedFreshness?.let { NowStatusChip(it,NowStatusTone.INFO) }
        }
        NowGlassCard {
            TimelineStep("Fresh proof received", "${uiState.evidenceCount} evidence ${if(uiState.evidenceCount==1) "packet" else "packets"}", uiState.evidenceCount>0)
            TimelineStep("Observation verified",if(verified) "Accepted under the locked verification policy" else "Awaiting an accepted verification result",verified)
            TimelineStep(if(uiState.projectionSuperseded) "Newer state preserved" else "State updated",uiState.projectedStateRevision?.let { "Verified revision $it" } ?: "Not yet confirmed",verified, last=true)
        }
        NowGlassCard {
            var expanded by remember { mutableStateOf(false) }
            ExperienceRow("Proof & policy details", if(uiState.policyVersion>0) "Policy v${uiState.policyVersion}" else "Waiting for policy metadata",Icons.Outlined.Shield,{ expanded=!expanded })
            if(expanded) {
                if(uiState.reasonCodes.isNotEmpty()) Text(uiState.reasonCodes.joinToString("\n") { it.lowercase().replace('_',' ').replaceFirstChar { c -> c.uppercase() } },style=NowType.BodyM,color=NowColors.Ink700)
                Text("Evidence set revision ${uiState.evidenceSetRevision}",style=NowType.BodyS,color=NowColors.Ink600)
                if(uiState.replayed) Text("Recovered verification result",style=NowType.BodyS,color=NowColors.Ink600)
            }
        }
    }
}
