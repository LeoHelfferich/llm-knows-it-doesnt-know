package io.github.leohelfferich.abstain.cost.model

import kotlinx.serialization.Serializable

/** Expected, pessimistic, and ceiling cost for one model. */
@Serializable
data class ModelEstimate(
    val alias: String,
    val openRouterId: String,
    val calls: Int,
    val inputTokensPerCall: Double,
    val expectedOutputTokensPerCall: Double,
    val pessimisticOutputTokensPerCall: Double,
    val ceilingOutputTokensPerCall: Double,
    val promptUsdPerMillion: Double,
    val completionUsdPerMillion: Double,
    val expectedUsd: Double,
    val pessimisticUsd: Double,
    val ceilingUsd: Double,
)
