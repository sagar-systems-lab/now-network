package com.sagarsystemslab.nownetwork.network

interface EvidenceReadApiClient {
    suspend fun opportunityDetail(
        refreshId: String,
        accessToken: String,
    ): OpportunityDto

    suspend fun claimDetail(
        acceptanceId: String,
        accessToken: String,
    ): ClaimStatusDto
}
