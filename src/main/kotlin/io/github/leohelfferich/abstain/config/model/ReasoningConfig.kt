package io.github.leohelfferich.abstain.config.model

import kotlinx.serialization.Serializable

/** OpenRouter `reasoning` object sent with a model that reasons by default. */
@Serializable
data class ReasoningConfig(
    val effort: String? = null,
    val exclude: Boolean? = null,
)
