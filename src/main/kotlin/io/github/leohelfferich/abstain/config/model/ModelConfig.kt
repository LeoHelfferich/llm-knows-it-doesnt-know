package io.github.leohelfferich.abstain.config.model

import kotlinx.serialization.Serializable

/**
 * One model in the experiment: the name used in results, the OpenRouter id that is called,
 * and the request fields that differ between models.
 */
@Serializable
data class ModelConfig(
    /** Short name in the call log and the result tables. */
    val alias: String,
    /** Floating OpenRouter id. The manifest also records the canonical slug at run time. */
    val openRouterId: String,
    /**
     * When false, `temperature` is left out of the request.
     * Must match `temperature` being present in the snapshot's `supported_parameters`.
     */
    val sendTemperature: Boolean = true,
    /** Sent as the OpenRouter `reasoning` object. Null means the field is omitted. */
    val reasoning: ReasoningConfig? = null,
    /** Added to [ExperimentConfig.assumedAnswerTokens] when no earlier run has been observed. */
    val assumedReasoningTokens: Int = 0,
)
