package io.github.leohelfferich.abstain.parsing.model

/**
 * @property formatViolation the reply did not follow "ANSWER: ..." / NOT_IN_CONTEXT exactly
 * @property ambiguous the reply is a longer answer that also contains refusal wording; it is counted
 *   as an answer but listed for manual review
 */
data class ParsedResponse(
    val outcome: Outcome,
    val answer: String?,
    val formatViolation: Boolean,
    val ambiguous: Boolean,
)
