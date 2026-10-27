package io.github.leohelfferich.abstain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

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

class ChatException(message: String, val retryable: Boolean, val status: Int? = null) : RuntimeException(message)

interface ChatClient {
    fun complete(request: ChatRequest): ChatResult
}

object Secrets {
    const val ENV_VAR = "OPENROUTER_API_KEY"

    fun apiKeyFromEnv(env: Map<String, String> = System.getenv()): String? =
        env[ENV_VAR]?.trim()?.takeIf { it.isNotEmpty() }

    fun redact(text: String, key: String?): String {
        var out = text
        if (!key.isNullOrBlank()) out = out.replace(key, "[REDACTED]")
        return out.replace(Regex("sk-or-[A-Za-z0-9_\\-]{8,}"), "[REDACTED]")
    }
}

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

    override fun toString() = "OpenRouterClient(baseUrl=$baseUrl, apiKey=[REDACTED])"

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

    override fun complete(request: ChatRequest): ChatResult {
        val body = CompactJson.encodeToString(JsonObject.serializer(), buildBody(request))
        var attempt = 0
        while (true) {
            try {
                return send(body)
            } catch (e: ChatException) {
                if (!e.retryable || attempt >= maxRetries) throw e
            }
            attempt++
            Thread.sleep(backoffMillis * (1L shl (attempt - 1)))
        }
    }

    private fun send(body: String): ChatResult {
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
        } catch (e: HttpTimeoutException) {
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
        return parseResponse(text, latencyMs)
    }

    fun parseResponse(text: String, latencyMs: Long): ChatResult {
        val root = try {
            CompactJson.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
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
            is JsonArray -> c.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
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
