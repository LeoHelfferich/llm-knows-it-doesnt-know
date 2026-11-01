package io.github.leohelfferich.abstain.client

import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import io.github.leohelfferich.abstain.config.model.ModelConfig
import io.github.leohelfferich.abstain.utils.CompactJson
import io.github.leohelfferich.abstain.testing.testModels
import io.github.leohelfferich.abstain.client.model.ChatRequest
import io.github.leohelfferich.abstain.client.model.ChatException

class OpenRouterClientTest {
    private val key = "sk-or-v1-0123456789abcdef0123456789abcdef"
    private var server: HttpServer? = null
    private val bodies = mutableListOf<String>()
    private val authHeaders = mutableListOf<String?>()

    @AfterEach
    fun stop() { server?.stop(0) }

    private val okBody = """{"id":"gen-9","provider":"Acme","model":"test/alpha-2026",
        "choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ANSWER: Paris"}}],
        "usage":{"prompt_tokens":120,"completion_tokens":30,"completion_tokens_details":{"reasoning_tokens":22},"cost":0.000123}}"""

    @Test
    fun `should send model, prompt, max tokens, temperature, reasoning, and usage`() {
        val client = OpenRouterClient(key, serve(200 to okBody), backoffMillis = 1)
        val result = client.complete(request(testModels()[0], 0.7))

        // ---
        val body = CompactJson.parseToJsonElement(bodies.single()).jsonObject
        // ---

        assertThat(body.getValue("model").jsonPrimitive.content).isEqualTo("test/alpha")
        assertThat(body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content).isEqualTo("PROMPT TEXT")
        assertThat(body.getValue("max_tokens").jsonPrimitive.content).isEqualTo("77")
        assertThat(body.getValue("temperature").jsonPrimitive.content).isEqualTo("0.7")
        assertThat(body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content).isEqualTo("low")
        assertThat(body.getValue("usage").jsonObject.getValue("include").jsonPrimitive.boolean).isTrue()
        assertThat(authHeaders.single()).isEqualTo("Bearer $key")
        assertThat(result.content).isEqualTo("ANSWER: Paris")
        assertThat(result.promptTokens).isEqualTo(120)
        assertThat(result.completionTokens).isEqualTo(30)
        assertThat(result.reasoningTokens).isEqualTo(22)
        assertThat(result.costUsd).isEqualTo(0.000123)
        assertThat(result.provider).isEqualTo("Acme")
        assertThat(result.responseModel).isEqualTo("test/alpha-2026")
    }

    @Test
    fun `should omit temperature and reasoning when they are not configured`() {
        val client = OpenRouterClient(key, serve(200 to okBody), backoffMillis = 1)
        client.complete(request(testModels()[1], null))

        // ---
        val body = CompactJson.parseToJsonElement(bodies.single()).jsonObject
        // ---

        assertThat(body.containsKey("temperature")).isFalse()
        assertThat(body.containsKey("reasoning")).isFalse()
    }

    @Test
    fun `should retry transient failures and then succeed`() {
        val base = serve(429 to """{"error":{"message":"slow down","code":429}}""", 503 to "upstream", 200 to okBody)

        // ---
        val result = OpenRouterClient(key, base, maxRetries = 3, backoffMillis = 1).complete(request(testModels()[0], 0.7))
        // ---

        assertThat(result.content).isEqualTo("ANSWER: Paris")
        assertThat(bodies.size).isEqualTo(3)
    }

    @Test
    fun `should give up after the retry budget`() {
        val base = serve(500 to "boom")

        // ---
        val e = catchThrowableOfType(ChatException::class.java) { OpenRouterClient(key, base, maxRetries = 2, backoffMillis = 1)
            .complete(request(testModels()[0], 0.7)) }
        // ---

        assertThat(e.retryable).isTrue()
        assertThat(bodies.size).isEqualTo(3)
    }

    @Test
    fun `should not retry authentication errors`() {
        val base = serve(401 to """{"error":{"message":"bad key $key","code":401}}""")

        // ---
        val e = catchThrowableOfType(ChatException::class.java) { OpenRouterClient(key, base, maxRetries = 3, backoffMillis = 1)
            .complete(request(testModels()[0], 0.7)) }
        // ---

        assertThat(e.status).isEqualTo(401)
        assertThat(e.retryable).isFalse()
        assertThat(bodies.size).isEqualTo(1)
    }

