package io.github.leohelfferich.abstain

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenRouterClientTest {
    private val key = "sk-or-v1-0123456789abcdef0123456789abcdef"
    private var server: HttpServer? = null
    private val bodies = mutableListOf<String>()
    private val authHeaders = mutableListOf<String?>()

    @AfterTest
    fun stop() { server?.stop(0) }

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

    private val okBody = """{"id":"gen-9","provider":"Acme","model":"test/alpha-2026",
        "choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ANSWER: Paris"}}],
        "usage":{"prompt_tokens":120,"completion_tokens":30,"completion_tokens_details":{"reasoning_tokens":22},"cost":0.000123}}"""

    private fun request(model: ModelConfig, temperature: Double?) = ChatRequest(model, "PROMPT TEXT", temperature, 77)

    @Test
    fun `sends model, prompt, max tokens, temperature, reasoning and usage accounting`() {
        val client = OpenRouterClient(key, serve(200 to okBody), backoffMillis = 1)
        val result = client.complete(request(testModels()[0], 0.7))
        val body = CompactJson.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("test/alpha", body.getValue("model").jsonPrimitive.content)
        assertEquals("PROMPT TEXT", body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("77", body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals("0.7", body.getValue("temperature").jsonPrimitive.content)
        assertEquals("low", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertTrue(body.getValue("usage").jsonObject.getValue("include").jsonPrimitive.boolean)
        assertEquals("Bearer $key", authHeaders.single())

        assertEquals("ANSWER: Paris", result.content)
        assertEquals(120, result.promptTokens)
        assertEquals(30, result.completionTokens)
        assertEquals(22, result.reasoningTokens)
        assertEquals(0.000123, result.costUsd)
        assertEquals("Acme", result.provider)
        assertEquals("test/alpha-2026", result.responseModel)
    }

    @Test
    fun `temperature and reasoning are omitted when not configured`() {
        val client = OpenRouterClient(key, serve(200 to okBody), backoffMillis = 1)
        client.complete(request(testModels()[1], null))
        val body = CompactJson.parseToJsonElement(bodies.single()).jsonObject
        assertFalse("temperature" in body)
        assertFalse("reasoning" in body)
    }

    @Test
    fun `retries transient failures and then succeeds`() {
        val base = serve(429 to """{"error":{"message":"slow down","code":429}}""", 503 to "upstream", 200 to okBody)
        val result = OpenRouterClient(key, base, maxRetries = 3, backoffMillis = 1).complete(request(testModels()[0], 0.7))
        assertEquals("ANSWER: Paris", result.content)
        assertEquals(3, bodies.size)
    }

    @Test
    fun `gives up after the retry budget`() {
        val base = serve(500 to "boom")
        val e = assertFailsWith<ChatException> { OpenRouterClient(key, base, maxRetries = 2, backoffMillis = 1).complete(request(testModels()[0], 0.7)) }
        assertTrue(e.retryable)
        assertEquals(3, bodies.size)
    }

    @Test
    fun `does not retry authentication errors`() {
        val base = serve(401 to """{"error":{"message":"bad key $key","code":401}}""")
        val e = assertFailsWith<ChatException> { OpenRouterClient(key, base, maxRetries = 3, backoffMillis = 1).complete(request(testModels()[0], 0.7)) }
        assertEquals(401, e.status)
        assertFalse(e.retryable)
        assertEquals(1, bodies.size)
    }

    @Test
    fun `the api key never appears in error messages or toString`() {
        val base = serve(400 to """{"error":{"message":"invalid key $key supplied"}}""")
        val client = OpenRouterClient(key, base, backoffMillis = 1)
        val e = assertFailsWith<ChatException> { client.complete(request(testModels()[0], 0.7)) }
        assertFalse(key in e.message.orEmpty())
        assertTrue("[REDACTED]" in e.message.orEmpty())
        assertFalse(key in client.toString())
    }

    @Test
    fun `error object inside a 200 response becomes an exception`() {
        val base = serve(200 to """{"error":{"message":"provider error","code":502}}""")
        val e = assertFailsWith<ChatException> { OpenRouterClient(key, base, maxRetries = 0, backoffMillis = 1).complete(request(testModels()[0], 0.7)) }
        assertEquals(502, e.status)
    }

    @Test
    fun `null content and missing usage are tolerated`() {
        val base = serve(200 to """{"choices":[{"finish_reason":"length","message":{"content":null}}]}""")
        val r = OpenRouterClient(key, base, backoffMillis = 1).complete(request(testModels()[0], 0.7))
        assertNull(r.content)
        assertEquals("length", r.finishReason)
        assertNull(r.costUsd)
    }

    @Test
    fun `redact removes configured and key-shaped secrets`() {
        assertEquals("a [REDACTED] b", Secrets.redact("a $key b", key))
        assertEquals("a [REDACTED] b", Secrets.redact("a sk-or-v1-zzzzzzzzzzzzzzzz b", null))
    }

    @Test
    fun `api key is read from the environment and trimmed`() {
        assertEquals("abc", Secrets.apiKeyFromEnv(mapOf("OPENROUTER_API_KEY" to " abc\n")))
        assertNull(Secrets.apiKeyFromEnv(mapOf("OPENROUTER_API_KEY" to "  ")))
        assertNull(Secrets.apiKeyFromEnv(emptyMap()))
    }
}
