package io.github.leohelfferich.abstain.cli

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

import io.github.leohelfferich.abstain.analysis.model.Results
import io.github.leohelfferich.abstain.config.HARD_CAP_EUR
import io.github.leohelfferich.abstain.config.Workspace
import io.github.leohelfferich.abstain.config.model.validate
import io.github.leohelfferich.abstain.execution.model.Manifest
import io.github.leohelfferich.abstain.utils.PrettyJson
import io.github.leohelfferich.abstain.pricing.model.PricingSnapshot
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.prompt.Prompts
import io.github.leohelfferich.abstain.squad.model.Kind
import io.github.leohelfferich.abstain.testing.FakeChatClient
import io.github.leohelfferich.abstain.testing.okResult
import io.github.leohelfferich.abstain.testing.repoRoot
import io.github.leohelfferich.abstain.testing.tempWorkspace

class CliTest {
    private val key = "sk-or-v1-SECRETSECRETSECRETSECRET1234"

    @Test
    fun `should estimate both runs from the committed pricing snapshot`() {
        // ---
        val h = harness(env = emptyMap(), client = null)
        // ---

        assertThat(h.run("estimate")).isEqualTo(0)
        assertThat(h.stdout).contains("200 items x 5 models x 2 prompts x 3 rep(s) = 6000 calls")
        assertThat(h.stdout).contains("10 items x 5 models x 2 prompts x 1 rep(s) = 100 calls")
        assertThat(h.stdout).contains("Hard cap: EUR 10.00")
    }

    @Test
    fun `should explain where the key comes from when it is missing`() {
        // ---
        val h = harness(env = emptyMap(), client = null)
        // ---

        assertThat(h.run("run", "--dry-run", "--yes")).isEqualTo(2)
        assertThat(h.stderr).contains("OPENROUTER_API_KEY")
        assertThat(h.factoryCalls.isEmpty()).isTrue()
        assertThat(Files.exists(h.ws.runsDir)).isFalse()
    }

    @Test
    fun `should make 100 dry-run calls without printing the key`() {
        val client = sensibleSample()

        // ---
        val h = harness(client = client)
        // ---

        assertThat(h.run("run", "--dry-run", "--yes")).describedAs(h.stderr).isEqualTo(0)
        assertThat(client.requests.size).isEqualTo(100)
        assertThat(client.requests.map { it.itemId }.distinct().count { id -> Workspace(repoRoot)
            .loadSample().items.first { it.id == id }.kind == Kind.ANSWERABLE }).isEqualTo(5)
        assertThat(client.requests.map { it.itemId }.distinct().size).isEqualTo(10)
        assertThat(client.requests.all { it.rep == 1 }).isTrue()
        assertThat(h.stdout).contains("Cost estimate: dry-run")
        val everything = h.stdout + h.stderr + Files.walk(h.ws.runsDir).filter { Files.isRegularFile(it) }.toList().joinToString("\n") { Files.readString(it) }
        assertThat(everything).doesNotContain(key)
        assertThat(everything).doesNotContain("SECRETSECRET")
        val manifest = PrettyJson.decodeFromString(Manifest.serializer(), Files.readString(h.ws.runsDir.resolve("dry-run/manifest.json")))
        assertThat(manifest.okCalls).isEqualTo(100)
        assertThat(manifest.kind).isEqualTo("dry-run")
        assertThat(manifest.mock).isEqualTo(false)
    }

    @Test
    fun `should ask for confirmation before a paid run`() {
        val client = sensibleSample()

        // ---
        val declined = harness(stdin = "no\n", client = client)
        // ---

        assertThat(declined.run("run", "--dry-run")).isEqualTo(1)
        assertThat(client.requests.size).isEqualTo(0)
        assertThat(declined.stdout).contains("Aborted; nothing was sent.")
        val eof = harness(stdin = "", client = client)
        assertThat(eof.run("run", "--dry-run")).isEqualTo(1)
        assertThat(client.requests.size).isEqualTo(0)
        val accepted = harness(stdin = "yes\n", client = client)
        assertThat(accepted.run("run", "--dry-run")).isEqualTo(0)
        assertThat(client.requests.size).isEqualTo(100)
    }

