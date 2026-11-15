package io.github.leohelfferich.abstain.analysis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

import io.github.leohelfferich.abstain.execution.model.CallRecord
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.squad.model.Kind

class RunFactsTest {
    @Test
    fun `should count successful calls by provider and ignore retries`() {
        // The retry reuses the successful call's key. Provider is not part of that key.
        val succeeded = ok("llama", "DeepInfra", 1)
        val calls = listOf(
            succeeded,
            ok("llama", "DeepInfra", 2),
            ok("llama", "Groq", 3),
            ok("grok", "xAI", 1),
            succeeded.copy(status = "error", provider = null, error = "HTTP 429: slow down"),
        )

        // ---
        val serving = RunFacts.servingProviders(calls, listOf("grok", "llama"))
        // ---

        assertThat(serving.map { Triple(it.model, it.provider, it.calls) }).containsExactly(
            Triple("grok", "xAI", 1),
            Triple("llama", "DeepInfra", 2),
            Triple("llama", "Groq", 1),
        )
    }

    @Test
    fun `should group failed attempts by HTTP status`() {
        val calls = listOf(
            ok("grok", "xAI", 1),
            ok("grok", "xAI", 1).copy(status = "error", error = "HTTP 429: limit"),
            ok("grok", "xAI", 2).copy(status = "error", error = "HTTP 429: limit"),
            ok("grok", "xAI", 3).copy(status = "error", error = "connection reset"),
        )

        // ---
        val counts = RunFacts.failedAttemptCounts(calls)
        // ---

        assertThat(counts).containsExactlyEntriesOf(mapOf("429" to 2, "other" to 1))
    }

    private fun ok(model: String, provider: String, rep: Int) = CallRecord(
        itemId = "a0",
        kind = Kind.ANSWERABLE,
        model = model,
        openRouterId = "test/$model",
        prompt = PromptVariant.NEUTRAL,
        rep = rep,
        status = "ok",
        requestedAt = "t",
        temperature = 0.0,
        promptChars = 10,
        provider = provider,
    )
}
