package io.github.leohelfferich.abstain.execution

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

import io.github.leohelfferich.abstain.client.model.ChatException
import io.github.leohelfferich.abstain.utils.CompactJson
import io.github.leohelfferich.abstain.utils.PrettyJson
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.squad.model.Item
import io.github.leohelfferich.abstain.squad.model.Kind
import io.github.leohelfferich.abstain.testing.FakeChatClient
import io.github.leohelfferich.abstain.testing.newRunner
import io.github.leohelfferich.abstain.testing.okResult
import io.github.leohelfferich.abstain.testing.sensibleClient
import io.github.leohelfferich.abstain.testing.tempDir
import io.github.leohelfferich.abstain.testing.testConfig
import io.github.leohelfferich.abstain.testing.testItems
import io.github.leohelfferich.abstain.execution.model.CallRecord
import io.github.leohelfferich.abstain.execution.model.Manifest
import io.github.leohelfferich.abstain.execution.model.RunSpec

class RunnerTest {

    @Test
    fun `should execute every planned call once and write a manifest`() {
        val dir = tempDir().resolve("r1")
        val client = sensibleClient()
        val summary = newRunner(testConfig(), client).execute(spec(dir), null)

        // ---
        val expected = 4 * 2 * 2 * 2
        // ---

        assertThat(summary.status).isEqualTo("complete")
        assertThat(summary.okCalls).isEqualTo(expected)
        assertThat(client.requests.size).isEqualTo(expected)
        val records = CallLog.read(dir)
        assertThat(records.map { it.key }.toSet().size).isEqualTo(expected)
        val manifest = PrettyJson.decodeFromString(Manifest.serializer(), Files.readString(dir.resolve("manifest.json")))
        assertThat(manifest.status).isEqualTo("complete")
        assertThat(manifest.plannedCalls).isEqualTo(expected)
        assertThat(manifest.okCalls).isEqualTo(expected)
        assertThat(manifest.gitCommit).isEqualTo("deadbeef")
        assertThat(manifest.prompts.keys).isEqualTo(setOf("neutral", "strict"))
        assertThat(manifest.models.map { it.openRouterId }).isEqualTo(listOf("test/alpha", "test/beta"))
        assertThat(manifest.models[0].temperatureSent).isEqualTo(0.7)
        assertThat(manifest.models[1].temperatureSent).isNull()
    }

    @Test
    fun `should send temperature only to models that accept it`() {
        val client = sensibleClient()

        // ---
        newRunner(testConfig(reps = 1), client).execute(spec(tempDir().resolve("r1"), reps = 1), null)
        // ---

        client.requests.forEach {
            if (it.model.alias == "beta") assertThat(it.temperature).isNull() else assertThat(it.temperature).isEqualTo(0.7)
            assertThat(it.maxTokens).isEqualTo(100)
        }
    }

    @Test
    fun `should order calls repetition by repetition`() {
        val client = sensibleClient()
        newRunner(testConfig(), client).execute(spec(tempDir().resolve("r1")), null)

        // ---
        val reps = client.requests.map { it.rep }
        // ---

        assertThat(reps).isEqualTo(reps.sorted())
    }

    @Test
    fun `should skip finished calls and retry failed ones when resuming`() {
        val dir = tempDir().resolve("r1")
        val failBeta = FakeChatClient { req ->
            if (req.model.alias == "beta") throw ChatException("HTTP 500: boom", retryable = true, status = 500) else okResult("ANSWER: Paris")
        }

        // ---
        val first = newRunner(testConfig(), failBeta).execute(spec(dir), null)
        // ---

        assertThat(first.status).isEqualTo("incomplete")
        assertThat(first.stopReason!!.contains("re-run")).isTrue()
        assertThat(first.okCalls).isEqualTo(32 / 2)
        assertThat(first.errorCalls).isEqualTo(32 / 2)
        val second = sensibleClient()
        val summary = newRunner(testConfig(), second).execute(spec(dir), null)
        assertThat(second.requests.size).isEqualTo(16)
        assertThat(second.requests.all { it.model.alias == "beta" }).isTrue()
        assertThat(summary.status).isEqualTo("complete")
        assertThat(CallLog.okByKey(CallLog.read(dir)).size).isEqualTo(32)
        val third = sensibleClient()
        newRunner(testConfig(), third).execute(spec(dir), null)
        assertThat(third.requests.size).isEqualTo(0)
    }

