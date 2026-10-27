package io.github.leohelfferich.abstain

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliTest {
    private val key = "sk-or-v1-SECRETSECRETSECRETSECRET1234"

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

    @Test
    fun `estimate works offline from the committed pricing snapshot and shows both runs`() {
        val h = harness(env = emptyMap(), client = null)
        assertEquals(0, h.run("estimate"))
        assertTrue("200 items x 5 models x 2 prompts x 3 rep(s) = 6000 calls" in h.stdout)
        assertTrue("10 items x 5 models x 2 prompts x 1 rep(s) = 100 calls" in h.stdout)
        assertTrue("Hard cap: EUR 10.00" in h.stdout)
    }

    @Test
    fun `run without a key explains where the key comes from and makes no client`() {
        val h = harness(env = emptyMap(), client = null)
        assertEquals(2, h.run("run", "--dry-run", "--yes"))
        assertTrue("OPENROUTER_API_KEY" in h.stderr)
        assertTrue(h.factoryCalls.isEmpty())
        assertFalse(Files.exists(h.ws.runsDir))
    }

    @Test
    fun `dry run makes 100 calls on 5 + 5 items and never prints or stores the key`() {
        val client = sensibleSample()
        val h = harness(client = client)
        assertEquals(0, h.run("run", "--dry-run", "--yes"), h.stderr)
        assertEquals(100, client.requests.size)
        assertEquals(5, client.requests.map { it.itemId }.distinct().count { id -> Workspace(repoRoot).loadSample().items.first { it.id == id }.kind == Kind.ANSWERABLE })
        assertEquals(10, client.requests.map { it.itemId }.distinct().size)
        assertTrue(client.requests.all { it.rep == 1 })
        assertTrue("Cost estimate: dry-run" in h.stdout)
        val everything = h.stdout + h.stderr + Files.walk(h.ws.runsDir).filter { Files.isRegularFile(it) }.toList().joinToString("\n") { Files.readString(it) }
        assertFalse(key in everything)
        assertFalse("SECRETSECRET" in everything)
        val manifest = PrettyJson.decodeFromString(Manifest.serializer(), Files.readString(h.ws.runsDir.resolve("dry-run/manifest.json")))
        assertEquals(100, manifest.okCalls)
        assertEquals("dry-run", manifest.kind)
        assertEquals(false, manifest.mock)
    }

    @Test
    fun `a paid run needs explicit confirmation`() {
        val client = sensibleSample()
        val declined = harness(stdin = "no\n", client = client)
        assertEquals(1, declined.run("run", "--dry-run"))
        assertEquals(0, client.requests.size)
        assertTrue("Aborted; nothing was sent." in declined.stdout)

        val eof = harness(stdin = "", client = client)
        assertEquals(1, eof.run("run", "--dry-run"))
        assertEquals(0, client.requests.size)

        val accepted = harness(stdin = "yes\n", client = client)
        assertEquals(0, accepted.run("run", "--dry-run"))
        assertEquals(100, client.requests.size)
    }

    @Test
    fun `cap can be lowered but never raised, and a run that cannot fit is refused before any call`() {
        val client = sensibleSample()
        val raised = harness(client = client)
        assertEquals(1, raised.run("run", "--dry-run", "--yes", "--cap-eur", "20"))
        assertTrue("may only lower" in raised.stderr)

        val tiny = harness(client = client)
        assertEquals(1, tiny.run("run", "--dry-run", "--yes", "--cap-eur", "0.3"))
        assertTrue("Refusing to start" in tiny.stderr || "safety" in tiny.stderr || "usable cap" in tiny.stderr)
        assertEquals(0, client.requests.size)
    }

    @Test
    fun `mock mode needs no key, calls no client and writes outside data runs`() {
        val h = harness(env = emptyMap(), client = null)
        assertEquals(0, h.run("run", "--dry-run", "--mock"))
        assertTrue(Files.exists(h.ws.mockRunsDir.resolve("dry-run/calls.jsonl")))
        assertFalse(Files.exists(h.ws.runsDir))
        assertTrue(h.factoryCalls.isEmpty())
        assertTrue("MOCK MODE" in h.stdout)
    }

    @Test
    fun `analyze writes results, items and review candidates and honours disputed ids`() {
        val h = harness(env = emptyMap(), client = null)
        assertEquals(0, h.run("run", "--dry-run", "--mock"))
        val runDir = h.ws.mockRunsDir.resolve("dry-run")
        val results = runDir.resolve("analysis.json")
        assertEquals(0, h.run("analyze", "--run", runDir.toString()), h.stderr)
        assertTrue(Files.exists(results) && Files.exists(runDir.resolve("items.json")) && Files.exists(runDir.resolve("review-candidates.json")))
        assertFalse(Files.exists(h.ws.docsData.resolve("results.json")), "non-publishable runs must not touch docs/")
        val parsed = PrettyJson.decodeFromString(Results.serializer(), Files.readString(results))
        assertEquals(10, parsed.scopes[0].answerableItems + parsed.scopes[0].unanswerableItems)
        assertTrue(parsed.run.mock)

        val sample = h.ws.loadSample().items
        val disputedId = sample.first { it.kind == Kind.UNANSWERABLE }.id
        Files.writeString(h.ws.disputedFile, """{"items":[{"id":"$disputedId","reason":"answer is in the passage"}]}""")
        assertEquals(0, h.run("analyze", "--run", runDir.toString()))
        val again = PrettyJson.decodeFromString(Results.serializer(), Files.readString(results))
        assertEquals(1, again.scopes[1].excludedItems)
        assertEquals(4, again.scopes[1].unanswerableItems)
        assertEquals(listOf(disputedId), again.disputed.map { it.id })
    }

    @Test
    fun `analyze refuses a disputed id that is not in the sample`() {
        val h = harness(env = emptyMap(), client = null)
        h.run("run", "--dry-run", "--mock")
        Files.writeString(h.ws.disputedFile, """{"items":[{"id":"nope"}]}""")
        assertEquals(1, h.run("analyze", "--run", h.ws.mockRunsDir.resolve("dry-run").toString()))
        assertTrue("not in the sample" in h.stderr)
    }

    @Test
    fun `mock results cannot be written to an explicit output without the allow flag`() {
        val h = harness(env = emptyMap(), client = null)
        h.run("run", "--dry-run", "--mock")
        val dir = h.ws.mockRunsDir.resolve("dry-run").toString()
        assertEquals(2, h.run("analyze", "--run", dir, "--out", h.ws.docsData.resolve("results.json").toString()))
        assertFalse(Files.exists(h.ws.docsData.resolve("results.json")))
        assertEquals(0, h.run("analyze", "--run", dir, "--out", h.ws.docsData.resolve("results.json").toString(), "--allow-mock"))
    }

    @Test
    fun `only runs of kind full are published to docs data by default`() {
        val h = harness(stdin = "yes\n")
        assertEquals(0, h.run("run", "--dry-run", "--yes", "--run-id", "full"), h.stderr)
        // a "dry-run" kind is not publishable even if the run id is called full
        assertEquals(0, h.run("analyze", "--run", h.ws.runsDir.resolve("full").toString()))
        assertFalse(Files.exists(h.ws.docsData.resolve("results.json")))
    }

    @Test
    fun `unknown commands and flags give a usage error`() {
        val h = harness(env = emptyMap(), client = null)
        assertEquals(2, h.run("frobnicate"))
        assertEquals(2, h.run("run", "stray"))
        assertEquals(2, h.run("run", "--run-id"))
    }

    @Test
    fun `config models match the committed OpenRouter snapshot`() {
        val ws = Workspace(repoRoot)
        val config = ws.loadConfig().also { it.validate() }
        val snapshot = PricingSnapshot.load(ws.snapshotFile)
        assertEquals(5, config.models.size)
        config.models.forEach {
            val s = snapshot.model(it.openRouterId)
            assertEquals(s.supportsTemperature, it.sendTemperature, "temperature support for ${it.openRouterId}")
        }
        assertEquals(setOf("x-ai/grok-4.3", "openai/gpt-5.4-mini", "anthropic/claude-haiku-4.5", "google/gemini-2.5-flash", "meta-llama/llama-3.3-70b-instruct"), config.models.map { it.openRouterId }.toSet())
        assertEquals(3, config.reps)
        assertTrue(config.capEur <= HARD_CAP_EUR)
    }
}
