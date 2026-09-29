package com.sagarsystemslab.nownetwork.di

import com.sagarsystemslab.nownetwork.repository.DefaultActivityRepository
import com.sagarsystemslab.nownetwork.repository.DefaultContributorClaimRepository
import com.sagarsystemslab.nownetwork.repository.DefaultEvidenceCaptureRepository
import com.sagarsystemslab.nownetwork.repository.EvidenceCaptureRepository
import com.sagarsystemslab.nownetwork.evidence.AppPrivateEvidenceFileStore
import com.sagarsystemslab.nownetwork.evidence.EvidenceFileStore
import com.sagarsystemslab.nownetwork.evidence.EvidenceLocationProvider
import com.sagarsystemslab.nownetwork.evidence.EvidenceObjectUploader
import com.sagarsystemslab.nownetwork.evidence.FusedEvidenceLocationProvider
import com.sagarsystemslab.nownetwork.evidence.KtorEvidenceObjectUploader
import com.sagarsystemslab.nownetwork.repository.DefaultOpportunityRepository
import com.sagarsystemslab.nownetwork.repository.DefaultPaymentRepository
import com.sagarsystemslab.nownetwork.repository.PaymentRepository
import com.sagarsystemslab.nownetwork.repository.DefaultStateRepository
import com.sagarsystemslab.nownetwork.repository.DefaultVerificationRepository
import com.sagarsystemslab.nownetwork.repository.VerificationRepository
import com.sagarsystemslab.nownetwork.repository.ContributorClaimRepository
import com.sagarsystemslab.nownetwork.repository.DefaultRequesterFundingRepository
import com.sagarsystemslab.nownetwork.repository.DefaultReceiptRepository
import com.sagarsystemslab.nownetwork.repository.ReceiptRepository
import com.sagarsystemslab.nownetwork.repository.StateCache
import com.sagarsystemslab.nownetwork.repository.StateRepository
import com.sagarsystemslab.nownetwork.repository.RequesterFundingRepository
import com.sagarsystemslab.nownetwork.repository.ActivityRepository
import com.sagarsystemslab.nownetwork.repository.OpportunityRepository
import com.sagarsystemslab.nownetwork.repository.RoomStateCache
import com.sagarsystemslab.nownetwork.realtime.NowRealtimeGateway
import com.sagarsystemslab.nownetwork.realtime.SupabaseNowRealtimeGateway
import com.sagarsystemslab.nownetwork.security.AndroidKeystoreSecretStore
import com.sagarsystemslab.nownetwork.security.SecureSecretStore
import com.sagarsystemslab.nownetwork.wallet.MwaWalletGateway
import com.sagarsystemslab.nownetwork.wallet.WalletGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindReceiptRepository(
        implementation: DefaultReceiptRepository,
    ): ReceiptRepository

    @Binds
    @Singleton
    abstract fun bindPaymentRepository(
        implementation: DefaultPaymentRepository,
    ): PaymentRepository

    @Binds
    @Singleton
    abstract fun bindVerificationRepository(
        implementation: DefaultVerificationRepository,
    ): VerificationRepository

    @Binds
    @Singleton
    abstract fun bindEvidenceCaptureRepository(
        implementation: DefaultEvidenceCaptureRepository,
    ): EvidenceCaptureRepository

    @Binds
    @Singleton
    abstract fun bindEvidenceObjectUploader(
        implementation: KtorEvidenceObjectUploader,
    ): EvidenceObjectUploader

    @Binds
    @Singleton
    abstract fun bindEvidenceFileStore(
        implementation: AppPrivateEvidenceFileStore,
    ): EvidenceFileStore

    @Binds
    @Singleton
    abstract fun bindEvidenceLocationProvider(
        implementation: FusedEvidenceLocationProvider,
    ): EvidenceLocationProvider

    @Binds
    @Singleton
    abstract fun bindContributorClaimRepository(
        implementation: DefaultContributorClaimRepository,
    ): ContributorClaimRepository

    @Binds
    @Singleton
    abstract fun bindRequesterFundingRepository(
        implementation: DefaultRequesterFundingRepository,
    ): RequesterFundingRepository

    @Binds
    @Singleton
    abstract fun bindWalletGateway(
        implementation: MwaWalletGateway,
    ): WalletGateway

    @Binds
    @Singleton
    abstract fun bindSecureSecretStore(
        implementation: AndroidKeystoreSecretStore,
    ): SecureSecretStore

    @Binds
    @Singleton
    abstract fun bindRealtimeGateway(
        implementation: SupabaseNowRealtimeGateway,
    ): NowRealtimeGateway

    @Binds
    @Singleton
    abstract fun bindOpportunityRepository(
        implementation: DefaultOpportunityRepository,
    ): OpportunityRepository

    @Binds
    @Singleton
    abstract fun bindActivityRepository(
        implementation: DefaultActivityRepository,
    ): ActivityRepository

    @Binds
    @Singleton
    abstract fun bindStateCache(
        implementation: RoomStateCache,
    ): StateCache

    @Binds
    @Singleton
    abstract fun bindStateRepository(
        implementation: DefaultStateRepository,
    ): StateRepository
}
