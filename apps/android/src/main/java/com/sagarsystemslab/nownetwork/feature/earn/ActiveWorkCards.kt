package com.sagarsystemslab.nownetwork.feature.earn

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.experience.*
import com.sagarsystemslab.nownetwork.feature.activity.ActivityUiState
import kotlinx.serialization.json.JsonObject

@Composable
internal fun ActiveWorkCards(state: ExperienceUiState, local: ActivityUiState, onOpen: (JsonObject) -> Unit, onLocalClaim: (String) -> Unit, onActivity: () -> Unit) {
    val rows = state.activeWork.rows()
    val remoteIds = (rows + state.activity.rows()).map { it.text("refresh_id") }.toSet()
    val saved = (local.active + local.completed).filter { it.type == "CONTRIBUTOR_CLAIM" && it.entityId !in remoteIds &&
        (it.active || it.remoteState in setOf("CLAIMED", "CAPTURE_ACTIVE", "EVIDENCE_COMMITTED")) }.distinctBy { it.entityId }
    if (rows.isEmpty() && saved.isEmpty()) {
        if (state.error != null) NowGlassCard {
            NowNotice("Account activity could not refresh. Open Activity to retry your saved work.")
            NowSecondaryButton("Open activity", onActivity, Modifier.fillMaxWidth())
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NowSectionTitle("Your active proof", "Saved work stays available across browse areas")
        rows.take(5).forEach { row ->
            val action = when {
                row.text("payment_status").isNotBlank() && row.text("payment_status") != "NOT_STARTED" -> "Track payment"
                row.text("claim_status") in setOf("CLAIMED", "CAPTURE_ACTIVE") -> "Continue proof"
                row.text("claim_status") in setOf("PREPARING", "WALLET_PENDING", "SUBMITTED", "CONFIRMING", "UNKNOWN") -> "Check existing claim"
                else -> "View verification"
            }
            NowGlassCard(emphasized = true) {
                Text(row.text("title"), style = NowType.TitleM, color = NowColors.Ink950)
                NowStatusChip(row.text("claim_status").ifBlank { row.text("status") }.replace('_', ' '), NowStatusTone.INFO)
                NowPrimaryButton(action, { onOpen(row) }, Modifier.fillMaxWidth())
            }
        }
        saved.take(5).forEach { item ->
            NowGlassCard {
                Text("Saved proof operation", style = NowType.TitleS, color = NowColors.Ink950)
                Text("Reconnect to confirm its latest status. Your operation identity is preserved.", style = NowType.BodyS, color = NowColors.Ink600)
                NowSecondaryButton("Resume saved claim", { onLocalClaim(item.entityId) }, Modifier.fillMaxWidth())
            }
        }
        NowSecondaryButton("View all activity", onActivity, Modifier.fillMaxWidth())
    }
}
