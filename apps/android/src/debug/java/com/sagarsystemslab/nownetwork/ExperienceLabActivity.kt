package com.sagarsystemslab.nownetwork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.NowColors
import com.sagarsystemslab.nownetwork.designsystem.NowNotice
import com.sagarsystemslab.nownetwork.designsystem.NowNoticeTone
import com.sagarsystemslab.nownetwork.designsystem.NowPrimaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSecondaryButton
import com.sagarsystemslab.nownetwork.designsystem.NowSpacing
import com.sagarsystemslab.nownetwork.designsystem.NowStatusChip
import com.sagarsystemslab.nownetwork.designsystem.NowStatusTone
import com.sagarsystemslab.nownetwork.designsystem.NowTextField
import com.sagarsystemslab.nownetwork.designsystem.NowTheme
import com.sagarsystemslab.nownetwork.designsystem.NowType
import com.sagarsystemslab.nownetwork.designsystem.nowLivePulse
import com.sagarsystemslab.nownetwork.designsystem.nowPulseOnChange
import com.sagarsystemslab.nownetwork.designsystem.rememberNowMotionEnabled
import com.sagarsystemslab.nownetwork.feature.capture.EvidenceCaptureScreen
import com.sagarsystemslab.nownetwork.feature.capture.EvidenceCaptureStage
import com.sagarsystemslab.nownetwork.feature.capture.EvidenceCaptureUiState
import com.sagarsystemslab.nownetwork.feature.earn.ContributorClaimScreen
import com.sagarsystemslab.nownetwork.feature.earn.ContributorClaimStage
import com.sagarsystemslab.nownetwork.feature.earn.ContributorClaimUiState
import com.sagarsystemslab.nownetwork.feature.earn.EarnNotice
import com.sagarsystemslab.nownetwork.feature.earn.EarnScreen
import com.sagarsystemslab.nownetwork.feature.earn.EarnUiState
import com.sagarsystemslab.nownetwork.feature.activity.ActivityScreen
import com.sagarsystemslab.nownetwork.feature.activity.ActivityUiState
import com.sagarsystemslab.nownetwork.feature.home.NowScreen
import com.sagarsystemslab.nownetwork.feature.payment.PaymentScreen
import com.sagarsystemslab.nownetwork.feature.payment.PaymentStage
import com.sagarsystemslab.nownetwork.feature.payment.PaymentUiState
import com.sagarsystemslab.nownetwork.feature.receipt.ReceiptScreen
import com.sagarsystemslab.nownetwork.feature.receipt.ReceiptStage
import com.sagarsystemslab.nownetwork.feature.receipt.ReceiptUiState
import com.sagarsystemslab.nownetwork.feature.settings.SettingsScreen
import com.sagarsystemslab.nownetwork.feature.requester.RequesterFundingScreen
import com.sagarsystemslab.nownetwork.feature.requester.RequesterFundingStage
import com.sagarsystemslab.nownetwork.feature.requester.RequesterFundingUiState
import com.sagarsystemslab.nownetwork.feature.state.BrowseNotice
import com.sagarsystemslab.nownetwork.feature.state.HomeUiState
import com.sagarsystemslab.nownetwork.feature.state.StateDetailScreen
import com.sagarsystemslab.nownetwork.feature.state.StateDetailUiState
import com.sagarsystemslab.nownetwork.feature.verification.VerificationScreen
import com.sagarsystemslab.nownetwork.feature.verification.VerificationStage
import com.sagarsystemslab.nownetwork.feature.verification.VerificationUiState
import com.sagarsystemslab.nownetwork.model.ActivityItem
import com.sagarsystemslab.nownetwork.model.OpportunitySummary
import com.sagarsystemslab.nownetwork.network.ClaimStatusDto
import com.sagarsystemslab.nownetwork.network.OpportunityAvailabilityDto
import com.sagarsystemslab.nownetwork.network.OpportunityDto
import com.sagarsystemslab.nownetwork.network.OpportunityEvidenceSummaryDto
import com.sagarsystemslab.nownetwork.network.OpportunityLocationDto
import com.sagarsystemslab.nownetwork.network.OpportunityRewardDto
import com.sagarsystemslab.nownetwork.model.StateDetail
import com.sagarsystemslab.nownetwork.model.StateLocation
import com.sagarsystemslab.nownetwork.model.StateSummary
import com.sagarsystemslab.nownetwork.model.StateVerification
import com.sagarsystemslab.nownetwork.repository.FinalReceipt
import java.io.File
import kotlinx.serialization.json.JsonPrimitive

class ExperienceLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialDarkTheme = NowThemePreferenceStore.readDarkTheme(this)

        setContent {
            var darkTheme by rememberSaveable {
                mutableStateOf(initialDarkTheme)
            }

            ApplyNowSystemBars(
                activity = this@ExperienceLabActivity,
                darkTheme = darkTheme,
            )

            NowTheme(darkTheme = darkTheme) {
                ExperienceLab(
                    darkTheme = darkTheme,
                    onDarkThemeChange = { enabled ->
                        darkTheme = enabled
                        NowThemePreferenceStore.writeDarkTheme(
                            context = this@ExperienceLabActivity,
                            enabled = enabled,
                        )
                    },
                )
            }
        }
    }
}

private enum class LabAvailability {
    READY,
    UPCOMING,
}

private data class LabScenario(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val availability: LabAvailability,
)

