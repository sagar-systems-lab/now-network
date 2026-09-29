package com.sagarsystemslab.nownetwork.di

import android.content.Context
import com.sagarsystemslab.nownetwork.BuildConfig
import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.auth.SupabaseAuthGateway
import com.sagarsystemslab.nownetwork.auth.SupabaseRuntimeClient
import com.sagarsystemslab.nownetwork.auth.UnavailableAuthGateway
import com.sagarsystemslab.nownetwork.config.BrowseAreaConfig
import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import com.sagarsystemslab.nownetwork.config.RewardDisplayConfig
import com.sagarsystemslab.nownetwork.config.SolanaRuntimeConfig
import com.sagarsystemslab.nownetwork.data.local.CachedStateDao
import com.sagarsystemslab.nownetwork.data.local.LocalPersistenceStore
import com.sagarsystemslab.nownetwork.data.local.NowDatabase
import com.sagarsystemslab.nownetwork.data.local.NowDatabaseFactory
import com.sagarsystemslab.nownetwork.data.local.WalletSessionMetadataDao
import com.sagarsystemslab.nownetwork.network.EvidenceApiClient
import com.sagarsystemslab.nownetwork.network.EvidenceReadApiClient
import com.sagarsystemslab.nownetwork.network.KtorNowApiClient
import com.sagarsystemslab.nownetwork.network.NowApiClient
import com.sagarsystemslab.nownetwork.network.PaymentApiClient
import com.sagarsystemslab.nownetwork.network.ReceiptApiClient
import com.sagarsystemslab.nownetwork.network.VerificationApiClient
import com.sagarsystemslab.nownetwork.solana.KtorSolanaRpcClient
import com.sagarsystemslab.nownetwork.solana.SolanaRpcClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Singleton
import kotlinx.serialization.json.Json

object NetworkTimeouts {
    const val REQUEST_MILLIS = 15_000L
    const val CONNECT_MILLIS = 10_000L
    const val SOCKET_MILLIS = 20_000L
}

@Module
@InstallIn(SingletonComponent::class)
object RuntimeModule {
    @Provides
    @Singleton
    fun providePublicRuntimeConfig(): PublicRuntimeConfig =
        PublicRuntimeConfig(
            apiBaseUrl = BuildConfig.API_BASE_URL,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabasePublishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            solanaCluster = BuildConfig.SOLANA_CLUSTER,
        )

    @Provides
    @Singleton
    fun provideSolanaRuntimeConfig(): SolanaRuntimeConfig =
        SolanaRuntimeConfig(
            cluster = BuildConfig.SOLANA_CLUSTER,
            rpcUrl = BuildConfig.SOLANA_RPC_URL,
            programId = BuildConfig.SOLANA_PROGRAM_ID,
            walletIdentityUri = BuildConfig.WALLET_IDENTITY_URI,
            walletIconUri = BuildConfig.WALLET_ICON_URI,
        )

    @Provides
    @Singleton
    fun provideBrowseAreaConfig(): BrowseAreaConfig =
        BrowseAreaConfig(
            label = BuildConfig.BROWSE_AREA_LABEL,
            latitude = BuildConfig.BROWSE_LATITUDE.toDoubleOrNull(),
            longitude = BuildConfig.BROWSE_LONGITUDE.toDoubleOrNull(),
            radiusMeters = BuildConfig.BROWSE_RADIUS_METERS.toIntOrNull() ?: 3_000,
        )

    @Provides
    @Singleton
    fun provideRewardDisplayConfig(): RewardDisplayConfig =
        RewardDisplayConfig(
            mint = BuildConfig.REWARD_MINT,
            symbol = BuildConfig.REWARD_SYMBOL,
            decimals = BuildConfig.REWARD_DECIMALS.toIntOrNull() ?: 6,
        )

    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
        }

    @Provides
    @Singleton
    fun provideHttpClient(appJson: Json): HttpClient =
        HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = false

            engine {
                config {
                    retryOnConnectionFailure(false)
                }
            }

            install(ContentNegotiation) {
                json(appJson)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = NetworkTimeouts.REQUEST_MILLIS
                connectTimeoutMillis = NetworkTimeouts.CONNECT_MILLIS
                socketTimeoutMillis = NetworkTimeouts.SOCKET_MILLIS
            }

            defaultRequest {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                header(HttpHeaders.UserAgent, "NOW-Android/${BuildConfig.VERSION_NAME}")
                header("x-now-client-build", BuildConfig.VERSION_NAME)
            }
        }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): NowDatabase = NowDatabaseFactory.create(context)

    @Provides
    fun provideCachedStateDao(database: NowDatabase): CachedStateDao =
        database.cachedStateDao()

    @Provides
    fun provideCachedOpportunityDao(
        database: NowDatabase,
    ): com.sagarsystemslab.nownetwork.data.local.CachedOpportunityDao =
        database.cachedOpportunityDao()

    @Provides
    fun provideActiveOperationDao(
        database: NowDatabase,
    ): com.sagarsystemslab.nownetwork.data.local.ActiveOperationDao =
        database.activeOperationDao()

    @Provides
    fun providePendingEvidenceDao(
        database: NowDatabase,
    ): com.sagarsystemslab.nownetwork.data.local.PendingEvidenceDao =
        database.pendingEvidenceDao()

    @Provides
    fun provideWalletSessionMetadataDao(
        database: NowDatabase,
    ): WalletSessionMetadataDao = database.walletSessionMetadataDao()

    @Provides
    @Singleton
    fun provideLocalPersistenceStore(database: NowDatabase): LocalPersistenceStore =
        LocalPersistenceStore(database)

    @Provides
    @Singleton
    fun provideNowApiClient(implementation: KtorNowApiClient): NowApiClient =
        implementation

    @Provides
    @Singleton
    fun provideEvidenceApiClient(implementation: KtorNowApiClient): EvidenceApiClient =
        implementation

    @Provides
    @Singleton
    fun provideEvidenceReadApiClient(
        implementation: KtorNowApiClient,
    ): EvidenceReadApiClient = implementation

    @Provides
    @Singleton
    fun provideVerificationApiClient(
        implementation: KtorNowApiClient,
    ): VerificationApiClient = implementation

    @Provides
    @Singleton
    fun providePaymentApiClient(
        implementation: KtorNowApiClient,
    ): PaymentApiClient = implementation

    @Provides
    @Singleton
    fun provideReceiptApiClient(
        implementation: KtorNowApiClient,
    ): ReceiptApiClient = implementation

    @Provides
    @Singleton
    fun provideSolanaRpcClient(
        implementation: KtorSolanaRpcClient,
    ): SolanaRpcClient = implementation

    @Provides
    @Singleton
    fun provideAuthGateway(runtimeClient: SupabaseRuntimeClient): AuthGateway =
        runtimeClient.client?.let(::SupabaseAuthGateway) ?: UnavailableAuthGateway()
}
