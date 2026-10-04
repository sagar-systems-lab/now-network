package com.sagarsystemslab.nownetwork.feature.activity

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.experience.*
import com.sagarsystemslab.nownetwork.feature.common.*
import kotlinx.serialization.json.JsonObject

/** Server history survives reinstall; local unsettled operations remain recoverable below it. */
@Composable
fun AccountActivityScreen(
    state: ExperienceUiState, local: ActivityUiState, viewModel: ExperienceViewModel,
    area: String, onArea: () -> Unit, onNotifications: () -> Unit, onProfile: () -> Unit,
    onHelp: () -> Unit, onOpen: (JsonObject) -> Unit, onLocalPayment: (String) -> Unit,
    onLocalClaim: (String) -> Unit, onLocalFunding: (String) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    val rows = state.activity.rows()
    val finished = setOf("COMPLETED", "REFUNDED", "CANCELLED", "EXPIRED", "REJECTED", "FAILED")
    val visible = rows.filter { row -> row.text("title").contains(query, ignoreCase = true) &&
        (filter == "All" || (row.text("status") in finished) == (filter == "Completed")) }
    val serverIds = rows.map { it.text("refresh_id") }.toSet()
    val pending = (local.active + local.completed.filter { it.type == "CONTRIBUTOR_CLAIM" && it.remoteState in setOf("CLAIMED", "CAPTURE_ACTIVE", "EVIDENCE_COMMITTED") }).filter { it.entityId !in serverIds }.distinctBy { it.entityId }
    val current = visible.firstOrNull { it.text("status") !in finished }
    val recent = visible.filter { it.text("refresh_id") != current?.text("refresh_id") }
    val motionDuration = nowMotionDuration(rememberNowMotionEnabled(), 180)
    LazyColumn(Modifier.testTag("screen-activity"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ExperienceHeader("ACTIVITY", "Your refreshes, proofs & earnings", area, onArea, onNotifications, onProfile, state.inbox.number("unread_count")) }
        item { MetricStrip(listOf((if (state.activity.isEmpty()) "—" else rows.count { it.text("status") !in finished }.toString()) to "In progress shown", (if (state.activity.isEmpty()) "—" else rows.count { it.text("status") in finished }.toString()) to "Completed shown")) }
        if (rows.isNotEmpty()) {
            item { NowTextField(query, { query = it }, "Search your activity") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("All", "In progress", "Completed").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) } } }
        }
        if (current != null) item(key = "current-${current.text("refresh_id")}") {
            ActivityEntry(current, viewModel, { onOpen(current) }, featured = true)
        }
        if (recent.isNotEmpty()) item { NowSectionTitle("Recent activity", "Your refreshes and proof history") }
        items(recent, key = { it.text("refresh_id") }) { row ->
            Box(Modifier.animateItem(fadeInSpec = tween(motionDuration), fadeOutSpec = tween(motionDuration), placementSpec = tween(motionDuration))) {
                ActivityEntry(row, viewModel, { onOpen(row) })
            }
        }
        // Locally submitted settlement can exist before the server history catches up.
        items(pending, key = { "local-${it.operationId}" }) { item ->
            NowGlassCard {
                val action: () -> Unit = { when (item.type) {
                    "CONTRIBUTOR_CLAIM" -> onLocalClaim(item.entityId)
                    "REFRESH_FUNDING" -> onLocalFunding(item.entityId)
                    else -> onLocalPayment(item.entityId)
                } }
                ExperienceRow(if (item.type == "CONTRIBUTOR_CLAIM") "Saved proof operation" else if (item.type == "REFRESH_FUNDING") "Funding awaiting sync" else "Payment awaiting sync", "Open to reconcile your saved operation", Icons.Outlined.Sync, action)
            }
        }
        if (rows.isEmpty() && pending.isEmpty() && !state.loading && !state.saving) item {
            EmptyProofCard(if (state.error == null) "Your activity starts here" else "Your activity, in one place",
                if (state.error == null) "Fund a refresh or capture fresh proof. Track every step and finalized receipt here." else "Your history couldn't load. Reconnect to see your proof, payments and finalized receipts.",
                onArea, onHelp, activity = true)
        }
        item {
            NowGlassCard(spacing = 8.dp) {
                Text("Activity types you'll see", style = NowType.TitleS, color = NowColors.Ink950)
                val types = listOf(Icons.Outlined.Payments to "Funding", Icons.Outlined.WorkOutline to "Claim", Icons.Outlined.CameraAlt to "Evidence", Icons.Outlined.Verified to "Verification", Icons.Outlined.AccountBalanceWallet to "Settlement", Icons.Outlined.ReceiptLong to "Receipt")
                types.chunked(3).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { (icon, label) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LuminousIcon(icon, Modifier.size(32.dp))
                                Text(label, style = NowType.LabelM, color = NowColors.Ink600)
                            }
                        }
                    }
                }
            }
        }
        item { ExperienceFeedback(state, viewModel) }
        if (rows.isNotEmpty() && visible.isEmpty()) item { NowNotice("No activity matches this filter.") }
        if (state.activity.text("next_offset").isNotBlank()) item { NowSecondaryButton("Load more activity", { viewModel.activity(more = true) }, Modifier.fillMaxWidth(), enabled = !state.saving) }
        item { NowSecondaryButton("Refresh activity", { viewModel.activity() }, Modifier.fillMaxWidth(), enabled = !state.saving) }
    }
}

@Composable
private fun ActivityEntry(row: JsonObject, viewModel: ExperienceViewModel, onOpen: () -> Unit, featured: Boolean = false) {
    val final = row.text("receipt_id").isNotBlank()
    val paid = row.text("payout_atomic").takeIf { it.isNotBlank() && it != "0" }
    val failed = row.text("status") in setOf("CANCELLED", "EXPIRED", "REJECTED", "FAILED")
    NowGlassCard(emphasized = featured, spacing = 8.dp) {
        if (featured) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NowStatusChip("CURRENT", NowStatusTone.INFO)
            Spacer(Modifier.weight(1f))
            Text("${row.text("role").lowercase().replaceFirstChar { it.uppercase() }} activity", style = NowType.BodyS, color = NowColors.Ink600)
        }
        ExperienceRow(row.text("title"), row.text("role").lowercase().replaceFirstChar { it.uppercase() },
            if (final) Icons.Outlined.ReceiptLong else Icons.Outlined.PendingActions, onOpen)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NowStatusChip(row.text("status").replace('_', ' '), if (failed) NowStatusTone.STALE else if (final) NowStatusTone.LIVE else NowStatusTone.INFO)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paid != null) Text(viewModel.amount(paid, row.text("reward_mint")), Modifier.weight(1f), style = NowType.LabelL, color = NowColors.LiveText)
                else Spacer(Modifier.weight(1f))
                if (row.text("updated_at").isNotBlank()) Text(displayEventTime(row.text("updated_at")), style = NowType.BodyS, color = NowColors.Ink500)
            }
        }
        if (featured) Text(when {
            final -> "Final receipt available"
            row.text("claim_status") == "CLAIMED" -> "Your claim is ready. Continue to capture fresh proof."
            row.text("payment_status").isNotBlank() -> "Settlement is being tracked. Open to check its status."
            else -> "Open to see the latest progress and next action."
        }, style = NowType.BodyS, color = NowColors.Ink600)
        if (featured) NowPrimaryButton("Open current activity", onOpen, Modifier.fillMaxWidth())
    }
}
