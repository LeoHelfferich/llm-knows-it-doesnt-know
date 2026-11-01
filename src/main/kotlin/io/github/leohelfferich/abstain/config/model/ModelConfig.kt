package io.github.leohelfferich.abstain.config.model

import kotlinx.serialization.Serializable

/** One model in the experiment: alias, OpenRouter id, and how it is called. */
@Serializable
data class ModelConfig(
    val alias: String,
    val openRouterId: String,
    val sendTemperature: Boolean = true,
    val reasoning: ReasoningConfig? = null,
    val assumedReasoningTokens: Int = 0,
)
