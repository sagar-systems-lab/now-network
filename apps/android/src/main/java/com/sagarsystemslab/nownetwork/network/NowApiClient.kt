package com.sagarsystemslab.nownetwork.network

data class NearbyStateQuery(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val limit: Int = 30,
    val cursor: String? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude must be within [-90, 90]"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude must be within [-180, 180]"
        }
        require(radiusMeters in 1..50_000) {
            "radiusMeters must be within [1, 50000]"
        }
        require(limit in 1..50) {
            "limit must be within [1, 50]"
        }
        require(cursor == null || cursor.length <= 512) {
            "cursor is too long"
        }
    }
}

data class NearbyOpportunityQuery(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val limit: Int = 30,
    val cursor: String? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(radiusMeters in 1..50_000)
        require(limit in 1..50)
        require(cursor == null || cursor.length <= 512)
    }
}

interface NowApiClient {
    suspend fun nearbyStates(query: NearbyStateQuery): NearbyStatesDto
    suspend fun stateDetail(stateId: String): StateDetailDto
    suspend fun nearbyOpportunities(
        query: NearbyOpportunityQuery,
        accessToken: String,
    ): NearbyOpportunitiesDto
    suspend fun me(accessToken: String): MeDto

    suspend fun walletBindingChallenge(
        request: WalletBindingChallengeRequest,
        accessToken: String,
    ): WalletBindingChallengeDto

    suspend fun verifyWalletBinding(
        request: WalletBindingVerifyRequest,
        accessToken: String,
    ): WalletBindingVerifyDto

    suspend fun createRefresh(
        request: CreateRefreshRequest,
        idempotencyKey: String,
        accessToken: String,
    ): RefreshDto

    suspend fun refreshDetail(
        refreshId: String,
        accessToken: String,
    ): RefreshDto

    suspend fun fundingIntent(
        refreshId: String,
        idempotencyKey: String,
        accessToken: String,
    ): FundingIntentDto

    suspend fun observeFunding(
        refreshId: String,
        request: FundingObserveRequest,
        idempotencyKey: String,
        accessToken: String,
    ): RefreshDto
}
