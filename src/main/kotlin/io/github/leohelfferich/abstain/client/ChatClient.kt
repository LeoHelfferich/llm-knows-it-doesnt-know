package io.github.leohelfferich.abstain.client

import io.github.leohelfferich.abstain.client.model.ChatRequest
import io.github.leohelfferich.abstain.client.model.ChatResult

/** Sends one chat completion. The runner depends on this, not on OpenRouter directly. */
interface ChatClient {
    fun complete(request: ChatRequest): ChatResult
}
