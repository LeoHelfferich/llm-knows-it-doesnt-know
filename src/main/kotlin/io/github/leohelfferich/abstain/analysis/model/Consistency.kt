package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/** How stable a model is across repetitions of the same item. */
@Serializable
data class Consistency(
    val decisionAgreement: Double?,
    val decisionAgreementUnanswerable: Double?,
    val decisionAgreementAnswerable: Double?,
    val fullyConsistentItems: Double?,
    val answerAgreement: Double?,
)
