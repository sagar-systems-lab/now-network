package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.data.local.CachedStateEntity
import com.sagarsystemslab.nownetwork.data.local.RevisionDecision
import com.sagarsystemslab.nownetwork.data.local.decideRevision
import com.sagarsystemslab.nownetwork.model.ActiveRefresh
import com.sagarsystemslab.nownetwork.model.NearbyStatePage
import com.sagarsystemslab.nownetwork.model.StateDetail
import com.sagarsystemslab.nownetwork.model.StateLocation
import com.sagarsystemslab.nownetwork.model.StateSummary
import com.sagarsystemslab.nownetwork.model.StateVerification
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.NearbyStateQuery
import com.sagarsystemslab.nownetwork.network.NowApiClient
import com.sagarsystemslab.nownetwork.network.StateDetailDto
import com.sagarsystemslab.nownetwork.network.StateSummaryDto
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface StateRepository {
    fun observeCachedStates(): Flow<List<StateSummary>>

    suspend fun refreshNearby(query: NearbyStateQuery): NearbyStatePage

    suspend fun getState(stateId: String): StateDetail

    suspend fun reconcileRealtimeState(
        stateId: String,
        incomingRevision: Long?,
    ): StateDetail?
}

@Singleton
class DefaultStateRepository @Inject constructor(
    private val api: NowApiClient,
    private val stateCache: StateCache,
    private val json: Json,
    private val serverClock: ServerClock,
) : StateRepository {
    override fun observeCachedStates(): Flow<List<StateSummary>> =
        stateCache.observeAll().map { rows ->
            rows.mapNotNull { row ->
                runCatching {
                    json.decodeFromString(StateSummary.serializer(), row.payloadJson)
                }.getOrNull()
            }
        }

    override suspend fun refreshNearby(query: NearbyStateQuery): NearbyStatePage {
        val remote = api.nearbyStates(query)
        val states = remote.items.map { it.toDomain() }
        val cachedAt = serverClock.nowMillis()

        states.forEach { state ->
            state.toCacheEntity(cachedAt)?.let { entity ->
                stateCache.applySnapshot(entity)
            }
        }

        return NearbyStatePage(
            items = states,
            nextCursor = remote.nextCursor,
        )
    }

    override suspend fun getState(stateId: String): StateDetail {
        val detail = api.stateDetail(stateId).toDomain()
        val previous = stateCache.get(stateId)?.let { row ->
            runCatching {
                json.decodeFromString(StateSummary.serializer(), row.payloadJson)
            }.getOrNull()
        }

        detail.toSummary(previous?.distanceMeters)
            .toCacheEntity(serverClock.nowMillis())
            ?.let { stateCache.applySnapshot(it) }

        return detail
    }

    override suspend fun reconcileRealtimeState(
        stateId: String,
        incomingRevision: Long?,
    ): StateDetail? {
        val currentRevision = stateCache.get(stateId)?.revision
        if (!requiresRealtimeSnapshot(currentRevision, incomingRevision)) {
            return null
        }

        return getState(stateId)
    }

    private fun StateSummary.toCacheEntity(cachedAtMillis: Long): CachedStateEntity? {
        val observed = observedAtMillis ?: return null
        val aging = agingAtMillis ?: return null
        val freshUntil = freshUntilMillis ?: return null

        return CachedStateEntity(
            stateId = stateId,
            revision = revision,
            payloadJson = json.encodeToString(this),
            observedAtMs = observed,
            agingAtMs = aging,
            freshUntilMs = freshUntil,
            cachedAtMs = cachedAtMillis,
        )
    }
}

private fun StateSummaryDto.toDomain(): StateSummary =
    StateSummary(
        stateId = stateId,
        title = title,
        question = question,
        stateType = stateType,
        valueJson = value?.toString(),
        unitCode = unitCode,
        freshnessStatus = freshnessStatus,
        observedAtMillis = observedAt.toEpochMillisOrNull("observed_at"),
        agingAtMillis = agingAt.toEpochMillisOrNull("aging_at"),
        freshUntilMillis = freshUntil.toEpochMillisOrNull("fresh_until"),
        verificationClass = verificationClass,
        refreshStatus = refreshStatus,
        conflictActive = conflictActive,
        distanceMeters = distanceM,
        revision = revision,
        location = location?.let { StateLocation(it.locationId, it.name, it.locationType, it.displayAddress, it.center) },
    )

private fun StateDetailDto.toDomain(): StateDetail =
    StateDetail(
        stateId = stateId,
        version = version,
        canonicalKey = canonicalKey,
        title = title,
        question = question,
        stateType = stateType,
        valueJson = value?.toString(),
        unitCode = unitCode,
        freshnessStatus = freshnessStatus,
        observedAtMillis = observedAt.toEpochMillisOrNull("observed_at"),
        observationEarliestMillis = observationEarliest.toEpochMillisOrNull("observation_earliest"),
        observationLatestMillis = observationLatest.toEpochMillisOrNull("observation_latest"),
        agingAtMillis = agingAt.toEpochMillisOrNull("aging_at"),
        freshUntilMillis = freshUntil.toEpochMillisOrNull("fresh_until"),
        verificationClass = verificationClass,
        conflictActive = conflictActive,
        revision = revision,
        location = StateLocation(
            locationId = location.locationId,
            name = location.name,
            locationType = location.locationType,
            displayAddress = location.displayAddress,
            center = location.center,
        ),
        verification = verification?.let {
            StateVerification(
                status = it.status,
                reasonCodes = it.reasonCodes,
                evidenceCount = it.evidenceCount,
            )
        },
        activeRefresh = activeRefresh?.let {
            ActiveRefresh(
                refreshId = it.refreshId,
                status = it.status,
                verificationClass = it.verificationClass,
                expiresAtMillis = it.expiresAt.toEpochMillis("active_refresh.expires_at"),
                revision = it.revision,
            )
        },
    )

private fun StateDetail.toSummary(distanceMeters: Double?): StateSummary =
    StateSummary(
        stateId = stateId,
        title = title,
        question = question,
        stateType = stateType,
        valueJson = valueJson,
        unitCode = unitCode,
        freshnessStatus = freshnessStatus,
        observedAtMillis = observedAtMillis,
        agingAtMillis = agingAtMillis,
        freshUntilMillis = freshUntilMillis,
        verificationClass = verificationClass,
        refreshStatus = activeRefresh?.status,
        conflictActive = conflictActive,
        distanceMeters = distanceMeters,
        revision = revision,
        location = location,
    )

private fun String?.toEpochMillisOrNull(field: String): Long? =
    this?.toEpochMillis(field)

private fun String.toEpochMillis(field: String): Long =
    try {
        Instant.parse(this).toEpochMilli()
    } catch (error: Exception) {
        throw ApiFailure.ProtocolError("NOW API returned invalid $field", error)
    }


fun requiresRealtimeSnapshot(
    currentRevision: Long?,
    incomingRevision: Long?,
): Boolean {
    if (incomingRevision == null) return true

    return when (decideRevision(currentRevision, incomingRevision)) {
        RevisionDecision.IGNORE_STALE,
        RevisionDecision.IGNORE_DUPLICATE -> false

        RevisionDecision.INSERT,
        RevisionDecision.APPLY_NEXT,
        RevisionDecision.REQUIRE_SNAPSHOT -> true
    }
}
