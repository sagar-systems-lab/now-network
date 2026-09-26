package com.sagarsystemslab.nownetwork.di

import com.sagarsystemslab.nownetwork.repository.DefaultStateRepository
import com.sagarsystemslab.nownetwork.repository.StateCache
import com.sagarsystemslab.nownetwork.repository.StateRepository
import com.sagarsystemslab.nownetwork.repository.RoomStateCache
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
    abstract fun bindStateCache(
        implementation: RoomStateCache,
    ): StateCache

    @Binds
    @Singleton
    abstract fun bindStateRepository(
        implementation: DefaultStateRepository,
    ): StateRepository
}
