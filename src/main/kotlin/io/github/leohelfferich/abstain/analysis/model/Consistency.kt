package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/**
 * How stable a model is across repetitions of the same item.
 * A decision is "abstain" or the outcome name, so the two abstention kinds count as the same decision.
 */
@Serializable
data class Consistency(
    /** Pairwise agreement of the decision, over items of both kinds. */
    val decisionAgreement: Double?,
    val decisionAgreementUnanswerable: Double?,
    val decisionAgreementAnswerable: Double?,
    /** Share of items with at least two reps whose decision did not change. */
    val fullyConsistentItems: Double?,
    /** Pairwise agreement of the normalized answer text, among reps that answered. */
    val answerAgreement: Double?,
)
