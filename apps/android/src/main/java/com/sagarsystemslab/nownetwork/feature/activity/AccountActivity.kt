package com.sagarsystemslab.nownetwork.feature.activity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
    LazyColumn(Modifier.testTag("screen-activity"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ExperienceHeader("ACTIVITY", "Your refreshes, proofs & earnings", area, onArea, onNotifications, onProfile, state.inbox.number("unread_count")) }
        item { MetricStrip(listOf((if (state.activity.isEmpty()) "—" else rows.count { it.text("status") !in finished }.toString()) to "In progress shown", (if (state.activity.isEmpty()) "—" else rows.count { it.text("status") in finished }.toString()) to "Completed shown")) }
        item { ExperienceFeedback(state, viewModel) }
        if (rows.isNotEmpty()) {
            item { NowTextField(query, { query = it }, "Search your activity") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("All", "In progress", "Completed").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) } } }
        }
        items(visible, key = { it.text("refresh_id") }) { row ->
            val final = row.text("receipt_id").isNotBlank()
            val paid = row.text("payout_atomic").takeIf { it.isNotBlank() && it != "0" }
            NowGlassCard(emphasized = !final) {
                ExperienceRow(row.text("title"), row.text("role").lowercase().replaceFirstChar { it.uppercase() },
                    if (final) Icons.Outlined.ReceiptLong else Icons.Outlined.PendingActions, { onOpen(row) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    NowStatusChip(row.text("status").replace('_', ' '), if (final) NowStatusTone.LIVE else NowStatusTone.INFO)
                    if (paid != null) Text(viewModel.amount(paid, row.text("reward_mint")), style = NowType.LabelL, color = NowColors.LiveText)
                }
                Text(when {
                    final -> "Final receipt available"
                    row.text("claim_status") == "CLAIMED" -> "Your claim is ready. Continue to capture fresh proof."
                    row.text("payment_status").isNotBlank() -> "Settlement is being tracked. Open to check its status."
                    else -> "Open to see the latest progress and next action."
                }, style = NowType.BodyS, color = NowColors.Ink600)
                NowSecondaryButton(if (final) "View receipt" else "Continue", { onOpen(row) }, Modifier.fillMaxWidth())
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
        if (rows.isEmpty() && pending.isEmpty() && state.activity.isNotEmpty() && !state.loading && !state.saving && state.error == null) item {
            EmptyProofCard("Your activity starts here", "Fund a refresh or capture fresh proof. Track every step and finalized receipt here.", onArea, onHelp, activity = true)
        }
        if (rows.isNotEmpty() && visible.isEmpty()) item { NowNotice("No activity matches this filter.") }
        if (state.activity.text("next_offset").isNotBlank()) item { NowSecondaryButton("Load more activity", { viewModel.activity(more = true) }, Modifier.fillMaxWidth(), enabled = !state.saving) }
        item { NowSecondaryButton("Refresh activity", { viewModel.activity() }, Modifier.fillMaxWidth(), enabled = !state.saving) }
    }
}
