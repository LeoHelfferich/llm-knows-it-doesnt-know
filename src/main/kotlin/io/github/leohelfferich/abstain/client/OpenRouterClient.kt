package io.github.leohelfferich.abstain.client

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

import kotlin.math.max

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

import io.github.leohelfferich.abstain.utils.CompactJson
import io.github.leohelfferich.abstain.client.model.ChatRequest
import io.github.leohelfferich.abstain.client.model.ChatResult
import io.github.leohelfferich.abstain.client.model.ChatException

/**
 * OpenRouter chat-completions client.
 *
 * Transient HTTP failures are retried with exponential backoff. A shared gate keeps parallel calls
 * from retrying a 429 together. The key is sent only to HTTPS hosts (loopback is allowed for tests)
 * and is removed from errors and from [toString].
 */
class OpenRouterClient(
    private val apiKey: String,
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    private val maxRetries: Int = 3,
    private val backoffMillis: Long = 1_000,
    private val requestTimeout: Duration = Duration.ofSeconds(120),
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) : ChatClient {
    private val gate = Any()
    private var notBeforeMs = 0L

    init {
        require(!apiKey.contains('\r') && !apiKey.contains('\n')) { "API key must not contain a line break" }
        val endpoint = URI.create(baseUrl)
        val host = endpoint.host?.lowercase()
        val loopback = host == "127.0.0.1" || host == "localhost" || host == "[::1]" || host == "::1"
        require(endpoint.scheme == "https" || loopback) { "Refusing to send the API key to a non-HTTPS host" }
    }

    override fun toString() = "OpenRouterClient(baseUrl=$baseUrl, apiKey=[REDACTED])"

    private fun awaitGate() {
        generateSequence { synchronized(gate) { notBeforeMs - System.currentTimeMillis() } }
            .takeWhile { waitMs -> waitMs > 0 }
            .forEach { waitMs -> Thread.sleep(waitMs) }
    }

    /** Parallel calls otherwise retry a 429 together and stampede the provider limit. */
    private fun delayAfterRateLimit() {
        synchronized(gate) {
            val now = System.currentTimeMillis()
            val maxPauseMs = 30_000L // One 429 must not stall the run for longer than this.
            val step = (backoffMillis * 5).coerceIn(1L, maxPauseMs) // Five backoffs, shared by every in-flight call.
            notBeforeMs = max(notBeforeMs, now) + step
            if (notBeforeMs > now + maxPauseMs) notBeforeMs = now + maxPauseMs
        }
    }

    fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model.openRouterId)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", request.prompt) })
        })
        put("max_tokens", request.maxTokens)
        request.temperature?.let { put("temperature", it) }
        request.model.reasoning?.let { r ->
            put("reasoning", buildJsonObject {
                r.effort?.let { put("effort", it) }
                r.exclude?.let { put("exclude", it) }
            })
        }
        put("usage", buildJsonObject { put("include", true) })
    }

    /**
     * Sends the completion, retrying transient failures with exponential backoff.
     * [maxRetries] is the number of retries after the first attempt, so a budget of 2 is three sends.
     */
    override fun complete(request: ChatRequest): ChatResult {
        val body = CompactJson.encodeToString(JsonObject.serializer(), buildBody(request))
        for (attempt in 0..maxRetries) {
            try {
                return send(body)
            } catch (e: ChatException) {
                if (e.status == 429) delayAfterRateLimit()
                if (!e.retryable || attempt == maxRetries) throw e
                Thread.sleep(backoffMillis * (1L shl attempt))
            }
        }
        error("retry loop exited without a result")
    }

    private fun send(body: String): ChatResult {
        awaitGate()
        val httpRequest = HttpRequest.newBuilder(URI.create("$baseUrl/chat/completions"))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "llm-knows-it-doesnt-know")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val started = System.nanoTime()
        val response = try {
            http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (_: HttpTimeoutException) {
            throw ChatException("Request timed out", retryable = true)
        } catch (e: IOException) {
            throw ChatException("Network error: ${e.javaClass.simpleName}", retryable = true)
        }
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        val status = response.statusCode()
        val text = response.body().orEmpty()
        if (status !in 200..299) {
            throw ChatException(
                Secrets.redact("HTTP $status: ${text.take(300)}", apiKey),
                retryable = status == 408 || status == 429 || status >= 500,
                status = status,
            )
        }
        return try {
            parseResponse(text, latencyMs)
        } catch (e: ChatException) {
            throw e
        } catch (_: Exception) {
            throw ChatException("Unparseable response body", retryable = false)
        }
    }

    fun parseResponse(text: String, latencyMs: Long): ChatResult {
        val root = try {
            CompactJson.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            throw ChatException("Unparseable response body", retryable = true)
        }
        (root["error"] as? JsonObject)?.let { err ->
            val code = err["code"]?.jsonPrimitive?.intOrNull
            val message = err["message"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
            throw ChatException(
                Secrets.redact("API error ${code ?: ""}: ${message.take(300)}", apiKey),
                retryable = code == null || code == 408 || code == 429 || code >= 500,
                status = code,
            )
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw ChatException("Response without choices", retryable = true)
        val content = when (val c = choice["message"]?.jsonObject?.get("content")) {
            null, JsonNull -> null
            is JsonPrimitive -> c.contentOrNull
            is JsonArray -> buildString {
                for (part in c) {
                    val textPart = (part as? JsonObject)?.get("text") as? JsonPrimitive ?: continue
                    append(textPart.contentOrNull.orEmpty())
                }
            }
            else -> null
        }
        val usage = root["usage"] as? JsonObject
        return ChatResult(
            content = content,
            finishReason = choice["finish_reason"].asString(),
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull,
            reasoningTokens = (usage?.get("completion_tokens_details") as? JsonObject)?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull,
            costUsd = usage?.get("cost")?.jsonPrimitive?.doubleOrNull,
            provider = root["provider"].asString(),
            responseModel = root["model"].asString(),
            generationId = root["id"].asString(),
            latencyMs = latencyMs,
        )
    }

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}
