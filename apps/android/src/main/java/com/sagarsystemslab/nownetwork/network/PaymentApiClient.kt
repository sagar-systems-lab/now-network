package com.sagarsystemslab.nownetwork.network

interface PaymentApiClient {
    suspend fun paymentStatus(
        refreshId: String,
        accessToken: String,
    ): PaymentStatusDto
}
