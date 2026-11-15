package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/**
 * What the strict prompt changed, relative to the neutral prompt, for one model.
 * Each delta is strict minus neutral, paired on the same item. The labels are decided in `Analyzer.tradeoff`.
 */
@Serializable
data class Tradeoff(
    val model: String,
    /** Negative means the strict prompt invented less. */
    val deltaInvented: Interval,
    /** Positive means the strict prompt refused more answerable questions. */
    val deltaOverRefusal: Interval,
    val deltaExactMatch: Interval,
    val deltaF1: Interval,
    /**
     * Inventions avoided per extra refusal, `-deltaInvented / deltaOverRefusal`.
     * Set only when invention fell and over-refusal rose.
     */
    val inventedAvoidedPerExtraRefusal: Double?,
    /**
     * `"trade-off"`, `"invention-down-without-clear-over-refusal"`,
     * `"over-refusal-up-without-clear-invention-drop"`, `"no-clear-effect"`, or `"insufficient-data"`.
     * "Clear" means the whole bootstrap interval is on one side of zero.
     */
    val pattern: String,
)
