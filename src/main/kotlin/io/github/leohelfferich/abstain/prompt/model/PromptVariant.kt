package io.github.leohelfferich.abstain.prompt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The two prompt wordings compared by the experiment.
 * [NEUTRAL] only asks for an answer. [STRICT] also allows the sentinel `NOT_IN_CONTEXT`.
 * The templates live in `Prompts`.
 */
@Serializable
enum class PromptVariant(val key: String) {
    /** No abstention instruction. A refusal has to be recognized from the wording. */
    @SerialName("neutral") NEUTRAL("neutral"),
    /** Tells the model to reply `NOT_IN_CONTEXT` when the passage does not contain the answer. */
    @SerialName("strict") STRICT("strict"),
}
