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
