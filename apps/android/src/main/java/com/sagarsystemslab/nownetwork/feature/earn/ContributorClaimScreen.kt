package com.sagarsystemslab.nownetwork.feature.earn

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.experience.displayDistance
import com.sagarsystemslab.nownetwork.feature.common.*

@Composable
fun ContributorClaimScreen(uiState:ContributorClaimUiState,rewardText:String,onBack:()->Unit,onPrepare:()->Unit,onSubmit:()->Unit,onCheck:()->Unit,onCaptureEvidence:()->Unit,onViewVerification:()->Unit = onCheck,estimatedRewardText:String? = null) {
    val stage=uiState.stage; val opportunity=uiState.opportunity
    val context = LocalContext.current
    var requirements by remember { mutableStateOf(false) }
    fun directions() {
        val target = opportunity?.location?.center ?: return
        val label = android.net.Uri.encode(opportunity.location.name)
        val uri = android.net.Uri.parse("geo:${target.latitude},${target.longitude}?q=${target.latitude},${target.longitude}($label)")
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
    }
    TransactionPage("Start this task","Check the exact place, claim it, then capture fresh proof","screen-contributor-claim",onBack,footer={
        when(stage) {
            ContributorClaimStage.REVIEW -> NowPrimaryButton("Claim this task",onPrepare,Modifier.fillMaxWidth().testTag("prepare-claim"),enabled=uiState.canPrepare)
            ContributorClaimStage.READY_FOR_WALLET -> NowPrimaryButton("Confirm claim in wallet",onSubmit,Modifier.fillMaxWidth().testTag("confirm-claim-wallet"))
            ContributorClaimStage.CLAIMED -> NowPrimaryButton("Start proof capture",onCaptureEvidence,Modifier.fillMaxWidth().testTag("capture-evidence"))
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
            NowGlassCard(emphasized=true) {
                NowSectionTitle("1 · Go to this exact place", opportunity.location.name)
                Text(
                    opportunity.location.displayAddress?.takeIf { it.isNotBlank() } ?: "Use the pin below as the exact destination.",
                    style = NowType.BodyM,
                    color = NowColors.Ink700,
                )
                opportunity.location.center?.let { target ->
                    LiveMapCard(
                        center = target,
                        pins = listOf(LiveMapPin(opportunity.refreshId, opportunity.location.name, target, "CLAIMABLE")),
                        onPin = null,
                        initialZoom = 16.4,
                        instruction = "Satellite view · confirm the exact building, gate or parking area.",
                    )
                    NowPrimaryButton("Open directions in Maps", ::directions, Modifier.fillMaxWidth())
                }
                Text(
                    "Do not claim from the wrong place. Reach this pin and finish the proof before the claim deadline.",
                    style = NowType.BodyS,
                    color = NowColors.Ink600,
                )
            }
            NowGlassCard(emphasized=true,modifier=Modifier.testTag("opportunity-detail")) {
                MetricStrip(listOf((estimatedRewardText ?: rewardText) to (if (estimatedRewardText == null) "Total reward pool" else "Estimated payout"),displayDistance(opportunity.distanceM) to "Away"), framed = false)
                NowStatusChip(if(opportunity.availability.claimable) "Ready to claim" else "Check availability",NowStatusTone.INFO)
                Text("Expires ${displayEventTime(opportunity.expiresAt)} · ${opportunity.availability.remainingSlots} slots remaining",style=NowType.BodyS,color=NowColors.Ink600)
                Text(if (estimatedRewardText == null) "Your allocation depends on accepted witnesses. This amount is the whole pool." else "Total pool: $rewardText · Estimate follows the locked witness rule. Payment requires accepted proof and final settlement.",style=NowType.BodyS,color=NowColors.Ink600)
            }
            NowGlassCard {
                ExperienceRow("2 · Proof you must capture","Check this before you claim",Icons.Outlined.Assignment,{requirements=true})
                ProofSteps(opportunity.evidenceSummary.mediaRequired,opportunity.evidenceSummary.locationRequired,when(opportunity.stateType.uppercase()) { "NUMERIC" -> "Count what you see"; "BINARY" -> "Answer yes or no"; else -> "Show the condition" },video=opportunity.evidenceSummary.videoRequired)
            }
            NowGlassCard {
                Text("3 simple steps", style = NowType.TitleS, color = NowColors.Ink950)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Check place", "Claim", "Capture").forEachIndexed { index, label ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            NowStatusChip("${index + 1}", NowStatusTone.INFO, accessibilityLabel = "Step ${index + 1}")
                            Text(label, style = NowType.LabelL, color = NowColors.Ink800)
                        }
                    }
                }
                Text("First confirm the destination. Then approve the claim in your wallet. Finally capture the required proof at the pin.", style = NowType.BodyS, color = NowColors.Ink600)
            }
        }
        NowGlassCard {
            ExperienceRow("Payout wallet",uiState.walletAddress ?: "Your verified wallet is checked before you sign",Icons.Outlined.AccountBalanceWallet)
            if(stage==ContributorClaimStage.CLAIMED) {
                NowStatusChip("Claim confirmed",NowStatusTone.LIVE,modifier=Modifier.testTag("claim-confirmed"))
                uiState.claim?.claimDeadline?.let { Text("Capture before ${displayEventTime(it)}",style=NowType.LabelL,color=NowColors.AgingText) }
            }
            uiState.claimDurationSeconds?.let { Text("Claim window: ${it / 60} min ${it % 60} sec",style=NowType.BodyS,color=NowColors.Ink600) }
        }
        uiState.message?.let { NowNotice(it,tone=if(stage==ContributorClaimStage.ERROR) NowNoticeTone.WARNING else NowNoticeTone.INFO) }
        if(stage in setOf(ContributorClaimStage.LOADING,ContributorClaimStage.PREPARING,ContributorClaimStage.SUBMITTING)) LinearProgressIndicator(Modifier.fillMaxWidth(),color=NowColors.Blue600)
        if(stage==ContributorClaimStage.CONFIRMING) NowNotice("Checking whether the wallet actually submitted the claim. If no transaction exists, retry unlocks automatically after 30 seconds.")
    }
    if(requirements && opportunity!=null) AlertDialog(onDismissRequest={requirements=false},title={Text("Locked proof requirements")},text={Text("${opportunity.question}\n\n${opportunity.evidenceSummary.requiredWitnesses} required witnesses · up to ${opportunity.evidenceSummary.maxWitnesses}\n${opportunity.verificationClass.replace('_',' ')}\nPhoto: ${if(opportunity.evidenceSummary.mediaRequired) "required" else "not required"}\nVideo: ${if(opportunity.evidenceSummary.videoRequired) "3–15 seconds required" else "not required"}\nPrecise location: ${if(opportunity.evidenceSummary.locationRequired) "required" else "not required"}\nEvidence deadline: ${displayEventTime(opportunity.evidenceDeadline)}")},confirmButton={TextButton({requirements=false}) {Text("Understood")}})
}
