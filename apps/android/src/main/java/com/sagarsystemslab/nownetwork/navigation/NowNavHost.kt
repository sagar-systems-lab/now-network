package com.sagarsystemslab.nownetwork.navigation

import com.sagarsystemslab.nownetwork.experience.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.sagarsystemslab.nownetwork.feature.activity.ActivityScreen
import com.sagarsystemslab.nownetwork.feature.activity.ActivityViewModel
import com.sagarsystemslab.nownetwork.feature.capture.EvidenceCaptureScreen
import com.sagarsystemslab.nownetwork.feature.capture.EvidenceCaptureViewModel
import com.sagarsystemslab.nownetwork.feature.earn.ContributorClaimScreen
import com.sagarsystemslab.nownetwork.feature.earn.ContributorClaimViewModel
import com.sagarsystemslab.nownetwork.feature.earn.EarnScreen
import com.sagarsystemslab.nownetwork.feature.earn.EarnViewModel
import com.sagarsystemslab.nownetwork.feature.home.NowScreen
import com.sagarsystemslab.nownetwork.feature.payment.PaymentScreen
import com.sagarsystemslab.nownetwork.feature.payment.PaymentViewModel
import com.sagarsystemslab.nownetwork.feature.receipt.ReceiptScreen
import com.sagarsystemslab.nownetwork.feature.receipt.ReceiptViewModel
import com.sagarsystemslab.nownetwork.feature.requester.RequesterFundingScreen
import com.sagarsystemslab.nownetwork.feature.requester.RequesterFundingViewModel
import com.sagarsystemslab.nownetwork.feature.settings.SettingsScreen
import com.sagarsystemslab.nownetwork.feature.state.BrowseViewModel
import com.sagarsystemslab.nownetwork.feature.state.StateDetailScreen
import com.sagarsystemslab.nownetwork.feature.verification.VerificationScreen
import com.sagarsystemslab.nownetwork.feature.verification.VerificationViewModel
import com.sagarsystemslab.nownetwork.wallet.WalletInteractionHost

