package com.sagarsystemslab.nownetwork.network

interface ReceiptApiClient {
    suspend fun receipt(
        refreshId: String,
        accessToken: String,
    ): ReceiptDto
}
