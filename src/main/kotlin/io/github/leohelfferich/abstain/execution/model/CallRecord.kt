package io.github.leohelfferich.abstain.execution.model

import kotlinx.serialization.Serializable

import io.github.leohelfferich.abstain.execution.callKey
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.squad.model.Kind

/** One attempt, successful or not, appended to `calls.jsonl` as soon as it finishes. */
@Serializable
data class CallRecord(
    val itemId: String,
    val kind: Kind,
    val model: String,
    val openRouterId: String,
    val prompt: PromptVariant,
    /** 1-based repetition. The same key with a later `"ok"` replaces an earlier error. */
    val rep: Int,
    /** `"ok"` or `"error"`. Only `"ok"` is skipped on resume and scored. */
    val status: String,
    val requestedAt: String,
    val temperature: Double?,
    val promptChars: Int,
    val content: String? = null,
    val finishReason: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val reasoningTokens: Int? = null,
    /** USD counted against the cap. Zero on an error. See `payableUsd`. */
    val costUsd: Double? = null,
    /** `"reported"`, `"computed"`, `"worst-case"`, or `"none"` when the call failed. */
    val costSource: String? = null,
    val provider: String? = null,
    val responseModel: String? = null,
    val generationId: String? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
) {
    val key get() = callKey(itemId, model, prompt, rep)
}
