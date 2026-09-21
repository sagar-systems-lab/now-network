package com.sagarsystemslab.nownetwork.data.local

import androidx.room.TypeConverter

class LocalTypeConverters {
    @TypeConverter
    fun outboxStatusToString(value: LocalOutboxStatus): String = value.name

    @TypeConverter
    fun stringToOutboxStatus(value: String): LocalOutboxStatus =
        LocalOutboxStatus.valueOf(value)

    @TypeConverter
    fun evidenceStatusToString(value: PendingEvidenceStatus): String = value.name

    @TypeConverter
    fun stringToEvidenceStatus(value: String): PendingEvidenceStatus =
        PendingEvidenceStatus.valueOf(value)
}