    @Test
    fun `should refuse to resume a directory whose configuration differs`() {
        val dir = tempDir().resolve("r1")

        // ---
        newRunner(testConfig(), sensibleClient()).execute(spec(dir), null)
        // ---

        assertThatThrownBy { newRunner(testConfig(), sensibleClient()).execute(spec(dir, reps = 1), null) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { newRunner(testConfig().copy(temperature = 0.1), sensibleClient()).execute(spec(dir), null) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `should stop before the cost cap is exceeded`() {
        val dir = tempDir().resolve("r1")
        val client = FakeChatClient { okResult("ANSWER: Paris", costUsd = 0.5) }

        // ---
        val summary = newRunner(testConfig(capEur = 2.0), client).execute(spec(dir), null)
        // ---

        assertThat(summary.status).isEqualTo("stopped")
        assertThat(summary.stopReason!!.contains("cost cap")).isTrue()
        assertThat(summary.okCalls).isEqualTo(4)
        assertThat(CallLog.read(dir).sumOf { it.costUsd ?: 0.0 } <= 2.0 + 1e-9).isTrue()
        assertThat(client.requests.size).isEqualTo(4)
    }

    @Test
    fun `should hold the cap when calls cost at most their reserved worst case`() {
        val dir = tempDir().resolve("r1")
        val client = FakeChatClient { okResult("ANSWER: Paris", costUsd = 0.0002) }

        // ---
        val summary = newRunner(testConfig(capEur = 0.001, concurrency = 6), client).execute(spec(dir), null)
        // ---

        assertThat(summary.status).isEqualTo("stopped")
        assertThat(CallLog.read(dir).sumOf { it.costUsd ?: 0.0 } <= 0.001 + 1e-12).isTrue()
    }

    @Test
    fun `should count money already spent in other runs against the cap`() {
        val runs = tempDir()
        val earlier = runs.resolve("dry-run")
        Files.createDirectories(earlier)
        val spent = CallRecord("a0", Kind.ANSWERABLE, "alpha", "test/alpha", PromptVariant.NEUTRAL, 1, "ok", "t", 0.7, 10, "x", costUsd = 1.9)
        Files.writeString(CallLog.file(earlier), CompactJson.encodeToString(CallRecord.serializer(), spent) + "\n")
        val client = FakeChatClient { okResult("ANSWER: Paris", costUsd = 0.05) }

        // ---
        val summary = newRunner(testConfig(capEur = 2.0), client).execute(spec(runs.resolve("full")), null)
        // ---

        assertThat(summary.status).isEqualTo("stopped")
        assertThat(client.requests.size <= 2).describedAs("only about 0.1 EUR was left, made ${client.requests.size} calls").isTrue()
    }

    @Test
    fun `should abort the run on an authentication error`() {
        val client = FakeChatClient { throw ChatException("HTTP 401: nope", retryable = false, status = 401) }

        // ---
        val summary = newRunner(testConfig(), client).execute(spec(tempDir().resolve("r1")), null)
        // ---

        assertThat(summary.status).isEqualTo("stopped")
        assertThat(client.requests.size <= 2).isTrue()
    }

    @Test
    fun `should fall back to token pricing when the API reports no cost`() {
        val dir = tempDir().resolve("r1")
        newRunner(
            testConfig(reps = 1),
            FakeChatClient { okResult("ANSWER: Paris", costUsd = null, promptTokens = 1000, completionTokens = 100) })
            .execute(spec(dir, reps = 1), null)

        // ---
        val rec = CallLog.read(dir).first()
        // ---

        assertThat(rec.costSource).isEqualTo("computed")
        assertThat(rec.costUsd!!).isCloseTo(1000 * 1e-6 + 100 * 2e-6, within(1e-12))
    }

    @Test
    fun `should refuse a second run of a directory that is still going`() {
        val dir = tempDir().resolve("r1")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = FakeChatClient {
            started.countDown()
            release.await()
            okResult("ANSWER: Paris")
        }
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                newRunner(testConfig(reps = 1), client).execute(spec(dir, reps = 1), null)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }

        // ---
        worker.start()
        // ---

        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
            val conflict = catchThrowableOfType(IllegalStateException::class.java) {
                newRunner(testConfig(reps = 1), sensibleClient()).execute(spec(dir, reps = 1), null)
            }
            assertThat(conflict.message!!.contains("already in progress")).isTrue()
        } finally {
            release.countDown()
            worker.join(30_000)
        }
        assertThat(!worker.isAlive).isTrue()
        assertThat(failure.get()).isNull()
    }

    @Test
    fun `should not let a negative reported cost reduce the spent total`() {
        val dir = tempDir().resolve("r1")
        newRunner(
            testConfig(reps = 1),
            FakeChatClient { okResult("ANSWER: Paris", costUsd = -5.0, promptTokens = 1000, completionTokens = 100) },
        ).execute(spec(dir, reps = 1), null)

        // ---
        val records = CallLog.read(dir)
        // ---

        assertThat(records.all { (it.costUsd ?: 0.0) >= 0.0 }).isTrue()
        assertThat(records.first().costSource).isEqualTo("computed")
        assertThat(records.first().costUsd!!).isCloseTo(1000 * 1e-6 + 100 * 2e-6, within(1e-12))
    }

    @Test
    fun `should skip a truncated trailing log line and reject an earlier corrupt line`() {
        val dir = tempDir().resolve("r1")
        Files.createDirectories(dir)
        val ok = CallRecord("a0", Kind.ANSWERABLE, "alpha", "test/alpha", PromptVariant.NEUTRAL, 1, "ok", "t", 0.7, 10, "ANSWER: Paris", costUsd = 0.1)
        val line = CompactJson.encodeToString(CallRecord.serializer(), ok)

        // ---
        Files.writeString(CallLog.file(dir), line + "\n{\"itemId\":")
        // ---

        assertThat(CallLog.read(dir).map { it.itemId }).isEqualTo(listOf("a0"))
        val failed = ok.copy(status = "error", content = null, costUsd = 0.0, error = "HTTP 429: slow down")
        Files.writeString(dir.resolve("failed-attempts.jsonl"), CompactJson.encodeToString(CallRecord.serializer(), failed) + "\n")
        val both = CallLog.read(dir)
        assertThat(both.size).isEqualTo(2)
        assertThat(CallLog.okByKey(both).size).isEqualTo(1)
        Files.writeString(CallLog.file(dir), "{\"nope\":\n" + line + "\n")
        assertThatThrownBy { CallLog.read(dir) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `should keep prompt text and secrets out of the call log`() {
        val dir = tempDir().resolve("r1")
        newRunner(testConfig(reps = 1), sensibleClient()).execute(spec(dir, reps = 1), null)

        // ---
        val raw = Files.readString(CallLog.file(dir))
        // ---

        assertThat(raw).doesNotContain("Paris is the capital")
        assertThat(Regex("sk-or-").find(raw) == null).isTrue()
    }

    private fun spec(dir: Path, items: List<Item> = testItems(2), reps: Int = 2) = RunSpec("r1", "full", items, reps, dir, mock = false)
}
