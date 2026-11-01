package io.github.leohelfferich.abstain.prompt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The two prompt wordings compared by the experiment. */
@Serializable
enum class PromptVariant(val key: String) {
    @SerialName("neutral") NEUTRAL("neutral"),
    @SerialName("strict") STRICT("strict"),
}
