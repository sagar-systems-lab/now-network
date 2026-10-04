package com.sagarsystemslab.nownetwork.feature.activity

internal enum class ActivityDestination { RECEIPT, PAYMENT, CAPTURE, CLAIM, VERIFICATION, FUNDING, STATE }

internal fun activityDestination(receiptId: String, paymentStatus: String, claimStatus: String, acceptanceId: String, refreshStatus: String): ActivityDestination = when {
    receiptId.isNotBlank() -> ActivityDestination.RECEIPT
    paymentStatus.isNotBlank() && paymentStatus != "NOT_STARTED" -> ActivityDestination.PAYMENT
    claimStatus in setOf("CLAIMED", "CAPTURE_ACTIVE") && acceptanceId.isNotBlank() -> ActivityDestination.CAPTURE
    claimStatus in setOf("PREPARING", "WALLET_PENDING", "SUBMITTED", "CONFIRMING", "UNKNOWN") -> ActivityDestination.CLAIM
    acceptanceId.isNotBlank() -> ActivityDestination.VERIFICATION
    refreshStatus in setOf("DRAFT", "AWAITING_FUNDING") -> ActivityDestination.FUNDING
    else -> ActivityDestination.STATE
}