private val scenarios = listOf(
    LabScenario(
        id = "shell",
        title = "App shell & tabs",
        description = "Exercise the three-destination navigation contract and selected states.",
        icon = Icons.Outlined.TouchApp,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "theme",
        title = "Light / dark theme",
        description = "Theme tokens, semantic surfaces, status colors, and manual preference.",
        icon = Icons.Outlined.DarkMode,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "components",
        title = "Design primitives",
        description = "Buttons, status chips, notices, and inputs used by production screens.",
        icon = Icons.Outlined.Science,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "motion",
        title = "Motion & liveness",
        description = "State transitions, value emphasis, LIVE pulse, and reduced-motion behavior.",
        icon = Icons.Outlined.TouchApp,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "home",
        title = "NOW / Home",
        description = "Live, aging, stale, conflict, loading, empty, and offline home states.",
        icon = Icons.Outlined.Home,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "state-detail",
        title = "State detail",
        description = "Value, freshness, location, verification, refresh action, and technical disclosure.",
        icon = Icons.Outlined.Visibility,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "requester-refresh",
        title = "ASK / Refresh funding",
        description = "Reward, review, wallet handoff, reconciliation, and confirmed refresh funding.",
        icon = Icons.Outlined.Payments,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "earn",
        title = "EARN opportunities",
        description = "Nearby rewards, task requirements, deadlines, slots, and live availability.",
        icon = Icons.Outlined.WorkOutline,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "opportunity-claim",
        title = "Opportunity & claim",
        description = "Task detail, wallet review, reconciliation, claim confirmation, and evidence handoff.",
        icon = Icons.Outlined.WorkOutline,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "proof-loop",
        title = "Evidence → Verification → LIVE",
        description = "Fresh capture, evidence review, safe submission, verification, and live-state projection.",
        icon = Icons.Outlined.Visibility,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "settlement",
        title = "Settlement & recovery",
        description = "Payment reconciliation, PAID, final receipt, and Activity recovery.",
        icon = Icons.Outlined.Payments,
        availability = LabAvailability.READY,
    ),
    LabScenario(
        id = "settings",
        title = "Settings",
        description = "Appearance, privacy/permissions, version, and network context.",
        icon = Icons.Outlined.DarkMode,
        availability = LabAvailability.READY,
    ),
)

@Composable
private fun ExperienceLab(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
) {
    var selectedScenarioId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedScenario = scenarios.firstOrNull { it.id == selectedScenarioId }

    BackHandler(enabled = selectedScenario != null) {
        selectedScenarioId = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = NowColors.SurfaceCanvas,
        contentColor = NowColors.Ink950,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        if (selectedScenario == null) {
            LabCatalog(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onOpen = { scenario ->
                    selectedScenarioId = scenario.id
                },
            )
        } else {
            ScenarioScreen(
                scenario = selectedScenario,
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onBack = {
                    selectedScenarioId = null
                },
            )
        }
    }
}

@Composable
private fun LabCatalog(
    onOpen: (LabScenario) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = NowSpacing.PageHorizontal,
            top = NowSpacing.Space4,
            end = NowSpacing.PageHorizontal,
            bottom = NowSpacing.Space8,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = NowColors.Blue50,
                        border = BorderStroke(1.dp, NowColors.Blue100),
                    ) {
                        Text(
                            text = "DEBUG ONLY",
                            style = NowType.LabelM,
                            color = NowColors.Blue700,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    Text(
                        text = "Experience Lab",
                        style = NowType.TitleM,
                        color = NowColors.Ink950,
                    )
                }

                Text(
                    text = "Phone-first acceptance surface for visual, interaction, state, and motion checks.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
                Text(
                    text = "Production screens are connected here as they are finalized; the release app does not expose this launcher.",
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }
        }

        item {
            HorizontalDivider(color = NowColors.BorderSubtle)
        }

        items(
            items = scenarios,
            key = { it.id },
        ) { scenario ->
            ScenarioCard(
                scenario = scenario,
                onClick = {
                    onOpen(scenario)
                },
            )
        }
    }
}

@Composable
private fun ScenarioCard(
    scenario: LabScenario,
    onClick: () -> Unit,
) {
    val ready = scenario.availability == LabAvailability.READY

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = ready,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.large,
        color = NowColors.SurfacePrimary,
        border = BorderStroke(1.dp, NowColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NowSpacing.Space4),
            horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(10.dp),
                color = if (ready) NowColors.Blue50 else NowColors.Ink100,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = scenario.icon,
                        contentDescription = null,
                        tint = if (ready) NowColors.Blue600 else NowColors.Ink400,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = scenario.title,
                        style = NowType.TitleS,
                        color = if (ready) NowColors.Ink950 else NowColors.Ink600,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (ready) "READY" else "UPCOMING",
                        style = NowType.LabelM,
                        color = if (ready) NowColors.LiveText else NowColors.Ink500,
                    )
                }
                Text(
                    text = scenario.description,
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }
        }
    }
}

