package com.sagarsystemslab.nownetwork.repository

import com.sagarsystemslab.nownetwork.auth.AuthGateway
import com.sagarsystemslab.nownetwork.data.local.CachedOpportunityDao
import com.sagarsystemslab.nownetwork.data.local.CachedOpportunityEntity
import com.sagarsystemslab.nownetwork.model.OpportunityPage
import com.sagarsystemslab.nownetwork.model.OpportunitySummary
import com.sagarsystemslab.nownetwork.model.StateSummary
import com.sagarsystemslab.nownetwork.network.ApiFailure
import com.sagarsystemslab.nownetwork.network.NearbyOpportunityQuery
import com.sagarsystemslab.nownetwork.network.NowApiClient
import com.sagarsystemslab.nownetwork.network.OpportunityDto
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

interface OpportunityRepository {
    fun observeCached(): Flow<List<OpportunitySummary>>

    suspend fun refreshNearby(query: NearbyOpportunityQuery): OpportunityPage
}

@Singleton
class DefaultOpportunityRepository @Inject constructor(
    private val api: NowApiClient,
    private val auth: AuthGateway,
    private val opportunityDao: CachedOpportunityDao,
    stateRepository: StateRepository,
    private val serverClock: ServerClock,
) : OpportunityRepository {
    private val cachedStates = stateRepository.observeCachedStates()

    override fun observeCached(): Flow<List<OpportunitySummary>> =
        combine(
            opportunityDao.observeAll(),
            cachedStates,
        ) { opportunities, states ->
            val now = serverClock.nowMillis()
            val statesById = states.associateBy(StateSummary::stateId)

            opportunities
                .asSequence()
                .filter { it.refreshExpiresAtMs > now }
                .map { entity -> entity.toDomain(statesById[entity.stateId]) }
                .sortedWith(
                    compareBy<OpportunitySummary>(
                        { !it.claimable },
                        { it.expiresAtMillis },
                        { it.title },
                    ),
                )
                .toList()
        }

    override suspend fun refreshNearby(query: NearbyOpportunityQuery): OpportunityPage {
        val session = auth.ensureAnonymousSession()
        val remote = try {
            api.nearbyOpportunities(
                query = query,
                accessToken = session.accessToken,
            )
        } catch (error: ApiFailure.AuthExpired) {
            val refreshed = auth.refreshIfNeeded()
            api.nearbyOpportunities(
                query = query,
                accessToken = refreshed.accessToken,
            )
        }

        val cachedAt = serverClock.nowMillis()
        val items = remote.items.map { it.toDomain() }

        items.forEach { item ->
            opportunityDao.upsert(
                CachedOpportunityEntity(
                    refreshId = item.refreshId,
                    stateId = item.stateId,
                    revision = item.revision,
                    status = if (item.claimable) "CLAIMABLE" else "UNAVAILABLE",
                    rewardAmountAtomic = item.rewardAtomic,
                    rewardMint = item.rewardMint,
                    claimDeadlineMs = null,
                    evidenceDeadlineMs = item.evidenceDeadlineMillis,
                    refreshExpiresAtMs = item.expiresAtMillis,
                    cachedAtMs = cachedAt,
                ),
            )
        }

        return OpportunityPage(
            items = items,
            nextCursor = remote.nextCursor,
        )
    }
}

private fun OpportunityDto.toDomain(): OpportunitySummary =
    OpportunitySummary(
        refreshId = refreshId,
        stateId = stateId,
        title = title,
        question = question,
        locationName = location.name,
        displayAddress = location.displayAddress,
        rewardAtomic = reward.poolAtomic,
        rewardMint = reward.mint,
        distanceMeters = distanceM,
        expiresAtMillis = expiresAt.toEpochMillis("expires_at"),
        evidenceDeadlineMillis = evidenceDeadline.toEpochMillis("evidence_deadline"),
        verificationClass = verificationClass,
        mediaRequired = evidenceSummary.mediaRequired,
        locationRequired = evidenceSummary.locationRequired,
        claimable = availability.claimable,
        remainingSlots = availability.remainingSlots,
        revision = revision,
        cachedOnly = false,
        center = location.center,
        payoutRule = reward.payoutRule,
        requiredWitnesses = evidenceSummary.requiredWitnesses,
    )

private fun CachedOpportunityEntity.toDomain(
    state: StateSummary?,
): OpportunitySummary =
    OpportunitySummary(
        refreshId = refreshId,
        stateId = stateId,
        title = state?.title ?: "Refresh available state",
        question = state?.question ?: "Current state needs fresh verification.",
        locationName = null,
        displayAddress = null,
        rewardAtomic = rewardAmountAtomic,
        rewardMint = rewardMint,
        distanceMeters = null,
        expiresAtMillis = refreshExpiresAtMs,
        evidenceDeadlineMillis = evidenceDeadlineMs,
        verificationClass = null,
        mediaRequired = null,
        locationRequired = null,
        claimable = status == "CLAIMABLE",
        remainingSlots = null,
        revision = revision,
        cachedOnly = true,
    )

private fun String.toEpochMillis(field: String): Long =
    try {
        Instant.parse(this).toEpochMilli()
    } catch (error: Exception) {
        throw ApiFailure.ProtocolError("NOW API returned invalid $field", error)
    }
