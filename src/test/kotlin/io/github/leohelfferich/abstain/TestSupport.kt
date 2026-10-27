package io.github.leohelfferich.abstain

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

val repoRoot: Path = Path.of("").toAbsolutePath()

fun testModels(): List<ModelConfig> = listOf(
    ModelConfig("alpha", "test/alpha", sendTemperature = true, reasoning = ReasoningConfig("low", true), assumedReasoningTokens = 100),
    ModelConfig("beta", "test/beta", sendTemperature = false),
)

fun testConfig(
    models: List<ModelConfig> = testModels(),
    reps: Int = 2,
    capEur: Double = 10.0,
    margin: Double = 0.0,
    concurrency: Int = 1,
) = ExperimentConfig(
    seed = 7, answerableCount = 3, unanswerableCount = 3, reps = reps, temperature = 0.7, maxTokens = 100,
    capEur = capEur, safetyMarginEur = margin, eurPerUsd = 1.0, concurrency = concurrency, charsPerToken = 4.0,
    assumedAnswerTokens = 10, bootstrapResamples = 200, bootstrapSeed = 1,
    dryRun = DryRunConfig(1, 1, 1), models = models,
)

fun testSnapshot(models: List<ModelConfig> = testModels()) = PricingSnapshot(
    retrievedOn = "2026-01-01", source = "test", note = "test",
    models = models.map {
        SnapshotModel(it.openRouterId, null, it.alias, "0.000001", "0.000002", null, 1000, if (it.sendTemperature) listOf("temperature", "max_tokens") else listOf("max_tokens"))
    },
)

fun testItems(pairs: Int = 3): List<Item> = (0 until pairs).flatMap { i ->
    listOf(
        Item("a$i", Kind.ANSWERABLE, i, "Art", i, "Paris is the capital of France. Passage $i.", "What is the capital of France? ($i)", listOf("Paris"), emptyList()),
        Item("u$i", Kind.UNANSWERABLE, i, "Art", i, "Paris is the capital of France. Passage $i.", "Who is the mayor of Lyon? ($i)", emptyList(), listOf("Gérard Collomb")),
    )
}

fun okResult(content: String, costUsd: Double? = 0.001, promptTokens: Int? = 50, completionTokens: Int? = 5) =
    ChatResult(content, "stop", promptTokens, completionTokens, 0, costUsd, "TestProvider", "test/model", "gen-1", 12)

class FakeChatClient(private val handler: (ChatRequest) -> ChatResult) : ChatClient {
    val requests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
    override fun complete(request: ChatRequest): ChatResult {
        requests += request
        return handler(request)
    }
}

/** A client that answers like a reasonable model: abstains on unanswerables only under the strict prompt. */
fun sensibleClient() = FakeChatClient { req ->
    val answerable = req.itemId!!.startsWith("a")
    okResult(
        when {
            answerable -> "ANSWER: Paris"
            req.variant == PromptVariant.STRICT -> Prompts.SENTINEL
            else -> "ANSWER: Gérard Collomb"
        },
    )
}

fun newRunner(config: ExperimentConfig, client: ChatClient, log: (String) -> Unit = {}) = Runner(
    config, testSnapshot(config.models), "sample-sha", "config-sha", client, log, sourceSha256 = "source-sha",
    gitInfo = { "deadbeef" to false },
)

fun tempDir(prefix: String = "abstain-test"): Path = Files.createTempDirectory(prefix)

/** Copies the real config, pricing snapshot and sample into a throw-away workspace. */
fun tempWorkspace(): Workspace {
    val root = tempDir("abstain-ws")
    val ws = Workspace(root)
    Files.createDirectories(ws.configFile.parent)
    Files.createDirectories(ws.sampleFile.parent)
    Files.copy(repoRoot.resolve("config/experiment.json"), ws.configFile)
    Files.copy(repoRoot.resolve("config/openrouter-models.snapshot.json"), ws.snapshotFile)
    Files.copy(repoRoot.resolve("data/sample.json"), ws.sampleFile)
    return ws
}