    @Test
    fun `should lower the cap and refuse a run that cannot fit`() {
        val client = sensibleSample()

        // ---
        val raised = harness(client = client)
        // ---

        assertThat(raised.run("run", "--dry-run", "--yes", "--cap-eur", "20")).isEqualTo(1)
        assertThat(raised.stderr).contains("may only lower")
        val tiny = harness(client = client)
        assertThat(tiny.run("run", "--dry-run", "--yes", "--cap-eur", "0.3")).isEqualTo(1)
        assertThat("Refusing to start" in tiny.stderr || "safety" in tiny.stderr || "usable cap" in tiny.stderr).isTrue()
        assertThat(client.requests.size).isEqualTo(0)
    }

    @Test
    fun `should run in mock mode without a key or a client`() {
        // ---
        val h = harness(env = emptyMap(), client = null)
        // ---

        assertThat(h.run("run", "--dry-run", "--mock")).isEqualTo(0)
        assertThat(Files.exists(h.ws.mockRunsDir.resolve("dry-run/calls.jsonl"))).isTrue()
        assertThat(Files.exists(h.ws.runsDir)).isFalse()
        assertThat(h.factoryCalls.isEmpty()).isTrue()
        assertThat(h.stdout).contains("MOCK MODE")
    }

    @Test
    fun `should write results and honour disputed ids`() {
        // ---
        val h = harness(env = emptyMap(), client = null)
        // ---

        assertThat(h.run("run", "--dry-run", "--mock")).isEqualTo(0)
        val runDir = h.ws.mockRunsDir.resolve("dry-run")
        val results = runDir.resolve("analysis.json")
        assertThat(h.run("analyze", "--run", runDir.toString())).describedAs(h.stderr).isEqualTo(0)
        assertThat(Files.exists(results) && Files.exists(runDir.resolve("items.json")) && Files.exists(runDir.resolve("review-candidates.json"))).isTrue()
        assertThat(Files.exists(h.ws.docsData.resolve("results.json"))).describedAs("non-publishable runs must not touch docs/").isFalse()
        val parsed = PrettyJson.decodeFromString(Results.serializer(), Files.readString(results))
        assertThat(parsed.scopes[0].answerableItems + parsed.scopes[0].unanswerableItems).isEqualTo(10)
        assertThat(parsed.run.mock).isTrue()
        val sample = h.ws.loadSample().items
        val disputedId = sample.first { it.kind == Kind.UNANSWERABLE }.id
        Files.writeString(h.ws.disputedFile, """{"items":[{"id":"$disputedId","reason":"answer is in the passage"}]}""")
        assertThat(h.run("analyze", "--run", runDir.toString())).isEqualTo(0)
        val again = PrettyJson.decodeFromString(Results.serializer(), Files.readString(results))
        assertThat(again.scopes[1].excludedItems).isEqualTo(1)
        assertThat(again.scopes[1].unanswerableItems).isEqualTo(4)
        assertThat(again.disputed.map { it.id }).isEqualTo(listOf(disputedId))
    }

    @Test
    fun `should refuse a disputed id that is not in the sample`() {
        val h = harness(env = emptyMap(), client = null)
        h.run("run", "--dry-run", "--mock")

        // ---
        Files.writeString(h.ws.disputedFile, """{"items":[{"id":"nope"}]}""")
        // ---

        assertThat(h.run("analyze", "--run", h.ws.mockRunsDir.resolve("dry-run").toString())).isEqualTo(1)
        assertThat(h.stderr).contains("not in the sample")
    }

    @Test
    fun `should refuse to publish mock results without the allow flag`() {
        val h = harness(env = emptyMap(), client = null)
        h.run("run", "--dry-run", "--mock")

        // ---
        val dir = h.ws.mockRunsDir.resolve("dry-run").toString()
        // ---

        assertThat(h.run("analyze", "--run", dir, "--out", h.ws.docsData.resolve("results.json").toString())).isEqualTo(2)
        assertThat(Files.exists(h.ws.docsData.resolve("results.json"))).isFalse()
        assertThat(h.run("analyze", "--run", dir, "--out", h.ws.docsData.resolve("results.json").toString(), "--allow-mock")).isEqualTo(0)
    }

    @Test
    fun `should publish only full runs to docs data`() {
        // ---
        val h = harness(stdin = "yes\n")
        // ---

        assertThat(h.run("run", "--dry-run", "--yes", "--run-id", "full")).describedAs(h.stderr).isEqualTo(0)
        // a "dry-run" kind is not publishable even if the run id is called full
        assertThat(h.run("analyze", "--run", h.ws.runsDir.resolve("full").toString())).isEqualTo(0)
        assertThat(Files.exists(h.ws.docsData.resolve("results.json"))).isFalse()
    }

