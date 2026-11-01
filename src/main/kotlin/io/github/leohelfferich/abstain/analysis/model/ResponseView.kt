package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

@Serializable
data class ResponseView(
    val model: String,
    val prompt: String,
    val rep: Int,
    val outcome: String,
    val answer: String?,
    /** Raw reply, only included when it differs from the standard `ANSWER: <x>` / sentinel form. */
    val raw: String?,
    val formatViolation: Boolean,
    val ambiguous: Boolean,
    val correct: Boolean?,
)
