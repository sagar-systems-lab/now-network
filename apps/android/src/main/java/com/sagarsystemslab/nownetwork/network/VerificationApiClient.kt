package com.sagarsystemslab.nownetwork.network

interface VerificationApiClient {
    suspend fun verifyRefresh(
        refreshId: String,
        accessToken: String,
    ): VerificationResultDto
}
