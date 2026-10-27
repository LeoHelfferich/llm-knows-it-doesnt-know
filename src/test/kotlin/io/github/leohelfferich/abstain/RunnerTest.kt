package io.github.leohelfferich.abstain

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunnerTest {
    private fun spec(dir: java.nio.file.Path, items: List<Item> = testItems(2), reps: Int = 2) = RunSpec("r1", "full", items, reps, dir, mock = false)

    @Test
    fun `executes every item x model x prompt x rep once and writes a manifest`() {
        val dir = tempDir().resolve("r1")
        val client = sensibleClient()
        val summary = newRunner(testConfig(), client).execute(spec(dir), null)
        val expected = 4 * 2 * 2 * 2
        assertEquals("complete", summary.status)
        assertEquals(expected, summary.okCalls)
        assertEquals(expected, client.requests.size)
        val records = CallLog.read(dir)
        assertEquals(expected, records.map { it.key }.toSet().size)
        val manifest = PrettyJson.decodeFromString(Manifest.serializer(), Files.readString(dir.resolve("manifest.json")))
        assertEquals("complete", manifest.status)
        assertEquals(expected, manifest.plannedCalls)
        assertEquals(expected, manifest.okCalls)
        assertEquals("deadbeef", manifest.gitCommit)
        assertEquals(setOf("neutral", "strict"), manifest.prompts.keys)
        assertEquals(listOf("test/alpha", "test/beta"), manifest.models.map { it.openRouterId })
        assertEquals(0.7, manifest.models[0].temperatureSent)
        assertNull(manifest.models[1].temperatureSent)
    }

    @Test
    fun `temperature is only sent to models that accept it`() {
        val client = sensibleClient()
        newRunner(testConfig(reps = 1), client).execute(spec(tempDir().resolve("r1"), reps = 1), null)
        client.requests.forEach {
            if (it.model.alias == "beta") assertNull(it.temperature) else assertEquals(0.7, it.temperature)
            assertEquals(100, it.maxTokens)
        }
    }

    @Test
    fun `calls are ordered repetition by repetition so a stop leaves complete rep sets`() {
        val client = sensibleClient()
        newRunner(testConfig(), client).execute(spec(tempDir().resolve("r1")), null)
        val reps = client.requests.map { it.rep }
        assertEquals(reps.sorted(), reps)
    }

    @Test
    fun `resuming skips finished calls and retries failed ones`() {
        val dir = tempDir().resolve("r1")
        val failBeta = FakeChatClient { req ->
            if (req.model.alias == "beta") throw ChatException("HTTP 500: boom", retryable = true, status = 500) else okResult("ANSWER: Paris")
        }
        val first = newRunner(testConfig(), failBeta).execute(spec(dir), null)
        assertEquals(32 / 2, first.okCalls)
        assertEquals(32 / 2, first.errorCalls)

        val second = sensibleClient()
        val summary = newRunner(testConfig(), second).execute(spec(dir), null)
        assertEquals(16, second.requests.size)
        assertTrue(second.requests.all { it.model.alias == "beta" })
        assertEquals("complete", summary.status)
        assertEquals(32, CallLog.okByKey(CallLog.read(dir)).size)

        val third = sensibleClient()
        newRunner(testConfig(), third).execute(spec(dir), null)
        assertEquals(0, third.requests.size)
    }

    @Test
    fun `refuses to resume a run directory whose configuration differs`() {
        val dir = tempDir().resolve("r1")
        newRunner(testConfig(), sensibleClient()).execute(spec(dir), null)
        assertFailsWith<IllegalStateException> { newRunner(testConfig(), sensibleClient()).execute(spec(dir, reps = 1), null) }
        assertFailsWith<IllegalStateException> { newRunner(testConfig().copy(temperature = 0.1), sensibleClient()).execute(spec(dir), null) }
    }

    @Test
    fun `stops before the cost cap is exceeded`() {
        val dir = tempDir().resolve("r1")
        val client = FakeChatClient { okResult("ANSWER: Paris", costUsd = 0.5) }
        val summary = newRunner(testConfig(capEur = 2.0), client).execute(spec(dir), null)
        assertEquals("stopped", summary.status)
        assertTrue(summary.stopReason!!.contains("cost cap"))
        assertEquals(4, summary.okCalls)
        assertTrue(CallLog.read(dir).sumOf { it.costUsd ?: 0.0 } <= 2.0 + 1e-9)
        assertEquals(4, client.requests.size)
    }

    @Test
    fun `cap holds with concurrent workers when calls cost at most their reserved worst case`() {
        val dir = tempDir().resolve("r1")
        val client = FakeChatClient { okResult("ANSWER: Paris", costUsd = 0.0002) }
        val summary = newRunner(testConfig(capEur = 0.001, concurrency = 6), client).execute(spec(dir), null)
        assertEquals("stopped", summary.status)
        assertTrue(CallLog.read(dir).sumOf { it.costUsd ?: 0.0 } <= 0.001 + 1e-12)
    }

    @Test
    fun `money already spent in other runs counts against the cap`() {
        val runs = tempDir()
        val earlier = runs.resolve("dry-run")
        Files.createDirectories(earlier)
        val spent = CallRecord("a0", Kind.ANSWERABLE, "alpha", "test/alpha", PromptVariant.NEUTRAL, 1, "ok", "t", 0.7, 10, "x", costUsd = 1.9)
        Files.writeString(CallLog.file(earlier), CompactJson.encodeToString(CallRecord.serializer(), spent) + "\n")
        val client = FakeChatClient { okResult("ANSWER: Paris", costUsd = 0.05) }
        val summary = newRunner(testConfig(capEur = 2.0), client).execute(spec(runs.resolve("full")), null)
        assertEquals("stopped", summary.status)
        assertTrue(client.requests.size <= 2, "only about 0.1 EUR was left, made ${client.requests.size} calls")
    }

    @Test
    fun `authentication errors abort the run immediately`() {
        val client = FakeChatClient { throw ChatException("HTTP 401: nope", retryable = false, status = 401) }
        val summary = newRunner(testConfig(), client).execute(spec(tempDir().resolve("r1")), null)
        assertEquals("stopped", summary.status)
        assertTrue(client.requests.size <= 2)
    }

    @Test
    fun `cost falls back to token pricing when the API reports none`() {
        val dir = tempDir().resolve("r1")
        newRunner(testConfig(reps = 1), FakeChatClient { okResult("ANSWER: Paris", costUsd = null, promptTokens = 1000, completionTokens = 100) })
            .execute(spec(dir, reps = 1), null)
        val rec = CallLog.read(dir).first()
        assertEquals("computed", rec.costSource)
        assertEquals(1000 * 1e-6 + 100 * 2e-6, rec.costUsd!!, 1e-12)
    }

    @Test
    fun `records never contain the prompt text or secrets, only the reply and metadata`() {
        val dir = tempDir().resolve("r1")
        newRunner(testConfig(reps = 1), sensibleClient()).execute(spec(dir, reps = 1), null)
        val raw = Files.readString(CallLog.file(dir))
        assertTrue("Paris is the capital" !in raw)
        assertTrue(Regex("sk-or-").find(raw) == null)
    }
}
