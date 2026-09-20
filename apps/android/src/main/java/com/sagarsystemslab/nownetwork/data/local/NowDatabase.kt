package com.sagarsystemslab.nownetwork.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(
    entities = [
        CachedStateEntity::class,
        CachedOpportunityEntity::class,
        ActiveOperationEntity::class,
        PendingEvidenceEntity::class,
        OutboxEntity::class,
        WalletSessionMetadataEntity::class,
        SyncMetadataEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(LocalTypeConverters::class)
abstract class NowDatabase : RoomDatabase() {
    abstract fun cachedStateDao(): CachedStateDao
    abstract fun cachedOpportunityDao(): CachedOpportunityDao
    abstract fun activeOperationDao(): ActiveOperationDao
    abstract fun pendingEvidenceDao(): PendingEvidenceDao
    abstract fun outboxDao(): OutboxDao
    abstract fun walletSessionMetadataDao(): WalletSessionMetadataDao
    abstract fun syncMetadataDao(): SyncMetadataDao
}

object NowDatabaseMigrations {
    val ALL: Array<Migration> = emptyArray()
}

object NowDatabaseFactory {
    const val DATABASE_NAME = "now_v1.db"

    fun create(context: Context): NowDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            NowDatabase::class.java,
            DATABASE_NAME,
        )
            .addMigrations(*NowDatabaseMigrations.ALL)
            .build()
}
