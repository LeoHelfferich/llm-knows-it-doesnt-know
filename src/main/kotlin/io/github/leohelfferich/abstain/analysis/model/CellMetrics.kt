package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/**
 * Metrics for one model and one prompt, on the items that were not excluded.
 * Rates are means of per-item rates. An item with no successful call is absent, not a zero.
 */
@Serializable
data class CellMetrics(
    val model: String,
    val prompt: String,
    /** Unanswerable items with at least one successful call. */
    val unanswerableItems: Int,
    /** Answerable items with at least one successful call. */
    val answerableItems: Int,
    val unanswerableCalls: Int,
    val answerableCalls: Int,
    /** Share of unanswerable items on which the model gave an answer. */
    val invented: Interval,
    /** Share of answerable items on which the model abstained. */
    val overRefusal: Interval,
    /** Exact match on answerable items. An abstention scores 0. */
    val exactMatch: Interval,
    /** Token F1 on answerable items. An abstention scores 0. */
    val f1: Interval,
    /** Exact match over answerable calls that did answer. Abstentions are left out. */
    val exactMatchWhenAnswered: Double?,
    /** Token F1 over the same calls as [exactMatchWhenAnswered]. */
    val f1WhenAnswered: Double?,
    /** `((1 - invented) + (1 - overRefusal)) / 2`. Null when either rate is missing. */
    val abstentionBalancedAccuracy: Double?,
    /** Of invented answers, the share with token F1 of at least 0.5 against a plausible span. */
    val inventedMatchesPlausible: Double?,
    /** Replies that were the sentinel `NOT_IN_CONTEXT`. */
    val sentinelAbstentions: Int,
    /** Short replies that matched the refusal lexicon. The neutral prompt has no sentinel. */
    val phraseAbstentions: Int,
    val emptyCalls: Int,
    val formatViolations: Int,
    /** Answers that also contain refusal wording and were too long to count as an abstention. */
    val ambiguousCalls: Int,
    val consistency: Consistency,
    /** Sum of recorded `costUsd` on the calls in this cell. */
    val costUsd: Double,
    val meanCompletionTokens: Double?,
)
