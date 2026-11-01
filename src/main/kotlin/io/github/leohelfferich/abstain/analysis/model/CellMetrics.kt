package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/** Metrics for one model and one prompt, on the items that were not excluded. */
@Serializable
data class CellMetrics(
    val model: String,
    val prompt: String,
    val unanswerableItems: Int,
    val answerableItems: Int,
    val unanswerableCalls: Int,
    val answerableCalls: Int,
    val invented: Interval,
    val overRefusal: Interval,
    val exactMatch: Interval,
    val f1: Interval,
    val exactMatchWhenAnswered: Double?,
    val f1WhenAnswered: Double?,
    val abstentionBalancedAccuracy: Double?,
    val inventedMatchesPlausible: Double?,
    val sentinelAbstentions: Int,
    val phraseAbstentions: Int,
    val emptyCalls: Int,
    val formatViolations: Int,
    val ambiguousCalls: Int,
    val consistency: Consistency,
    val costUsd: Double,
    val meanCompletionTokens: Double?,
)
