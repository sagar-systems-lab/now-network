package com.sagarsystemslab.nownetwork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val browseViewModel: BrowseViewModel by viewModels()
    private val earnViewModel: EarnViewModel by viewModels()
    private val contributorClaimViewModel: ContributorClaimViewModel by viewModels()
    private val evidenceCaptureViewModel: EvidenceCaptureViewModel by viewModels()
    private val verificationViewModel: VerificationViewModel by viewModels()
    private val paymentViewModel: PaymentViewModel by viewModels()
    private val receiptViewModel: ReceiptViewModel by viewModels()
    private val activityViewModel: ActivityViewModel by viewModels()
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
        setContent {
            NowTheme {
                LaunchedEffect(Unit) {
                    if (runtimeConfig.apiConfigured && runtimeConfig.authConfigured) {
                        sessionRepository.get().bootstrap()
                    }
                }

                NowApp(
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
        }
    }

    override fun onResume() {
        super.onResume()
        browseViewModel.onForeground()
    }
}
