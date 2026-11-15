package io.github.leohelfferich.abstain.cost.model

import kotlinx.serialization.Serializable

/**
 * Cost of a whole run (dry run or full) at three levels of caution.
 * Expected uses assumed or observed tokens, pessimistic inflates them, ceiling prices every
 * completion at `maxTokens`. Whether each column fits is `expected EUR <= cap - safety margin`.
 */
@Serializable
data class Estimate(
    val label: String,
    val basis: String,
    val pricingRetrievedOn: String,
    val items: Int,
    val reps: Int,
    val promptVariants: Int,
    val models: List<ModelEstimate>,
    val totalCalls: Int,
    val expectedUsd: Double,
    val pessimisticUsd: Double,
    val ceilingUsd: Double,
    val eurPerUsd: Double,
    val expectedEur: Double,
    val pessimisticEur: Double,
    val ceilingEur: Double,
    val capEur: Double,
    val expectedFitsCap: Boolean,
    val pessimisticFitsCap: Boolean,
)
