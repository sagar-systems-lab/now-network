package com.sagarsystemslab.nownetwork.navigation

import kotlinx.serialization.Serializable

@Serializable
data object NowRoute

@Serializable
data object EarnRoute

@Serializable
data object ActivityRoute

@Serializable
data class StateDetailRoute(
    val stateId: String,
)

@Serializable
data class OpportunityRoute(
    val refreshId: String,
)


@Serializable
data class RequesterFundingRoute(
    val stateId: String,
)

@Serializable
data class EvidenceCaptureRoute(
    val acceptanceId: String,
    val refreshId: String,
)

@Serializable
data class VerificationRoute(
    val refreshId: String,
)

@Serializable
data class PaymentRoute(
    val refreshId: String,
)
