package io.github.leohelfferich.abstain.client.model

import io.github.leohelfferich.abstain.config.model.ModelConfig
import io.github.leohelfferich.abstain.prompt.model.PromptVariant

/**
 * One chat completion.
 *
 * [itemId], [variant], and [rep] are local bookkeeping for the runner and the offline simulator.
 * They are never sent to the API.
 */
data class ChatRequest(
    val model: ModelConfig,
    val prompt: String,
    val temperature: Double?,
    val maxTokens: Int,
    /** Local bookkeeping only (used by the offline simulator); never sent to the API. */
    val itemId: String? = null,
    val variant: PromptVariant? = null,
    val rep: Int = 1,
)