@Composable
private fun ScenarioScreen(
    scenario: LabScenario,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (scenario.id == "state-detail") {
        StateDetailScenario(
            onBack = onBack,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (scenario.id == "requester-refresh") {
        RequesterRefreshScenario(
            onBack = onBack,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (scenario.id == "opportunity-claim") {
        OpportunityClaimScenario(
            onBack = onBack,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (scenario.id == "proof-loop") {
        ProofLoopScenario(
            onBack = onBack,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (scenario.id == "settlement") {
        SettlementRecoveryScenario(
            onBack = onBack,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (scenario.id == "settings") {
        SettingsScreen(
            darkTheme = darkTheme,
            onDarkThemeChange = onDarkThemeChange,
            onBack = onBack,
        )
        return
    }

    Column(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = NowSpacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back to Experience Lab",
                    tint = NowColors.Ink700,
                )
            }
            Text(
                text = scenario.title,
                style = NowType.TitleM,
                color = NowColors.Ink950,
            )
        }

        HorizontalDivider(color = NowColors.BorderSubtle)

        when (scenario.id) {
            "shell" -> ShellScenario(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(NowSpacing.PageHorizontal),
            )

            "theme" -> ThemeScenario(
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(NowSpacing.PageHorizontal),
            )

            "components" -> DesignPrimitivesScenario(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(NowSpacing.PageHorizontal),
            )

            "motion" -> MotionScenario(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(NowSpacing.PageHorizontal),
            )

            "home" -> HomeScenario(
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange,
                modifier = Modifier.fillMaxSize(),
            )

            "earn" -> EarnScenario(
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SettlementRecoveryScenario(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowMillis = 1_800_000_000_000L
    var phase by rememberSaveable { mutableIntStateOf(0) }

    when (phase) {
        0 -> {
            Box(modifier = modifier) {
                PaymentScreen(
                    uiState = PaymentUiState(
                        stage = PaymentStage.VERIFYING,
                        refreshId = "lab-refresh",
                        settlementId = "lab-settlement",
                        settlementStatus = "SUBMITTED",
                        chainSignature = "LabSettlementSignature0123456789",
                        chainCommitment = "confirmed",
                        confirmedAt = "2035-01-01T00:11:00Z",
                        finalizedAt = null,
                        message = "NOW is checking Solana before allowing any retry. No action is needed yet.",
                    ),
                    onBack = onBack,
                    onRetry = {},
                    onViewReceipt = { phase = 2 },
                    onDone = { phase = 3 },
                )

                NowSecondaryButton(
                    text = "Lab · resolve PAID",
                    onClick = { phase = 1 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = NowSpacing.Space4,
                            end = NowSpacing.Space4,
                            bottom = NowSpacing.Space4,
                        )
                        .fillMaxWidth(),
                )
            }
        }

        1 -> {
            PaymentScreen(
                uiState = PaymentUiState(
                    stage = PaymentStage.PAID,
                    refreshId = "lab-refresh",
                    settlementId = "lab-settlement",
                    settlementStatus = "FINALIZED",
                    chainSignature = "LabSettlementSignature0123456789",
                    chainCommitment = "finalized",
                    confirmedAt = "2035-01-01T00:11:00Z",
                    finalizedAt = "2035-01-01T00:12:00Z",
                    message = "Payment is finalized on Solana.",
                ),
                onBack = { phase = 0 },
                onRetry = {},
                onViewReceipt = { phase = 2 },
                onDone = { phase = 3 },
            )
        }

        2 -> {
            ReceiptScreen(
                uiState = ReceiptUiState(
                    stage = ReceiptStage.READY,
                    refreshId = "lab-refresh",
                    receipt = FinalReceipt(
                        receiptId = "lab-receipt",
                        refreshId = "lab-refresh",
                        stateId = "lab-state",
                        verificationResultId = "lab-verification",
                        settlementId = "lab-settlement",
                        finalValue = JsonPrimitive(18),
                        observedAt = "2035-01-01T00:09:00Z",
                        verificationClass = "FRESH_PHOTO_LOCATION",
                        rewardAmountAtomic = "500000",
                        rewardMint = "USDC",
                        verificationDigest = "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcdabcd",
                        settlementOperationHash = "1234123412341234123412341234123412341234123412341234123412341234",
                        receiptDigest = "dcba" + "dcba".repeat(15),
                        settlementSignature = "LabSettlementSignature0123456789",
                        finalizedAt = "2035-01-01T00:12:00Z",
                        revision = 1L,
                    ),
                    message = "Final receipt is ready.",
                ),
                onBack = { phase = 1 },
                onRetry = {},
                onDone = { phase = 3 },
            )
        }

        else -> {
            ActivityScreen(
                uiState = ActivityUiState(
                    active = listOf(
                        ActivityItem(
                            operationId = "active-payment",
                            entityId = "lab-refresh-pending",
                            type = "SETTLEMENT",
                            localState = "VERIFYING",
                            remoteState = "VERIFYING",
                            updatedAtMillis = nowMillis - 45_000L,
                            active = true,
                        ),
                    ),
                    completed = listOf(
                        ActivityItem(
                            operationId = "paid-payment",
                            entityId = "lab-refresh",
                            type = "SETTLEMENT",
                            localState = "PAID",
                            remoteState = "PAID",
                            updatedAtMillis = nowMillis - 180_000L,
                            active = false,
                        ),
                    ),
                ),
                serverNowMillis = { nowMillis },
                onPaymentClick = { phase = 0 },
                onReceiptClick = { phase = 2 },
            )
        }
    }
}

@Composable
private fun ProofLoopScenario(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capturePath = remember {
        File(context.cacheDir, "now-lab-evidence.jpg").absolutePath
    }

    var phase by rememberSaveable { mutableIntStateOf(0) }
    var captureStage by rememberSaveable {
        mutableStateOf(EvidenceCaptureStage.READY)
    }
    var answer by rememberSaveable { mutableStateOf("18") }
    var locationSamples by rememberSaveable { mutableIntStateOf(0) }
    var labMessage by rememberSaveable { mutableStateOf<String?>(null) }

    if (phase == 0) {
        val captureState = EvidenceCaptureUiState(
            stage = captureStage,
            acceptanceId = "lab-acceptance",
            refreshId = "lab-refresh",
            evidenceId = "lab-evidence",
            question = "How many parking spaces are available right now?",
            stateType = "NUMERIC",
            mediaRequired = true,
            locationRequired = true,
            expiresAt = "2035-01-01T00:10:00Z",
            localFilePath = capturePath,
            answer = answer,
            locationSampleCount = locationSamples,
            message = labMessage,
            nextStep = if (captureStage == EvidenceCaptureStage.SUBMITTED) {
                "VERIFY_REFRESH"
            } else {
                null
            },
        )

        EvidenceCaptureScreen(
            uiState = captureState,
            onBack = onBack,
            onBeginCapture = {
                labMessage = null
                captureStage = EvidenceCaptureStage.CAMERA
            },
            onPermissionDenied = {
                labMessage = "Camera and precise location are required for this sample."
                captureStage = EvidenceCaptureStage.ERROR
            },
            onPhotoCaptured = {
                locationSamples = 1
                captureStage = EvidenceCaptureStage.REVIEW
            },
            onCameraError = {
                labMessage = "Camera preview could not continue."
                captureStage = EvidenceCaptureStage.ERROR
            },
            onAnswerChange = { answer = it },
            onRefreshLocation = {
                locationSamples = 1
                labMessage = "Fresh location sample ready."
            },
            onRecapture = {
                captureStage = EvidenceCaptureStage.CAMERA
            },
            onSubmit = {
                labMessage = "Proof committed in the lab acceptance flow."
                captureStage = EvidenceCaptureStage.SUBMITTED
            },
            onRetry = {
                captureStage = EvidenceCaptureStage.REVIEW
            },
            onContinueVerification = {
                phase = 1
            },
        )
        return
    }

    val verified = phase >= 2
    val verificationState = VerificationUiState(
        stage = if (verified) {
            VerificationStage.VERIFIED
        } else {
            VerificationStage.VERIFYING
        },
        refreshId = "lab-refresh",
        verificationResultId = if (verified) "lab-result" else null,
        evidenceCount = 1,
        evidenceSetRevision = 1,
        policyVersion = 1,
        reasonCodes = if (verified) {
            listOf("fresh_capture", "location_match", "request_match", "replay_check")
        } else {
            emptyList()
        },
        finalAnswer = if (verified) JsonPrimitive(18) else null,
        projectedValue = if (verified) JsonPrimitive(18) else null,
        projectedFreshness = if (verified) "LIVE" else null,
        projectedStateRevision = if (verified) 12L else null,
        projectionSuperseded = false,
        replayed = false,
        message = if (verified) {
            "Evidence verified and the live state projection is confirmed."
        } else {
            "Checking committed evidence against the verification policy…"
        },
    )

    Box(modifier = modifier) {
        VerificationScreen(
            uiState = verificationState,
            onBack = {
                if (phase > 0) {
                    phase = 0
                    captureStage = EvidenceCaptureStage.SUBMITTED
                } else {
                    onBack()
                }
            },
            onRetry = {
                phase = 1
            },
            onTrackPayment = {
                labMessage = "Payment surface is the next acceptance block."
            },
            onDone = onBack,
        )

        if (!verified) {
            NowSecondaryButton(
                text = "Lab · resolve VERIFIED",
                onClick = {
                    phase = 2
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = NowSpacing.Space4,
                        end = NowSpacing.Space4,
                        bottom = NowSpacing.Space4,
                    )
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OpportunityClaimScenario(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stage by rememberSaveable {
        mutableStateOf(ContributorClaimStage.REVIEW)
    }
    var lastAction by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val opportunity = remember {
        OpportunityDto(
            refreshId = "parking-refresh",
            stateId = "parking-sector-7",
            stateVersion = 1,
            title = "Sector 7 parking",
            question = "How many parking spaces are available right now?",
            stateType = "NUMERIC",
            unitCode = "spaces",
            location = OpportunityLocationDto(
                locationId = "sector-7-parking",
                name = "Sector 7 parking",
                locationType = "POINT",
                displayAddress = "Main market",
            ),
            reward = OpportunityRewardDto(
                mint = "USDC",
                poolAtomic = "500000",
                payoutRule = "equal_verified_witnesses",
            ),
            distanceM = 320.0,
            expiresAt = "2035-01-01T00:15:00Z",
            evidenceDeadline = "2035-01-01T00:10:00Z",
            verificationClass = "FRESH_PHOTO_LOCATION",
            evidenceSummary = OpportunityEvidenceSummaryDto(
                templateKey = sampleTemplateKey(),
                mediaRequired = true,
                locationRequired = true,
                requiredWitnesses = 1,
                maxWitnesses = 2,
            ),
            availability = OpportunityAvailabilityDto(
                claimable = true,
                activeClaims = 0,
                remainingSlots = 1,
            ),
            stateRevision = 11L,
            revision = 4L,
        )
    }

    val claim = if (stage == ContributorClaimStage.CLAIMED) {
        ClaimStatusDto(
            acceptanceId = "acceptance-demo",
            refreshId = "parking-refresh",
            status = "CLAIMED",
            claimSlot = 0,
            claimDurationSeconds = 300L,
            claimDeadline = "2035-01-01T00:05:00Z",
            chainSignature = null,
            chainStatus = "confirmed",
            refreshStatus = "CLAIMED",
            refreshExpiresAt = "2035-01-01T00:15:00Z",
            evidenceDeadline = "2035-01-01T00:10:00Z",
            revision = 2L,
            nextStep = "EVIDENCE_CHALLENGE",
        )
    } else {
        null
    }

    val uiState = ContributorClaimUiState(
        refreshId = "parking-refresh",
        opportunity = opportunity,
        stage = stage,
        walletAddress = if (
            stage == ContributorClaimStage.READY_FOR_WALLET ||
            stage == ContributorClaimStage.CONFIRMING ||
            stage == ContributorClaimStage.CLAIMED
        ) {
            "DemoWallet"
        } else {
            null
        },
        claimDurationSeconds = if (stage == ContributorClaimStage.REVIEW) null else 300L,
        claim = claim,
        message = if (stage == ContributorClaimStage.CONFIRMING) {
            "Transaction submitted. Waiting for authoritative confirmation."
        } else {
            null
        },
        canPrepare = stage == ContributorClaimStage.REVIEW,
    )

    Box(modifier = modifier) {
        ContributorClaimScreen(
            uiState = uiState,
            rewardText = "0.50 USDC",
            onBack = onBack,
            onPrepare = {
                stage = ContributorClaimStage.READY_FOR_WALLET
            },
            onSubmit = {
                stage = ContributorClaimStage.CONFIRMING
            },
            onCheck = {
                stage = ContributorClaimStage.CLAIMED
            },
            onCaptureEvidence = {
                lastAction = "Evidence capture invoked"
            },
        )

        lastAction?.let { action ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = NowSpacing.Space4,
                        end = NowSpacing.Space4,
                        bottom = NowSpacing.Space4,
                    ),
                shape = MaterialTheme.shapes.medium,
                color = NowColors.Ink950,
            ) {
                Text(
                    text = action,
                    style = NowType.BodyS,
                    color = NowColors.SurfacePrimary,
                    modifier = Modifier.padding(
                        horizontal = NowSpacing.Space3,
                        vertical = NowSpacing.Space2,
                    ),
                )
            }
        }
    }
}

@Composable
private fun EarnScenario(
    modifier: Modifier = Modifier,
) {
    val nowMillis = 1_800_000_000_000L
    var lastAction by rememberSaveable { mutableStateOf<String?>(null) }

    val sampleState = remember {
        EarnUiState(
            areaLabel = "Sector 7 · 3 km",
            opportunities = listOf(
                OpportunitySummary(
                    refreshId = "parking-refresh",
                    stateId = "parking-sector-7",
                    title = "Sector 7 parking",
                    question = "How many parking spaces are available right now?",
                    locationName = "Sector 7 parking",
                    displayAddress = "Main market",
                    rewardAtomic = "500000",
                    rewardMint = "USDC",
                    distanceMeters = 320.0,
                    expiresAtMillis = nowMillis + 900_000L,
                    evidenceDeadlineMillis = nowMillis + 600_000L,
                    verificationClass = "FRESH_PHOTO_LOCATION",
                    mediaRequired = true,
                    locationRequired = true,
                    claimable = true,
                    remainingSlots = 1,
                    revision = 4L,
                    cachedOnly = false,
                ),
                OpportunitySummary(
                    refreshId = "queue-refresh",
                    stateId = "gate-2-queue",
                    title = "Gate 2 queue",
                    question = "Is the entry queue longer than 10 people?",
                    locationName = "Gate 2",
                    displayAddress = "Sector 7",
                    rewardAtomic = "250000",
                    rewardMint = "USDC",
                    distanceMeters = 610.0,
                    expiresAtMillis = nowMillis + 1_200_000L,
                    evidenceDeadlineMillis = nowMillis + 900_000L,
                    verificationClass = "VISUAL_LOCATION",
                    mediaRequired = true,
                    locationRequired = true,
                    claimable = false,
                    remainingSlots = 0,
                    revision = 7L,
                    cachedOnly = false,
                ),
                OpportunitySummary(
                    refreshId = "elevator-refresh",
                    stateId = "elevator-gate-b",
                    title = "Elevator status",
                    question = "Is the Gate B elevator working right now?",
                    locationName = "Gate B",
                    displayAddress = "Sector 7",
                    rewardAtomic = "300000",
                    rewardMint = "USDC",
                    distanceMeters = 880.0,
                    expiresAtMillis = nowMillis + 1_500_000L,
                    evidenceDeadlineMillis = nowMillis + 1_100_000L,
                    verificationClass = "FRESH_PHOTO_LOCATION",
                    mediaRequired = true,
                    locationRequired = true,
                    claimable = true,
                    remainingSlots = 2,
                    revision = 2L,
                    cachedOnly = true,
                ),
            ),
            refreshing = false,
            notice = EarnNotice.NONE,
        )
    }

    Box(modifier = modifier) {
        EarnScreen(
            uiState = sampleState,
            rewardText = { opportunity ->
                when (opportunity.refreshId) {
                    "parking-refresh" -> "0.50 USDC"
                    "queue-refresh" -> "0.25 USDC"
                    else -> "0.30 USDC"
                }
            },
            serverNowMillis = { nowMillis },
            onRefresh = {
                lastAction = "Opportunity refresh invoked"
            },
            onOpportunityClick = { refreshId ->
                lastAction = "Opened opportunity · " + refreshId
            },
        )

        lastAction?.let { action ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = NowSpacing.Space4,
                        end = NowSpacing.Space4,
                        bottom = NowSpacing.Space4,
                    ),
                shape = MaterialTheme.shapes.medium,
                color = NowColors.Ink950,
            ) {
                Text(
                    text = action,
                    style = NowType.BodyS,
                    color = NowColors.SurfacePrimary,
                    modifier = Modifier.padding(
                        horizontal = NowSpacing.Space3,
                        vertical = NowSpacing.Space2,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RequesterRefreshScenario(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stage by rememberSaveable {
        mutableStateOf(RequesterFundingStage.SETUP)
    }
    var amount by rememberSaveable {
        mutableStateOf("0.40")
    }

    val uiState = RequesterFundingUiState(
        stateId = "metro-exit",
        title = "Metro exit crowd",
        stage = stage,
        amountInput = amount,
        amountError = null,
        rewardSymbol = "USDC",
        rewardConfigured = true,
        network = "devnet",
        walletAddress = if (stage >= RequesterFundingStage.REVIEW) "DemoWallet" else null,
        refreshId = if (stage == RequesterFundingStage.COMPLETE) "refresh-demo" else null,
        operationId = null,
        expiresAt = null,
        notice = null,
    )

    Box(modifier = modifier) {
        RequesterFundingScreen(
            uiState = uiState,
            onBack = onBack,
            onAmountChange = { amount = it },
            onPrepare = {
                stage = RequesterFundingStage.REVIEW
            },
            onSubmit = {
                stage = RequesterFundingStage.CONFIRMING
            },
            onCheck = {
                stage = RequesterFundingStage.COMPLETE
            },
        )
    }
}

@Composable
private fun StateDetailScenario(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowMillis = 1_800_000_000_000L
    var lastAction by rememberSaveable { mutableStateOf<String?>(null) }

    val sampleState = remember {
        StateDetailUiState(
            stateId = "metro-exit",
            detail = StateDetail(
                stateId = "metro-exit",
                version = 1,
                canonicalKey = sampleCanonicalKey(),
                title = "Metro exit crowd",
                question = "How crowded is the west exit right now?",
                stateType = "VISUAL",
                valueJson = "Busy",
                unitCode = null,
                freshnessStatus = "STALE",
                observedAtMillis = nowMillis - 2_700_000L,
                observationEarliestMillis = nowMillis - 2_760_000L,
                observationLatestMillis = nowMillis - 2_700_000L,
                agingAtMillis = nowMillis - 1_800_000L,
                freshUntilMillis = nowMillis - 900_000L,
                verificationClass = "FRESH_PHOTO_LOCATION",
                conflictActive = false,
                revision = 5L,
                location = StateLocation(
                    locationId = "metro-west-exit",
                    name = "West metro exit",
                    locationType = "POINT",
                    displayAddress = "Sector 7",
                ),
                verification = StateVerification(
                    status = "VERIFIED",
                    reasonCodes = listOf(
                        "fresh_capture",
                        "location_match",
                    ),
                    evidenceCount = 2,
                ),
                activeRefresh = null,
            ),
            loading = false,
            notice = BrowseNotice.NONE,
        )
    }

    Box(modifier = modifier) {
        StateDetailScreen(
            uiState = sampleState,
            serverNowMillis = { nowMillis },
            onBack = onBack,
            onRetry = {
                lastAction = "Retry invoked"
            },
            onRefreshRequest = { stateId ->
                lastAction = "Refresh flow invoked · $stateId"
            },
        )

        lastAction?.let { action ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = NowSpacing.Space4,
                        end = NowSpacing.Space4,
                        bottom = NowSpacing.Space4,
                    ),
                shape = MaterialTheme.shapes.medium,
                color = NowColors.Ink950,
            ) {
                Text(
                    text = action,
                    style = NowType.BodyS,
                    color = NowColors.SurfacePrimary,
                    modifier = Modifier.padding(
                        horizontal = NowSpacing.Space3,
                        vertical = NowSpacing.Space2,
                    ),
                )
            }
        }
    }
}

@Composable
private fun HomeScenario(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowMillis = 1_800_000_000_000L
    var lastAction by rememberSaveable { mutableStateOf<String?>(null) }

    val sampleState = remember {
        HomeUiState(
            areaLabel = "Sector 7 · 3 km",
            states = listOf(
                StateSummary(
                    stateId = "parking-sector-7",
                    title = "Sector 7 parking",
                    question = "How many parking spaces are available right now?",
                    stateType = "NUMERIC",
                    valueJson = "18",
                    unitCode = "spaces",
                    freshnessStatus = "LIVE",
                    observedAtMillis = nowMillis - 42_000L,
                    agingAtMillis = nowMillis + 180_000L,
                    freshUntilMillis = nowMillis + 480_000L,
                    verificationClass = "FRESH_PHOTO_LOCATION",
                    refreshStatus = null,
                    conflictActive = false,
                    distanceMeters = 320.0,
                    revision = 11L,
                ),
                StateSummary(
                    stateId = "gate-2-queue",
                    title = "Gate 2 queue",
                    question = "Is the entry queue longer than 10 people?",
                    stateType = "BINARY",
                    valueJson = "No",
                    unitCode = null,
                    freshnessStatus = "AGING",
                    observedAtMillis = nowMillis - 420_000L,
                    agingAtMillis = nowMillis - 60_000L,
                    freshUntilMillis = nowMillis + 180_000L,
                    verificationClass = "VISUAL_LOCATION",
                    refreshStatus = null,
                    conflictActive = false,
                    distanceMeters = 610.0,
                    revision = 8L,
                ),
                StateSummary(
                    stateId = "metro-exit",
                    title = "Metro exit crowd",
                    question = "How crowded is the west exit right now?",
                    stateType = "VISUAL",
                    valueJson = "Busy",
                    unitCode = null,
                    freshnessStatus = "STALE",
                    observedAtMillis = nowMillis - 2_700_000L,
                    agingAtMillis = nowMillis - 1_800_000L,
                    freshUntilMillis = nowMillis - 900_000L,
                    verificationClass = "FRESH_PHOTO_LOCATION",
                    refreshStatus = null,
                    conflictActive = false,
                    distanceMeters = 940.0,
                    revision = 5L,
                ),
            ),
            refreshing = false,
            notice = BrowseNotice.NONE,
        )
    }

    Box(modifier = modifier) {
        NowScreen(
            uiState = sampleState,
            darkTheme = darkTheme,
            onDarkThemeChange = onDarkThemeChange,
            serverNowMillis = { nowMillis },
            onRefresh = {
                lastAction = "Nearby refresh invoked"
            },
            onStateClick = { stateId ->
                lastAction = "Opened state · $stateId"
            },
            onEarnClick = {
                lastAction = "EARN navigation invoked"
            },
            onSettingsClick = {
                lastAction = "Settings navigation invoked"
            },
        )

        lastAction?.let { action ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = NowSpacing.Space4,
                        end = NowSpacing.Space4,
                        bottom = NowSpacing.Space4,
                    ),
                shape = MaterialTheme.shapes.medium,
                color = NowColors.Ink950,
            ) {
                Text(
                    text = action,
                    style = NowType.BodyS,
                    color = NowColors.SurfacePrimary,
                    modifier = Modifier.padding(
                        horizontal = NowSpacing.Space3,
                        vertical = NowSpacing.Space2,
                    ),
                )
            }
        }
    }
}

private fun sampleCanonicalKey(): String =
    listOf(
        "parking",
        "metro_west_exit",
        "crowd",
        "v1",
    ).joinToString(".")

private fun sampleTemplateKey(): String =
    listOf(
        "parking",
        "available_spaces",
        "v1",
    ).joinToString(".")

@Composable
private fun MotionScenario(
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val motionEnabled = rememberNowMotionEnabled()
    val labels = listOf("STALE", "VERIFYING", "LIVE")
    val tones = listOf(
        NowStatusTone.STALE,
        NowStatusTone.INFO,
        NowStatusTone.LIVE,
    )
    val values = listOf("12 spaces", "14 spaces", "18 spaces")
    val rewards = listOf("0.30 USDC", "0.40 USDC", "0.50 USDC")

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            top = NowSpacing.Space4,
            bottom = NowSpacing.Space8,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        item {
            NowNotice(
                title = if (motionEnabled) {
                    "System motion enabled"
                } else {
                    "Reduced motion active"
                },
                body = if (motionEnabled) {
                    "Transitions use the frozen NOW motion tokens."
                } else {
                    "State changes remain immediate and readable without animation.",
                },
                tone = NowNoticeTone.NEUTRAL,
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = NowColors.SurfacePrimary,
                border = BorderStroke(1.dp, NowColors.BorderSubtle),
            ) {
                Column(
                    modifier = Modifier.padding(NowSpacing.Space4),
                    verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
                ) {
                    Text(
                        text = "State transition",
                        style = NowType.TitleS,
                        color = NowColors.Ink950,
                    )

                    NowStatusChip(
                        label = labels[step],
                        tone = tones[step],
                        accessibilityLabel = "Motion sample " + labels[step],
                        modifier = Modifier.nowLivePulse(
                            active = labels[step] == "LIVE",
                        ),
                    )

                    Text(
                        text = values[step],
                        style = NowType.DataHero,
                        color = NowColors.Ink950,
                        modifier = Modifier.nowPulseOnChange(
                            key = values[step],
                        ),
                    )

                    Text(
                        text = rewards[step],
                        style = NowType.DataMedium,
                        color = NowColors.Ink800,
                        modifier = Modifier.nowPulseOnChange(
                            key = rewards[step],
                        ),
                    )

                    Text(
                        text = "Only real state/value changes animate. Navigation remains instant.",
                        style = NowType.BodyS,
                        color = NowColors.Ink500,
                    )
                }
            }
        }

        item {
            NowPrimaryButton(
                text = "Advance state",
                onClick = {
                    step = (step + 1) % labels.size
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DesignPrimitivesScenario(
    modifier: Modifier = Modifier,
) {
    var inputValue by rememberSaveable { mutableStateOf("Sector 7 parking") }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            top = NowSpacing.Space4,
            bottom = NowSpacing.Space8,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
            ) {
                Text(
                    text = "Production primitives",
                    style = NowType.TitleM,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "These components are shared with real product screens, not duplicate mock styling.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
            }
        }

        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Text(
                    text = "Actions",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                NowPrimaryButton(
                    text = "Refresh this state",
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                NowSecondaryButton(
                    text = "View evidence",
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                NowPrimaryButton(
                    text = "Unavailable action",
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false,
                )
            }
        }

        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Text(
                    text = "Status",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                ) {
                    NowStatusChip(label = "LIVE", tone = NowStatusTone.LIVE)
                    NowStatusChip(label = "AGING", tone = NowStatusTone.AGING)
                    NowStatusChip(label = "STALE", tone = NowStatusTone.STALE)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
                ) {
                    NowStatusChip(label = "CONFLICT", tone = NowStatusTone.CONFLICT)
                    NowStatusChip(label = "VERIFYING", tone = NowStatusTone.INFO)
                }
            }
        }

        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Text(
                    text = "Notices",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                NowNotice(
                    title = "Checking previous transaction",
                    body = "Do not submit again while the existing funding attempt is reconciled.",
                    tone = NowNoticeTone.INFO,
                )
                NowNotice(
                    body = "Live connection unavailable · saved verified states are still shown.",
                    tone = NowNoticeTone.NEUTRAL,
                )
                NowNotice(
                    body = "Evidence verified and the state is live.",
                    tone = NowNoticeTone.SUCCESS,
                )
                NowNotice(
                    body = "This opportunity expires soon.",
                    tone = NowNoticeTone.WARNING,
                )
                NowNotice(
                    body = "Evidence conflicts with the current verified state.",
                    tone = NowNoticeTone.ERROR,
                )
            }
        }

        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Text(
                    text = "Input",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                NowTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    label = "Location",
                    supportingText = "Structured input remains readable in both themes.",
                )
                NowTextField(
                    value = "Unavailable",
                    onValueChange = {},
                    label = "Disabled field",
                    enabled = false,
                )
            }
        }
    }
}

@Composable
private fun ThemeScenario(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            top = NowSpacing.Space4,
            bottom = NowSpacing.Space8,
        ),
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = NowColors.SurfacePrimary,
                border = BorderStroke(1.dp, NowColors.BorderSubtle),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(NowSpacing.Space4),
                    horizontalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space1),
                    ) {
                        Text(
                            text = "Manual theme",
                            style = NowType.TitleS,
                            color = NowColors.Ink950,
                        )
                        Text(
                            text = if (darkTheme) {
                                "Dark · deep navy operational surfaces"
                            } else {
                                "Light · cool white operational surfaces"
                            },
                            style = NowType.BodyS,
                            color = NowColors.Ink500,
                        )
                    }

                    Switch(
                        checked = darkTheme,
                        onCheckedChange = onDarkThemeChange,
                        modifier = Modifier.semantics {
                            stateDescription = if (darkTheme) {
                                "Dark theme"
                            } else {
                                "Light theme"
                            }
                        },
                    )
                }
            }
        }

        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Text(
                    text = "Semantic surfaces",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Canvas, card, border, text, and primary action should all change together.",
                    style = NowType.BodyS,
                    color = NowColors.Ink500,
                )
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = NowColors.SurfacePrimary,
                border = BorderStroke(1.dp, NowColors.BorderSubtle),
            ) {
                Column(
                    modifier = Modifier.padding(NowSpacing.Space4),
                    verticalArrangement = Arrangement.spacedBy(NowSpacing.Space3),
                ) {
                    Text(
                        text = "Primary surface",
                        style = NowType.TitleM,
                        color = NowColors.Ink950,
                    )
                    Text(
                        text = "Secondary copy remains quieter without losing contrast.",
                        style = NowType.BodyM,
                        color = NowColors.Ink600,
                    )
                    Button(
                        onClick = {},
                    ) {
                        Text("Primary action")
                    }
                }
            }
        }

        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Text(
                    text = "Operational status colors",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                ThemeStatusChip(
                    label = "LIVE",
                    foreground = NowColors.LiveText,
                    background = NowColors.LiveSoft,
                    border = NowColors.LiveBorder,
                )
                ThemeStatusChip(
                    label = "AGING",
                    foreground = NowColors.AgingText,
                    background = NowColors.AgingSoft,
                    border = NowColors.AgingBorder,
                )
                ThemeStatusChip(
                    label = "STALE",
                    foreground = NowColors.StaleText,
                    background = NowColors.StaleSoft,
                    border = NowColors.StaleBorder,
                )
                ThemeStatusChip(
                    label = "CONFLICT",
                    foreground = NowColors.ConflictText,
                    background = NowColors.ConflictSoft,
                    border = NowColors.ConflictBorder,
                )
                ThemeStatusChip(
                    label = "INFO / VERIFYING",
                    foreground = NowColors.InfoText,
                    background = NowColors.InfoSoft,
                    border = NowColors.InfoBorder,
                )
            }
        }
    }
}

@Composable
private fun ThemeStatusChip(
    label: String,
    foreground: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
    border: androidx.compose.ui.graphics.Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = background,
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = label,
            style = NowType.LabelL,
            color = foreground,
            modifier = Modifier.padding(
                horizontal = NowSpacing.Space3,
                vertical = NowSpacing.Space2,
            ),
        )
    }
}

