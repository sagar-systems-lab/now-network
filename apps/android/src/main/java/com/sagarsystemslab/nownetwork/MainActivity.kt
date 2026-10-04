package com.sagarsystemslab.nownetwork

import android.os.Bundle
import android.app.KeyguardManager
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.security.AndroidKeystoreSecretStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.sagarsystemslab.nownetwork.experience.*
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import com.sagarsystemslab.nownetwork.designsystem.NowTheme
import com.sagarsystemslab.nownetwork.feature.activity.ActivityViewModel
import com.sagarsystemslab.nownetwork.feature.capture.EvidenceCaptureViewModel
import com.sagarsystemslab.nownetwork.feature.earn.ContributorClaimViewModel
import com.sagarsystemslab.nownetwork.feature.earn.EarnViewModel
import com.sagarsystemslab.nownetwork.feature.payment.PaymentViewModel
import com.sagarsystemslab.nownetwork.feature.receipt.ReceiptViewModel
import com.sagarsystemslab.nownetwork.feature.requester.RequesterFundingViewModel
import com.sagarsystemslab.nownetwork.feature.state.BrowseViewModel
import com.sagarsystemslab.nownetwork.feature.verification.VerificationViewModel
import com.sagarsystemslab.nownetwork.repository.SessionRepository
import com.sagarsystemslab.nownetwork.wallet.AndroidWalletInteractionHost
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var inboxIntentRevision by mutableStateOf(0)
    private var appLocked by mutableStateOf(false)
    private var unlocking = false
    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        unlocking = false
        appLocked = result.resultCode != RESULT_OK
    }
    private fun lockEnabled() = AndroidKeystoreSecretStore(this).read("app_lock_enabled") == "true"
    private fun requestUnlock() {
        if (unlocking) return
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock NOW", "Confirm your device credential to continue.")
        if (intent != null) { unlocking = true; unlock.launch(intent) }
        else startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
    }

    override fun onStart() {
        super.onStart()
        if (lockEnabled()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (!unlocking) appLocked = true
        } else { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); appLocked = false }
    }

    override fun onStop() {
        if (lockEnabled() && !unlocking) appLocked = true
        super.onStop()
    }
    private val experienceViewModel: ExperienceViewModel by viewModels()
    @Inject lateinit var uiPreferencesStore: UiPreferencesStore
    private val browseViewModel: BrowseViewModel by viewModels()
    private val earnViewModel: EarnViewModel by viewModels()
    private val contributorClaimViewModel: ContributorClaimViewModel by viewModels()
    private val evidenceCaptureViewModel: EvidenceCaptureViewModel by viewModels()
    private val verificationViewModel: VerificationViewModel by viewModels()
    private val paymentViewModel: PaymentViewModel by viewModels()
    private val receiptViewModel: ReceiptViewModel by viewModels()
    private val activityViewModel: ActivityViewModel by viewModels()
    private val askComposerViewModel: com.sagarsystemslab.nownetwork.feature.ask.AskComposerViewModel by viewModels()
    private val availabilityViewModel: com.sagarsystemslab.nownetwork.feature.ask.ContributorAvailabilityViewModel by viewModels()
    private val requesterFundingViewModel: RequesterFundingViewModel by viewModels()
    private lateinit var walletInteractionHost: AndroidWalletInteractionHost

    @Inject
    lateinit var runtimeConfig: PublicRuntimeConfig

    @Inject
    lateinit var sessionRepository: Lazy<SessionRepository>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletInteractionHost = AndroidWalletInteractionHost(this)
        enableEdgeToEdge()
        if (intent?.action == NowPush.OPEN_INBOX) inboxIntentRevision++

        setContent {
            val preferences by uiPreferencesStore.state.collectAsStateWithLifecycle(uiPreferencesStore.initial)
            val scope = rememberCoroutineScope()
            val darkTheme = when (preferences.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            LaunchedEffect(Unit) { uiPreferencesStore.migrate() }

            ApplyNowSystemBars(
                activity = this@MainActivity,
                darkTheme = darkTheme,
            )

            androidx.compose.runtime.CompositionLocalProvider(LocalDistanceUnit provides preferences.distanceUnit) {
            NowTheme(darkTheme = darkTheme, reduceMotion = preferences.reduceMotion || appLocked) {
                LaunchedEffect(Unit) {
                    if (runtimeConfig.apiConfigured && runtimeConfig.authConfigured) {
                        withContext(Dispatchers.IO) {
                            sessionRepository.get().bootstrap()
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                    LaunchedEffect(appLocked) { if (appLocked) focusManager.clearFocus(force = true) }
                    Box(Modifier.then(if (appLocked) Modifier.clearAndSetSemantics {} else Modifier)) {
                    NowApp(
                        askComposerViewModelProvider = { askComposerViewModel },
                        availabilityViewModelProvider = { availabilityViewModel },
                        inboxIntentRevision = inboxIntentRevision,
                        darkTheme = darkTheme,
                        onDarkThemeChange = { enabled -> scope.launch { uiPreferencesStore.theme(if (enabled) ThemeMode.DARK else ThemeMode.LIGHT) } },
                        experienceViewModelProvider = { experienceViewModel },
                        uiPreferencesStore = uiPreferencesStore,
                        browseViewModelProvider = { browseViewModel },
                        earnViewModelProvider = { earnViewModel },
                        contributorClaimViewModelProvider = { contributorClaimViewModel },
                        evidenceCaptureViewModelProvider = { evidenceCaptureViewModel },
                        verificationViewModelProvider = { verificationViewModel },
                        paymentViewModelProvider = { paymentViewModel },
                        receiptViewModelProvider = { receiptViewModel },
                        activityViewModelProvider = { activityViewModel },
                        requesterFundingViewModelProvider = { requesterFundingViewModel },
                        walletInteractionHost = walletInteractionHost,
                    )
                    }

                    if (appLocked) {
                        BackHandler { /* Keep private content covered until unlocked. */ }
                        Box(Modifier.fillMaxSize().background(NowColors.SurfaceCanvas).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
                            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("NOW is locked", style = NowType.TitleXL, color = NowColors.Ink950)
                                Text("Unlock with your Android device credential.", style = NowType.BodyM, color = NowColors.Ink600)
                                NowPrimaryButton("Unlock NOW", ::requestUnlock, Modifier.fillMaxWidth(), enabled = !unlocking)
                            }
                        }
                    }

                }
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == NowPush.OPEN_INBOX) inboxIntentRevision++
    }

    override fun onResume() {
        super.onResume()
        browseViewModel.onForeground()
        if (runtimeConfig.apiConfigured && runtimeConfig.authConfigured) experienceViewModel.refresh()
    }
}
