package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/** What the strict prompt changed, relative to the neutral prompt, for one model. */
@Serializable
data class Tradeoff(
    val model: String,
    val deltaInvented: Interval,
    val deltaOverRefusal: Interval,
    val deltaExactMatch: Interval,
    val deltaF1: Interval,
    val inventedAvoidedPerExtraRefusal: Double?,
    val pattern: String,
)