    @Test
    fun `should refuse a sample or prompt that does not match the run`() {
        // ---
        val h = harness(env = emptyMap(), client = null)
        // ---

        assertThat(h.run("run", "--dry-run", "--mock")).isEqualTo(0)
        val manifestPath = h.ws.mockRunsDir.resolve("dry-run/manifest.json")
        val original = Files.readString(manifestPath)
        Files.writeString(manifestPath, original.replace(Regex("\"sampleSha256\": \"[0-9a-f]+\""), "\"sampleSha256\": \"${"0".repeat(64)}\""))
        assertThat(h.run("analyze", "--run", h.ws.mockRunsDir.resolve("dry-run").toString())).isEqualTo(1)
        assertThat(h.stderr).contains("Sample file")
        val h2 = harness(env = emptyMap(), client = null)
        assertThat(h2.run("run", "--dry-run", "--mock")).isEqualTo(0)
        val promptPath = h2.ws.mockRunsDir.resolve("dry-run/manifest.json")
        Files.writeString(promptPath, Files.readString(promptPath).replaceFirst(Regex("\"sha256\": \"[0-9a-f]+\""), "\"sha256\": \"${"ab".repeat(32)}\""))
        assertThat(h2.run("analyze", "--run", h2.ws.mockRunsDir.resolve("dry-run").toString())).isEqualTo(1)
        assertThat(h2.stderr).contains("Prompt")
    }

    @Test
    fun `should keep the run id inside the runs directory`() {
        // ---
        val h = harness(env = emptyMap(), client = null)
        // ---

        assertThat(h.run("run", "--dry-run", "--mock", "--run-id", "..")).isEqualTo(1)
        assertThat(h.stderr).contains("run-id")
        assertThat(Files.exists(h.ws.mockRunsDir.parent.resolve("calls.jsonl"))).isFalse()
        assertThat(Files.exists(h.ws.mockRunsDir.parent.resolve("manifest.json"))).isFalse()
    }

    @Test
    fun `should reject unknown commands and flags`() {
        // ---
        val h = harness(env = emptyMap(), client = null)
        // ---

        assertThat(h.run("frobnicate")).isEqualTo(2)
        assertThat(h.run("run", "stray")).isEqualTo(2)
        assertThat(h.run("run", "--run-id")).isEqualTo(2)
    }

    @Test
    fun `should match config models to the committed OpenRouter snapshot`() {
        val ws = Workspace(repoRoot)
        val config = ws.loadConfig().also { it.validate() }

        // ---
        val snapshot = PricingSnapshot.load(ws.snapshotFile)
        // ---

        assertThat(config.models.size).isEqualTo(5)
        config.models.forEach {
            val s = snapshot.model(it.openRouterId)
            assertThat(it.sendTemperature).describedAs("temperature support for ${it.openRouterId}").isEqualTo(s.supportsTemperature)
        }
        assertThat(config.models.map { it.openRouterId }.toSet()).isEqualTo(setOf("x-ai/grok-4.3", "openai/gpt-5.4-mini", "anthropic/claude-haiku-4.5", "google/gemini-2.5-flash", "meta-llama/llama-3.3-70b-instruct"))
        assertThat(config.reps).isEqualTo(3)
        assertThat(config.capEur <= HARD_CAP_EUR).isTrue()
    }

    private class Harness(val ws: Workspace, env: Map<String, String>, stdin: String, client: FakeChatClient?) {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val factoryCalls = mutableListOf<String>()
        val cli = Cli(
            ws, PrintStream(out), PrintStream(err), env, BufferedReader(StringReader(stdin)),
            clientFactory = { k -> factoryCalls += "created"; client ?: error("no client") },
        )
        fun run(vararg args: String) = cli.run(arrayOf(*args))
        val stdout get() = out.toString()
        val stderr get() = err.toString()
    }

    private fun harness(env: Map<String, String> = mapOf("OPENROUTER_API_KEY" to key), stdin: String = "", client: FakeChatClient? = sensibleSample()) =
        Harness(tempWorkspace(), env, stdin, client)

    private fun sensibleSample() = FakeChatClient { req ->
        val sample = Workspace(repoRoot).loadSample().items.first { it.id == req.itemId }
        okResult(
            when {
                sample.kind == Kind.ANSWERABLE -> "ANSWER: ${sample.answers.first()}"
                req.variant == PromptVariant.STRICT -> Prompts.SENTINEL
                else -> "ANSWER: a plausible invention"
            },
            costUsd = 0.0004,
        )
    }
}