    @Test
    fun `should keep the api key out of error messages and toString`() {
        val base = serve(400 to """{"error":{"message":"invalid key $key supplied"}}""")
        val client = OpenRouterClient(key, base, backoffMillis = 1)

        // ---
        val e = catchThrowableOfType(ChatException::class.java) { client.complete(request(testModels()[0], 0.7)) }
        // ---

        assertThat(e.message.orEmpty()).doesNotContain(key)
        assertThat(e.message.orEmpty()).contains("[REDACTED]")
        assertThat(client.toString()).doesNotContain(key)
    }

    @Test
    fun `should turn an error object inside a 200 response into an exception`() {
        val base = serve(200 to """{"error":{"message":"provider error","code":502}}""")

        // ---
        val e = catchThrowableOfType(ChatException::class.java) { OpenRouterClient(key, base, maxRetries = 0, backoffMillis = 1)
            .complete(request(testModels()[0], 0.7)) }
        // ---

        assertThat(e.status).isEqualTo(502)
    }

    @Test
    fun `should tolerate null content and missing usage`() {
        val base = serve(200 to """{"choices":[{"finish_reason":"length","message":{"content":null}}]}""")

        // ---
        val r = OpenRouterClient(key, base, backoffMillis = 1).complete(request(testModels()[0], 0.7))
        // ---

        assertThat(r.content).isNull()
        assertThat(r.finishReason).isEqualTo("length")
        assertThat(r.costUsd).isNull()
    }

    @Test
    fun `should redact a configured key and a key-shaped secret`() {
        val withTheConfiguredKey = "a $key b"
        val withAKeyShapedSecret = "a sk-or-v1-zzzzzzzzzzzzzzzz b"

        // ---
        val configuredRedacted = Secrets.redact(withTheConfiguredKey, key)
        val shapedRedacted = Secrets.redact(withAKeyShapedSecret, null)
        // ---

        assertThat(configuredRedacted).isEqualTo("a [REDACTED] b")
        assertThat(shapedRedacted).isEqualTo("a [REDACTED] b")
    }

    @Test
    fun `should reject a line break in the key or a non-https host`() {
        val keyWithALineBreak = "abc\ndef"
        val plainHttp = "http://example.com"

        // ---
        val lineBreakRejected = catchThrowable { OpenRouterClient(keyWithALineBreak) }
        val plainHttpRejected = catchThrowable { OpenRouterClient(key, plainHttp) }
        // ---

        assertThat(lineBreakRejected).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(plainHttpRejected).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `should read text out of a content array that contains stray parts`() {
        val body = """{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"text","text":"ANSWER: Paris"},"nope"]}}]}"""

        // ---
        val client = OpenRouterClient(key, serve(200 to body), backoffMillis = 1)
        // ---

        assertThat(client.complete(request(testModels()[0], 0.7)).content).isEqualTo("ANSWER: Paris")
    }

    @Test
    fun `should read the api key from the environment and trim it`() {
        val padded = mapOf("OPENROUTER_API_KEY" to " abc\n")
        val blank = mapOf("OPENROUTER_API_KEY" to "  ")
        val missing = emptyMap<String, String>()

        // ---
        val trimmed = Secrets.apiKeyFromEnv(padded)
        val blankIgnored = Secrets.apiKeyFromEnv(blank)
        val absent = Secrets.apiKeyFromEnv(missing)
        // ---

        assertThat(trimmed).isEqualTo("abc")
        assertThat(blankIgnored).isNull()
        assertThat(absent).isNull()
    }

    private fun serve(vararg responses: Pair<Int, String>): String {
        val counter = AtomicInteger()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/v1/chat/completions") { ex ->
            synchronized(bodies) {
                bodies += ex.requestBody.readBytes().decodeToString()
                authHeaders += ex.requestHeaders.getFirst("Authorization")
            }
            val (status, body) = responses[minOf(counter.getAndIncrement(), responses.size - 1)]
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/api/v1"
    }

    private fun request(model: ModelConfig, temperature: Double?) = ChatRequest(model, "PROMPT TEXT", temperature, 77)
}
