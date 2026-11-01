package io.github.leohelfferich.abstain.client.model

/** A failed completion. `retryable` is false for authentication and other caller errors. */
class ChatException(message: String, val retryable: Boolean, val status: Int? = null) : RuntimeException(message)
