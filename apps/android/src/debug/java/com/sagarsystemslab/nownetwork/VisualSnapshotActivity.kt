package com.sagarsystemslab.nownetwork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.experience.*
import com.sagarsystemslab.nownetwork.feature.activity.AccountActivityScreen
import com.sagarsystemslab.nownetwork.feature.activity.ActivityUiState
import com.sagarsystemslab.nownetwork.feature.requester.RequesterFundingStage
import com.sagarsystemslab.nownetwork.navigation.TopLevelDestination
import com.sagarsystemslab.nownetwork.network.MeDto
import com.sagarsystemslab.nownetwork.network.WalletBindingDto
import com.sagarsystemslab.nownetwork.wallet.WalletInteractionHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.serialization.json.*

/** Debug-only, read-only fixtures rendered by the production Compose screens. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@AndroidEntryPoint
class VisualSnapshotActivity : ComponentActivity() {
    private val experience: ExperienceViewModel by viewModels()
    @Inject lateinit var preferencesStore: UiPreferencesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screen = requireNotNull(intent.getStringExtra("screen"))
        val dark = intent.getBooleanExtra("dark", false)
        val motion = intent.getBooleanExtra("motion", false)
        setContent {
            ApplyNowSystemBars(this, dark)
            CompositionLocalProvider(LocalNowMotionAllowed provides motion) {
                NowTheme(darkTheme = dark) {
                    val selectedTab = when (screen) {
                        "now" -> TopLevelDestination.NOW
                        "earn", "earn-empty" -> TopLevelDestination.EARN
                        "activity", "activity-empty" -> TopLevelDestination.ACTIVITY
                        else -> null
                    }
                    Scaffold(
                        containerColor = NowColors.SurfaceCanvas,
                        contentColor = NowColors.Ink950,
                        contentWindowInsets = WindowInsets.safeDrawing,
                        bottomBar = {
                            if (selectedTab != null) NowBottomBar(TopLevelDestination.entries, { it == selectedTab }, {})
                        },
                    ) { insets ->
                        Box(Modifier.fillMaxSize().padding(insets)
                            .semantics { testTagsAsResourceId = true }.testTag("snapshot-$screen")
                            // Snapshot data must never trigger live account or wallet mutations.
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }) {
                            when (screen) {
                                "now" -> HomeScenario(dark, {})
                                "earn", "earn-empty" -> EarnScenario(empty = screen.endsWith("empty"))
                                "state" -> StateDetailScenario({})
                                "funding" -> RequesterRefreshScenario({})
                                "funding-review" -> RequesterRefreshScenario({}, initialStage = RequesterFundingStage.REVIEW)
                                "claim" -> OpportunityClaimScenario({})
                                "capture" -> ProofLoopScenario({}, captureOnly = true)
                                "verification" -> ProofLoopScenario({}, initialPhase = 1, captureOnly = true)
                                "verified" -> ProofLoopScenario({}, initialPhase = 2, captureOnly = true)
                                "payment" -> SettlementRecoveryScenario({}, initialPhase = 0, captureOnly = true)
                                "paid" -> SettlementRecoveryScenario({}, initialPhase = 1, captureOnly = true)
                                "receipt" -> SettlementRecoveryScenario({}, initialPhase = 2, captureOnly = true)
                                "activity", "activity-empty" -> AccountActivityScreen(
                                    state = if (screen.endsWith("empty")) fixture.copy(activity = json("""{"items":[]}""")) else fixture,
                                    local = ActivityUiState(), viewModel = experience, area = "San Francisco, CA",
                                    onArea = {}, onNotifications = {}, onProfile = {}, onHelp = {}, onOpen = {},
                                    onLocalPayment = {}, onLocalClaim = {}, onLocalFunding = {},
                                )
                                else -> ExperienceScreenContent(
                                    destination = ExperienceDestination.valueOf(screen.uppercase().replace('-', '_')),
                                    state = fixture, viewModel = experience,
                                    preferences = UiPreferences(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                                    uiPreferencesStore = preferencesStore, walletHost = object : WalletInteractionHost {},
                                    onBack = {}, navigate = {}, onNotification = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

private val fixture = ExperienceUiState(
    me = MeDto("visual-fixture", "ACTIVE", listOf(WalletBindingDto("fixture-wallet", "7qN4x2Kp6F9d8Wm3H1v5Jt8Yb2Lc4Rs6Ua9Ez3Pn5Qw7", "devnet", "ACTIVE", 1))),
    profile = json("""{"display_name":"Alex Morgan","verified_contributions":128,"completed_refreshes":42,"created_at":"2026-05-12T12:00:00Z"}"""),
    preferences = json("""{"payout_wallet_binding_id":"fixture-wallet","notifications":{"proof":true,"payments":true,"security":true,"opportunities":true,"quiet_enabled":false,"quiet_start":"22:00","quiet_end":"08:00","timezone":"America/Los_Angeles","area_ids":["sf"]}}"""),
    balances = mapOf("fixture-wallet" to ("0.024 SOL" to "2.40 USDC")),
    inbox = json("""{"unread_count":2,"items":[{"notification_id":"n1","title":"Your proof was verified","body":"Sector 7 parking has a fresh observation.","category":"proof","created_at":"2026-10-03T12:00:00Z"},{"notification_id":"n2","title":"Reward settled","body":"Your finalized receipt is ready.","category":"payments","created_at":"2026-10-03T11:58:00Z"}]}"""),
    activity = json("""{"items":[{"refresh_id":"parking-fixture","title":"Parking refresh · Sector 7","role":"CONTRIBUTOR","status":"VERIFYING","claim_status":"EVIDENCE_COMMITTED"},{"refresh_id":"gate-fixture","title":"Gate status · Civic Center","role":"CONTRIBUTOR","status":"COMPLETED","receipt_id":"receipt-fixture","payout_atomic":"300000","reward_mint":"USDC"}]}"""),
    areas = listOf(json("""{"area_id":"sf","name":"San Francisco","display_address":"California, United States","total":12,"live":8,"aging":1,"stale":2,"unobserved":1,"center":{"latitude":37.7749,"longitude":-122.4194},"radius_m":3000}""")),
    installations = listOf(json("""{"installation_id":"fixture-device","platform":"android","device_name":"Pixel 9","last_seen_at":"2026-10-03T12:00:00Z","current":true}""")),
)
