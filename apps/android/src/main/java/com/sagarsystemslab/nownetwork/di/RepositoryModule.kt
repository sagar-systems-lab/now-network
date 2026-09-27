package com.sagarsystemslab.nownetwork.di

import com.sagarsystemslab.nownetwork.repository.DefaultActivityRepository
import com.sagarsystemslab.nownetwork.repository.DefaultOpportunityRepository
import com.sagarsystemslab.nownetwork.repository.DefaultStateRepository
import com.sagarsystemslab.nownetwork.repository.StateCache
import com.sagarsystemslab.nownetwork.repository.StateRepository
import com.sagarsystemslab.nownetwork.repository.ActivityRepository
import com.sagarsystemslab.nownetwork.repository.OpportunityRepository
import com.sagarsystemslab.nownetwork.repository.RoomStateCache
import com.sagarsystemslab.nownetwork.realtime.NowRealtimeGateway
import com.sagarsystemslab.nownetwork.realtime.SupabaseNowRealtimeGateway
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
