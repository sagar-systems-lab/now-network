package com.sagarsystemslab.nownetwork.feature.requester

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.feature.common.*
import com.sagarsystemslab.nownetwork.feature.state.formatStateValue

@Composable
fun RequesterFundingScreen(uiState:RequesterFundingUiState,onBack:()->Unit,onAmountChange:(String)->Unit,onPrepare:()->Unit,onSubmit:()->Unit,onCheck:()->Unit,onWallet: (() -> Unit)? = null) {
    val stage=uiState.stage
    val busy=stage in setOf(RequesterFundingStage.LOADING,RequesterFundingStage.PREPARING,RequesterFundingStage.SUBMITTING)
    val review=stage==RequesterFundingStage.REVIEW
    val amount=listOf(uiState.amountInput,uiState.rewardSymbol).filter { it.isNotBlank() }.joinToString(" ")
    TransactionPage(if(review) "Review refresh" else "Refresh this state","Fund fresh, verified information","screen-requester-funding",onBack,footer={
        when(stage) {
            RequesterFundingStage.SETUP -> NowPrimaryButton("Review refresh",onPrepare,Modifier.fillMaxWidth().testTag("review-refresh-funding"),enabled=uiState.rewardConfigured)
            RequesterFundingStage.REVIEW -> NowPrimaryButton("Fund refresh",onSubmit,Modifier.fillMaxWidth().testTag("fund-refresh"))
            RequesterFundingStage.CONFIRMING -> NowPrimaryButton("Check existing funding",onCheck,Modifier.fillMaxWidth())
            RequesterFundingStage.COMPLETE -> NowPrimaryButton("Done",onBack,Modifier.fillMaxWidth())
            else -> NowPrimaryButton(if(stage==RequesterFundingStage.SUBMITTING) "Waiting for wallet…" else "Preparing…",{},Modifier.fillMaxWidth(),enabled=false)
        }
        if(review) Text("Review the exact amount and network in your wallet.",style=NowType.BodyS,color=NowColors.Ink600)
    }) {
        val detail=uiState.stateDetail
        RefreshContextCard(uiState.title,detail?.location?.name ?: "Selected state",detail?.let { formatStateValue(it.valueJson,it.unitCode) })
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=NowColors.Blue600)
        uiState.notice?.let { NowNotice(it,tone=if(stage==RequesterFundingStage.COMPLETE) NowNoticeTone.SUCCESS else NowNoticeTone.INFO) }
        if(stage==RequesterFundingStage.SETUP) {
            NowSectionTitle("Select a reward amount","The pool pays contributors whose proof is accepted.")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("0.10","0.25","0.50").forEachIndexed { index,value ->
                    val selected=uiState.amountInput.toBigDecimalOrNull()==value.toBigDecimal()
                    Surface(onClick={onAmountChange(value)},enabled=uiState.rewardConfigured,shape=NowShapes.large,color=if(selected) NowColors.InfoSoft else NowColors.SurfacePrimary,border=BorderStroke(if(selected) 2.dp else 1.dp,if(selected) NowColors.Blue600 else NowColors.BorderSubtle),modifier=Modifier.weight(1f)) {
                        Column(Modifier.padding(vertical=14.dp,horizontal=6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Icon(if(selected) Icons.Outlined.CheckCircle else Icons.Outlined.Toll,null,tint=NowColors.Blue600)
                            Text("$value ${uiState.rewardSymbol}",style=NowType.LabelM,color=NowColors.Ink950)
                            Text(listOf("Standard","Recommended","Priority")[index],style=NowType.BodyS,color=NowColors.Ink600)
                        }
                    }
                }
            }
            NowTextField(uiState.amountInput,onAmountChange,"Custom reward (${uiState.rewardSymbol})",modifier=Modifier.testTag("funding-amount"),supportingText=uiState.amountError ?: "This is the total pool, shared according to the witness policy.",enabled=uiState.rewardConfigured,isError=uiState.amountError!=null)
        }
        NowGlassCard(emphasized=true,modifier=Modifier.testTag(if(review) "funding-review" else if(stage==RequesterFundingStage.COMPLETE) "funding-confirmed" else "funding-policy")) {
            ExperienceRow(if(stage==RequesterFundingStage.COMPLETE) "Refresh funded" else "Reward is paid only after verification",if(stage==RequesterFundingStage.COMPLETE) "Funding is confirmed. Contributors can find this refresh once it becomes available." else "Fresh photo, location and answer requirements stay locked to this refresh.",if(stage==RequesterFundingStage.COMPLETE) Icons.Outlined.Verified else Icons.Outlined.Shield)
        }
        NowSectionTitle("Payment method")
        NowGlassCard {
            ExperienceRow("Solana wallet",uiState.walletAddress?.let { it.take(6)+"…"+it.takeLast(4) } ?: "Connect your wallet when you continue",Icons.Outlined.AccountBalanceWallet,onWallet)
        }
        NowGlassCard {
            NowSectionTitle("Transaction breakdown")
            ExperienceRow("Reward pool",amount.ifBlank { "Choose an amount" },Icons.Outlined.Toll)
            ExperienceRow("Network",uiState.network.ifBlank { "Not configured" },Icons.Outlined.Hub)
            ExperienceRow("Network fee","The wallet shows the actual fee before you approve",Icons.Outlined.Info)
            if(stage==RequesterFundingStage.CONFIRMING) NowNotice("The submitted operation is being reconciled. Do not submit another funding transaction.",tone=NowNoticeTone.WARNING)
            if(!uiState.rewardConfigured) NowNotice("Configure the reward token before funding a refresh.",tone=NowNoticeTone.WARNING)
        }
    }
}