@Composable
fun NowNavHost(
    inboxIntentRevision: Int = 0,
    appState: NowAppState,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    browseViewModelProvider: () -> BrowseViewModel,
    earnViewModelProvider: () -> EarnViewModel,
    contributorClaimViewModelProvider: () -> ContributorClaimViewModel,
    evidenceCaptureViewModelProvider: () -> EvidenceCaptureViewModel,
    verificationViewModelProvider: () -> VerificationViewModel,
    paymentViewModelProvider: () -> PaymentViewModel,
    receiptViewModelProvider: () -> ReceiptViewModel,
    activityViewModelProvider: () -> ActivityViewModel,
    requesterFundingViewModelProvider: () -> RequesterFundingViewModel,
    walletInteractionHost: WalletInteractionHost,
    experienceViewModelProvider: (() -> com.sagarsystemslab.nownetwork.experience.ExperienceViewModel)? = null,
    uiPreferencesStore: com.sagarsystemslab.nownetwork.experience.UiPreferencesStore? = null,
    modifier: Modifier = Modifier,
) {
    val experience = experienceViewModelProvider?.invoke()
    val experienceState = experience?.state?.collectAsStateWithLifecycle()?.value
    val area = experience?.browseContext?.state?.collectAsStateWithLifecycle()?.value
    val center = if (area?.configured == true) com.sagarsystemslab.nownetwork.model.GeoCenter(requireNotNull(area.latitude), requireNotNull(area.longitude)) else null
    fun openExperience(destination: ExperienceDestination) {
        if (experience == null) appState.navigateToSettings()
        else appState.navController.navigate(ExperienceRoute(destination)) { launchSingleTop = true }
    }
    val motion = com.sagarsystemslab.nownetwork.designsystem.rememberNowMotionEnabled()
    LaunchedEffect(experience) { experience?.refresh() }
    LaunchedEffect(inboxIntentRevision) { if (inboxIntentRevision > 0) openExperience(ExperienceDestination.NOTIFICATIONS) }

    experienceState?.privateProof?.let { proof ->
        com.sagarsystemslab.nownetwork.experience.PrivateProofDialog(proof) { experience?.dismissProof() }
    }
    NavHost(
        navController = appState.navController,
        startDestination = NowRoute,
        modifier = modifier,
        enterTransition = { if (motion) fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 } else EnterTransition.None },
        exitTransition = { if (motion) fadeOut(tween(180)) else ExitTransition.None },
        popEnterTransition = { if (motion) fadeIn(tween(220)) else EnterTransition.None },
        popExitTransition = { if (motion) fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 12 } else ExitTransition.None },
    ) {
        composable<NowRoute> {
            val browseViewModel = browseViewModelProvider()
            val uiState by browseViewModel.homeState.collectAsStateWithLifecycle()

            NowScreen(
                uiState = uiState,
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange,
                serverNowMillis = browseViewModel::serverNowMillis,
                onRefresh = browseViewModel::refreshHome,
                onStateClick = appState::navigateToState,
                onEarnClick = {
                    appState.navigateTo(TopLevelDestination.EARN)
                },
                onSettingsClick = { openExperience(ExperienceDestination.SETTINGS) },
                onBrowseAreas = { openExperience(ExperienceDestination.BROWSE_AREAS) },
                onNotifications = { openExperience(ExperienceDestination.NOTIFICATIONS) },
                onProfile = { openExperience(ExperienceDestination.PROFILE) },
                onFundState = appState::navigateToRequesterFunding,
                center = center,
                onSearchArea = { next -> experience?.browseContext?.select("Selected map area", next.latitude, next.longitude) },
                unread = experienceState?.inbox?.number("unread_count") ?: 0,
            )
        }

        composable<StateDetailRoute> { backStackEntry ->
            val browseViewModel = browseViewModelProvider()
            val route = backStackEntry.toRoute<StateDetailRoute>()
            val uiState by browseViewModel.detailState.collectAsStateWithLifecycle()

            LaunchedEffect(route.stateId) {
                browseViewModel.openState(route.stateId)
                experience?.history(route.stateId)
            }

            StateDetailScreen(
                uiState = uiState,
                serverNowMillis = browseViewModel::serverNowMillis,
                onBack = appState::navigateBack,
                onRetry = browseViewModel::retryState,
                onRefreshRequest = appState::navigateToRequesterFunding,
                history = { detail -> com.sagarsystemslab.nownetwork.feature.common.StateHistoryPanel(detail, experienceState?.history?.get(route.stateId), experienceState?.historyErrors?.get(route.stateId), { experience?.history(route.stateId) }, { experience?.history(route.stateId, more = true) }) },
                onViewProof = { experience?.proof(route.stateId) },
                onActivity = { appState.navigateTo(TopLevelDestination.ACTIVITY) },
            )
        }

        composable<RequesterFundingRoute> { backStackEntry ->
            val requesterFundingViewModel = requesterFundingViewModelProvider()
            val route = backStackEntry.toRoute<RequesterFundingRoute>()
            val uiState by requesterFundingViewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(route.stateId) {
                requesterFundingViewModel.open(route.stateId)
            }

            RequesterFundingScreen(
                uiState = uiState,
                onBack = appState::navigateBack,
                onAmountChange = requesterFundingViewModel::updateAmount,
                onPrepare = {
                    requesterFundingViewModel.prepare(walletInteractionHost)
                },
                onSubmit = {
                    requesterFundingViewModel.submit(walletInteractionHost)
                },
                onCheck = requesterFundingViewModel::checkConfirmation,
                onWallet = { openExperience(ExperienceDestination.WALLET) },
            )
        }

        composable<EarnRoute> {
            val earnViewModel = earnViewModelProvider()
            val uiState by earnViewModel.state.collectAsStateWithLifecycle()

            EarnScreen(
                uiState = uiState,
                rewardText = earnViewModel::rewardText,
                serverNowMillis = earnViewModel::serverNowMillis,
                onRefresh = earnViewModel::refresh,
                onOpportunityClick = appState::navigateToOpportunity,
                onBrowseAreas = { openExperience(ExperienceDestination.BROWSE_AREAS) },
                onNotifications = { openExperience(ExperienceDestination.NOTIFICATIONS) },
                onProfile = { openExperience(ExperienceDestination.PROFILE) },
                onHelp = { openExperience(ExperienceDestination.HELP_ABOUT) },
                center = center,
                onSearchArea = { next -> experience?.browseContext?.select("Selected map area", next.latitude, next.longitude) },
                unread = experienceState?.inbox?.number("unread_count") ?: 0,
            )
        }

        composable<OpportunityRoute> { backStackEntry ->
            val contributorClaimViewModel = contributorClaimViewModelProvider()
            val route = backStackEntry.toRoute<OpportunityRoute>()
            val uiState by contributorClaimViewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(route.refreshId) {
                contributorClaimViewModel.open(route.refreshId)
            }

            ContributorClaimScreen(
                uiState = uiState,
                rewardText = contributorClaimViewModel.rewardText(),
                onBack = appState::navigateBack,
                onPrepare = {
                    contributorClaimViewModel.prepare(walletInteractionHost)
                },
                onSubmit = {
                    contributorClaimViewModel.submit(walletInteractionHost)
                },
                onCheck = contributorClaimViewModel::checkConfirmation,
                onCaptureEvidence = {
                    val claim = uiState.claim ?: return@ContributorClaimScreen
                    appState.navigateToEvidence(
                        acceptanceId = claim.acceptanceId,
                        refreshId = claim.refreshId,
                    )
                },
            )
        }

        composable<EvidenceCaptureRoute> { backStackEntry ->
            val evidenceCaptureViewModel = evidenceCaptureViewModelProvider()
            val route = backStackEntry.toRoute<EvidenceCaptureRoute>()
            val uiState by evidenceCaptureViewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(route.acceptanceId, route.refreshId) {
                evidenceCaptureViewModel.open(
                    acceptanceId = route.acceptanceId,
                    refreshId = route.refreshId,
                )
            }

            EvidenceCaptureScreen(
                uiState = uiState,
                onBack = appState::navigateBack,
                onBeginCapture = evidenceCaptureViewModel::beginCapture,
                onPermissionDenied = evidenceCaptureViewModel::permissionDenied,
                onPhotoCaptured = evidenceCaptureViewModel::photoCaptured,
                onCameraError = evidenceCaptureViewModel::cameraFailure,
                onAnswerChange = evidenceCaptureViewModel::updateAnswer,
                onRefreshLocation = evidenceCaptureViewModel::refreshLocation,
                onRecapture = evidenceCaptureViewModel::recapture,
                onSubmit = evidenceCaptureViewModel::submit,
                onRetry = evidenceCaptureViewModel::retry,
                onContinueVerification = {
                    appState.navigateToVerification(route.refreshId)
                },
            )
        }

        composable<VerificationRoute> { backStackEntry ->
            val verificationViewModel = verificationViewModelProvider()
            val route = backStackEntry.toRoute<VerificationRoute>()
            val uiState by verificationViewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(route.refreshId) {
                verificationViewModel.open(route.refreshId)
            }

            VerificationScreen(
                uiState = uiState,
                onBack = appState::navigateBack,
                onRetry = verificationViewModel::retry,
                onTrackPayment = {
                    appState.navigateToPayment(route.refreshId)
                },
                onDone = {
                    appState.navigateTo(TopLevelDestination.EARN)
                },
            )
        }

        composable<PaymentRoute> { backStackEntry ->
            val paymentViewModel = paymentViewModelProvider()
            val route = backStackEntry.toRoute<PaymentRoute>()
            val uiState by paymentViewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(route.refreshId) {
                paymentViewModel.open(route.refreshId)
                experience?.refreshContext(route.refreshId)
            }

            val paymentContext = experienceState?.refreshDetails?.get(route.refreshId)
            LaunchedEffect(uiState.finalizedAt) { if (uiState.finalizedAt != null) experience?.refreshContext(route.refreshId) }
            PaymentScreen(
                uiState = uiState,
                context = paymentContext,
                personalAmount = paymentContext?.text("payout_atomic")?.takeIf { it.isNotBlank() }?.let { experience?.amount(it, paymentContext.text("reward_mint")) },
                onBack = appState::navigateBack,
                onRetry = paymentViewModel::retry,
                onViewReceipt = {
                    appState.navigateToReceipt(route.refreshId)
                },
                onDone = {
                    appState.navigateTo(TopLevelDestination.ACTIVITY)
                },
            )
        }

        composable<ReceiptRoute> { backStackEntry ->
            val receiptViewModel = receiptViewModelProvider()
            val route = backStackEntry.toRoute<ReceiptRoute>()
            val uiState by receiptViewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(route.refreshId) {
                receiptViewModel.open(route.refreshId)
                experience?.refreshContext(route.refreshId)
            }

            val receiptContext = experienceState?.refreshDetails?.get(route.refreshId)
            LaunchedEffect(uiState.receipt?.receiptId) { if (uiState.receipt != null) experience?.refreshContext(route.refreshId) }
            ReceiptScreen(
                uiState = uiState,
                context = receiptContext,
                personalAmount = receiptContext?.text("payout_atomic")?.takeIf { it.isNotBlank() }?.let { experience?.amount(it, receiptContext.text("reward_mint")) },
                poolAmount = uiState.receipt?.let { experience?.amount(it.rewardAmountAtomic, it.rewardMint) },
                onBack = appState::navigateBack,
                onRetry = receiptViewModel::retry,
                onDone = {
                    appState.navigateTo(TopLevelDestination.ACTIVITY)
                },
            )
        }

        composable<ActivityRoute> {
            val activityViewModel = activityViewModelProvider()
            val uiState by activityViewModel.state.collectAsStateWithLifecycle()

            if (experience != null && experienceState != null) {
                LaunchedEffect(Unit) { experience.activity() }
                com.sagarsystemslab.nownetwork.feature.activity.AccountActivityScreen(
                    experienceState, uiState, experience, area?.label.orEmpty(),
                    onArea = { openExperience(ExperienceDestination.BROWSE_AREAS) },
                    onNotifications = { openExperience(ExperienceDestination.NOTIFICATIONS) },
                    onProfile = { openExperience(ExperienceDestination.PROFILE) },
                    onHelp = { openExperience(ExperienceDestination.HELP_ABOUT) },
                    onOpen = { row ->
                        val id = row.text("refresh_id")
                        when {
                            row.text("receipt_id").isNotBlank() -> appState.navigateToReceipt(id)
                            row.text("payment_status").isNotBlank() -> appState.navigateToPayment(id)
                            row.text("claim_status") == "CLAIMED" -> appState.navigateToEvidence(row.text("acceptance_id"), id)
                            row.text("acceptance_id").isNotBlank() -> appState.navigateToVerification(id)
                            row.text("status") in setOf("DRAFT", "AWAITING_FUNDING") -> appState.navigateToRequesterFunding(row.text("state_id"))
                            else -> appState.navigateToState(row.text("state_id"))
                        }
                    }, onLocalPayment = appState::navigateToPayment,
                )
            } else ActivityScreen(
                uiState = uiState,
                serverNowMillis = activityViewModel::serverNowMillis,
                onPaymentClick = appState::navigateToPayment,
                onReceiptClick = appState::navigateToReceipt,
                onBrowseAreas = { openExperience(ExperienceDestination.BROWSE_AREAS) },
                onNotifications = { openExperience(ExperienceDestination.NOTIFICATIONS) },
                onProfile = { openExperience(ExperienceDestination.PROFILE) },
                onHelp = { openExperience(ExperienceDestination.HELP_ABOUT) },
                areaLabel = area?.label.orEmpty(),
                unread = experienceState?.inbox?.number("unread_count") ?: 0,
            )
        }

        composable<ExperienceRoute> { entry ->
            val route = entry.toRoute<ExperienceRoute>()
            if (experience != null && uiPreferencesStore != null) {
                ExperienceScreen(route.destination, experience, uiPreferencesStore, walletInteractionHost,
                    onBack = appState::navigateBack, navigate = ::openExperience,
                    onNotification = { notification ->
                        val id = notification.text("entity_id")
                        when (notification.text("destination")) {
                            "receipt" -> if (id.isNotBlank()) appState.navigateToReceipt(id)
                            "payment" -> if (id.isNotBlank()) appState.navigateToPayment(id)
                            "verification" -> if (id.isNotBlank()) appState.navigateToVerification(id)
                            "opportunity" -> if (id.isNotBlank()) appState.navigateToOpportunity(id)
                            "wallet" -> openExperience(if (notification.text("category") == "security") ExperienceDestination.CONNECTED_SESSIONS else ExperienceDestination.WALLET)
                            else -> appState.navigateTo(TopLevelDestination.ACTIVITY)
                        }
                    },
                )
            }
        }

        composable<SettingsRoute> {
            SettingsScreen(
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange,
                onBack = appState::navigateBack,
            )
        }
    }
}
