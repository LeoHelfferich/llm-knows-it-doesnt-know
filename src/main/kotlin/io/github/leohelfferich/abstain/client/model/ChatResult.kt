package io.github.leohelfferich.abstain.client.model

/** What came back from one completion: text, token counts, reported cost, and provider identity. */
data class ChatResult(
    val content: String?,
    val finishReason: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val reasoningTokens: Int?,
    val costUsd: Double?,
    val provider: String?,
    val responseModel: String?,
    val generationId: String?,
    val latencyMs: Long,
)
