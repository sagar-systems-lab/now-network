package com.sagarsystemslab.nownetwork.feature.earn

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.experience.displayDistance
import com.sagarsystemslab.nownetwork.feature.common.*

@Composable
fun ContributorClaimScreen(uiState:ContributorClaimUiState,rewardText:String,onBack:()->Unit,onPrepare:()->Unit,onSubmit:()->Unit,onCheck:()->Unit,onCaptureEvidence:()->Unit,onViewVerification:()->Unit = onCheck,estimatedRewardText:String? = null) {
    val stage=uiState.stage; val opportunity=uiState.opportunity
    var requirements by remember { mutableStateOf(false) }
    TransactionPage("Claim opportunity","Review the details and claim to get started","screen-contributor-claim",onBack,footer={
        when(stage) {
            ContributorClaimStage.REVIEW -> NowPrimaryButton("Claim and continue",onPrepare,Modifier.fillMaxWidth().testTag("prepare-claim"),enabled=uiState.canPrepare)
            ContributorClaimStage.READY_FOR_WALLET -> NowPrimaryButton("Confirm claim in wallet",onSubmit,Modifier.fillMaxWidth().testTag("confirm-claim-wallet"))
            ContributorClaimStage.CLAIMED -> NowPrimaryButton("Capture evidence",onCaptureEvidence,Modifier.fillMaxWidth().testTag("capture-evidence"))
            ContributorClaimStage.EVIDENCE_COMMITTED -> NowPrimaryButton("View verification",onViewVerification,Modifier.fillMaxWidth())
            ContributorClaimStage.CONFIRMING -> NowPrimaryButton("Check existing claim",onCheck,Modifier.fillMaxWidth())
            ContributorClaimStage.ERROR -> NowPrimaryButton(if(uiState.canPrepare) "Try preparing again" else "Check claim status",if(uiState.canPrepare) onPrepare else onCheck,Modifier.fillMaxWidth())
            else -> NowPrimaryButton(if(stage==ContributorClaimStage.SUBMITTING) "Waiting for wallet…" else "Checking availability…",{},Modifier.fillMaxWidth(),enabled=false)
        }
        NowSecondaryButton("Review requirements",{requirements=true},Modifier.fillMaxWidth(),enabled=opportunity!=null)
    }) {
        if (opportunity == null && stage in setOf(ContributorClaimStage.CLAIMED, ContributorClaimStage.EVIDENCE_COMMITTED, ContributorClaimStage.CONFIRMING, ContributorClaimStage.READY_FOR_WALLET)) {
            NowGlassCard {
                Text("Your saved claim", style = NowType.TitleM, color = NowColors.Ink950)
                Text(if (stage == ContributorClaimStage.EVIDENCE_COMMITTED) "Your proof was submitted. Continue to its authoritative verification status." else "Continue your existing operation. Availability for new claims does not affect recovery.", style = NowType.BodyM, color = NowColors.Ink600)
            }
        }
        if(opportunity!=null) {
            RefreshContextCard(opportunity.title,opportunity.location.name,opportunity.question,stateId=opportunity.stateId)
            NowGlassCard(emphasized=true,modifier=Modifier.testTag("opportunity-detail")) {
                MetricStrip(listOf((estimatedRewardText ?: rewardText) to (if (estimatedRewardText == null) "Total reward pool" else "Estimated payout"),displayDistance(opportunity.distanceM) to "Away"), framed = false)
                NowStatusChip(if(opportunity.availability.claimable) "Available" else "Check availability",NowStatusTone.INFO)
                Text("Expires ${displayEventTime(opportunity.expiresAt)} · ${opportunity.availability.remainingSlots} slots remaining",style=NowType.BodyS,color=NowColors.Ink600)
                Text(if (estimatedRewardText == null) "Your allocation depends on accepted witnesses. This amount is the whole pool." else "Total pool: $rewardText · Estimate follows the locked witness rule. Payment requires accepted proof and final settlement.",style=NowType.BodyS,color=NowColors.Ink600)
            }
            NowGlassCard {
                ExperienceRow("Proof requirements","Complete the locked requirements to receive your reward",Icons.Outlined.Assignment,{requirements=true})
                ProofSteps(opportunity.evidenceSummary.mediaRequired,opportunity.evidenceSummary.locationRequired,when(opportunity.stateType.uppercase()) { "NUMERIC" -> "Count what you see"; "BINARY" -> "Answer yes or no"; else -> "Show the condition" },video=opportunity.evidenceSummary.videoRequired)
            }
            NowGlassCard {
                Text("How it works", style = NowType.TitleS, color = NowColors.Ink950)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Review", "Claim", "Capture").forEachIndexed { index, label ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            NowStatusChip("${index + 1}", NowStatusTone.INFO, accessibilityLabel = "Step ${index + 1}")
                            Text(label, style = NowType.LabelL, color = NowColors.Ink800)
                        }
                    }
                }
                Text("Review the requirements, approve your claim in the wallet, then capture fresh proof on site.", style = NowType.BodyS, color = NowColors.Ink600)
            }
        }
        NowGlassCard {
            ExperienceRow("Payout wallet",uiState.walletAddress ?: "Your verified wallet is checked before signing",Icons.Outlined.AccountBalanceWallet)
            if(stage==ContributorClaimStage.CLAIMED) {
                NowStatusChip("Claim confirmed",NowStatusTone.LIVE,modifier=Modifier.testTag("claim-confirmed"))
                uiState.claim?.claimDeadline?.let { Text("Capture before ${displayEventTime(it)}",style=NowType.LabelL,color=NowColors.AgingText) }
            }
            uiState.claimDurationSeconds?.let { Text("Claim window: ${it / 60} min ${it % 60} sec",style=NowType.BodyS,color=NowColors.Ink600) }
        }
        uiState.message?.let { NowNotice(it,tone=if(stage==ContributorClaimStage.ERROR) NowNoticeTone.WARNING else NowNoticeTone.INFO) }
        if(stage in setOf(ContributorClaimStage.LOADING,ContributorClaimStage.PREPARING,ContributorClaimStage.SUBMITTING)) LinearProgressIndicator(Modifier.fillMaxWidth(),color=NowColors.Blue600)
        if(stage==ContributorClaimStage.CONFIRMING) NowNotice("Your submitted claim is being reconciled. A second transaction will not be sent.")
    }
    if(requirements && opportunity!=null) AlertDialog(onDismissRequest={requirements=false},title={Text("Locked proof requirements")},text={Text("${opportunity.question}\n\n${opportunity.evidenceSummary.requiredWitnesses} required witnesses · up to ${opportunity.evidenceSummary.maxWitnesses}\n${opportunity.verificationClass.replace('_',' ')}\nPhoto: ${if(opportunity.evidenceSummary.mediaRequired) "required" else "not required"}\nVideo: ${if(opportunity.evidenceSummary.videoRequired) "3–15 seconds required" else "not required"}\nPrecise location: ${if(opportunity.evidenceSummary.locationRequired) "required" else "not required"}\nEvidence deadline: ${displayEventTime(opportunity.evidenceDeadline)}")},confirmButton={TextButton({requirements=false}) {Text("Understood")}})
}