@Composable
private fun ShellScenario(
    modifier: Modifier = Modifier,
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val labels = listOf("NOW", "EARN", "ACTIVITY")
    val icons = listOf(
        Icons.Outlined.Home,
        Icons.Outlined.WorkOutline,
        Icons.Outlined.CheckCircle,
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NowSpacing.Space4),
    ) {
        Spacer(Modifier.height(NowSpacing.Space2))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = NowColors.SurfacePrimary,
            border = BorderStroke(1.dp, NowColors.BorderSubtle),
        ) {
            Column(
                modifier = Modifier.padding(NowSpacing.Space4),
                verticalArrangement = Arrangement.spacedBy(NowSpacing.Space2),
            ) {
                Text(
                    text = "Interaction smoke test",
                    style = NowType.TitleS,
                    color = NowColors.Ink950,
                )
                Text(
                    text = "Tap each destination. The selected state changes immediately without navigating or loading.",
                    style = NowType.BodyM,
                    color = NowColors.Ink600,
                )
                Text(
                    text = "Selected: ${labels[selectedIndex]}",
                    style = NowType.LabelM,
                    color = NowColors.Blue700,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = NowColors.SurfacePrimary,
            border = BorderStroke(1.dp, NowColors.BorderSubtle),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) {
                labels.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable(
                                role = Role.Tab,
                                onClick = {
                                    selectedIndex = index
                                },
                            )
                            .semantics {
                                this.selected = selected
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 34.dp, height = 28.dp)
                                .background(
                                    color = if (selected) {
                                        NowColors.Blue50
                                    } else {
                                        NowColors.SurfacePrimary
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icons[index],
                                contentDescription = null,
                                tint = if (selected) NowColors.Blue600 else NowColors.Ink500,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) NowColors.Blue600 else NowColors.Ink500,
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = NowColors.InfoSoft,
            border = BorderStroke(1.dp, NowColors.InfoBorder),
        ) {
            Text(
                text = "This is a harness smoke test. Production components will replace lab-only previews as each UI surface is finalized.",
                style = NowType.BodyS,
                color = NowColors.InfoText,
                modifier = Modifier.padding(NowSpacing.Space3),
            )
        }

        Button(
            onClick = {
                selectedIndex = (selectedIndex + 1) % labels.size
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Science,
                contentDescription = null,
            )
            Spacer(Modifier.size(8.dp))
            Text("Cycle selected tab")
        }
    }
}
