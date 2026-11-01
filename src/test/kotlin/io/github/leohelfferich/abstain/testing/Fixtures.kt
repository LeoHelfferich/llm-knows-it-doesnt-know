package io.github.leohelfferich.abstain.testing

import io.github.leohelfferich.abstain.client.ChatClient
import io.github.leohelfferich.abstain.client.model.ChatRequest
import io.github.leohelfferich.abstain.client.model.ChatResult
import io.github.leohelfferich.abstain.config.model.DryRunConfig
import io.github.leohelfferich.abstain.config.model.ExperimentConfig
import io.github.leohelfferich.abstain.config.model.ModelConfig
import io.github.leohelfferich.abstain.config.model.ReasoningConfig
import io.github.leohelfferich.abstain.config.Workspace
import io.github.leohelfferich.abstain.execution.Runner
import io.github.leohelfferich.abstain.pricing.model.PricingSnapshot
import io.github.leohelfferich.abstain.pricing.model.SnapshotModel
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.prompt.Prompts
import io.github.leohelfferich.abstain.squad.model.Item
import io.github.leohelfferich.abstain.squad.model.Kind
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/** Repository root when tests run with the Gradle working directory. */
val repoRoot: Path = Path.of("").toAbsolutePath()

/** Two stand-in models. Alpha accepts temperature; beta does not. */
fun testModels(): List<ModelConfig> = listOf(
    ModelConfig("alpha", "test/alpha", sendTemperature = true, reasoning = ReasoningConfig("low", true), assumedReasoningTokens = 100),
    ModelConfig("beta", "test/beta", sendTemperature = false),
)

/** A small experiment configuration. Defaults keep the tests off the network and under the hard cap. */
fun testConfig(
    models: List<ModelConfig> = testModels(),
    reps: Int = 2,
    capEur: Double = 10.0,
    margin: Double = 0.0,
    concurrency: Int = 1,
) = ExperimentConfig(
    seed = 7,
    answerableCount = 3,
    unanswerableCount = 3,
    reps = reps,
    temperature = 0.7,
    maxTokens = 100,
    capEur = capEur,
    safetyMarginEur = margin,
    eurPerUsd = 1.0,
    concurrency = concurrency,
    charsPerToken = 4.0,
    assumedAnswerTokens = 10,
    bootstrapResamples = 200,
    bootstrapSeed = 1,
    dryRun = DryRunConfig(1, 1, 1),
    models = models,
)

/** Prices that make the arithmetic in the cost tests easy to check by hand. */
fun testSnapshot(models: List<ModelConfig> = testModels()) = PricingSnapshot(
    retrievedOn = "2026-01-01",
    source = "test",
    note = "test",
    models = models.map {
        SnapshotModel(
            it.openRouterId,
            null,
            it.alias,
            "0.000001",
            "0.000002",
            null,
            1000,
            if (it.sendTemperature) listOf("temperature", "max_tokens") else listOf("max_tokens"),
        )
    },
)

/** Paired answerable and unanswerable items that share a passage. */
fun testItems(pairs: Int = 3): List<Item> = (0 until pairs).flatMap { index ->
    listOf(
        Item(
            "a$index",
            Kind.ANSWERABLE,
            index,
            "Art",
            index,
            "Paris is the capital of France. Passage $index.",
            "What is the capital of France? ($index)",
            listOf("Paris"),
            emptyList(),
        ),
        Item(
            "u$index",
            Kind.UNANSWERABLE,
            index,
            "Art",
            index,
            "Paris is the capital of France. Passage $index.",
            "Who is the mayor of Lyon? ($index)",
            emptyList(),
            listOf("Gérard Collomb"),
        ),
    )
}

/** A successful completion with the token counts and cost the caller wants to assert on. */
fun okResult(content: String, costUsd: Double? = 0.001, promptTokens: Int? = 50, completionTokens: Int? = 5) =
    ChatResult(content, "stop", promptTokens, completionTokens, 0, costUsd, "TestProvider", "test/model", "gen-1", 12)

/** Records every request and answers with the supplied handler. */
class FakeChatClient(private val handler: (ChatRequest) -> ChatResult) : ChatClient {
    val requests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())

    override fun complete(request: ChatRequest): ChatResult {
        requests += request
        return handler(request)
    }
}

/**
 * Answers like a tidy model: the gold answer on answerable items, the sentinel under the strict
 * prompt, and an invention otherwise.
 */
fun sensibleClient() = FakeChatClient { request ->
    val answerable = request.itemId!!.startsWith("a")
    okResult(
        when {
            answerable -> "ANSWER: Paris"
            request.variant == PromptVariant.STRICT -> Prompts.SENTINEL
            else -> "ANSWER: Gérard Collomb"
        },
    )
}

/** A runner whose git metadata and checksums are fixed, so tests do not depend on the working tree. */
fun newRunner(config: ExperimentConfig, client: ChatClient, log: (String) -> Unit = {}) = Runner(
    config,
    testSnapshot(config.models),
    "sample-sha",
    "config-sha",
    client,
    log,
    sourceSha256 = "source-sha",
    gitInfo = { "deadbeef" to false },
)

/** A temporary directory the test can write a run into. */
fun tempDir(prefix: String = "abstain-test"): Path = Files.createTempDirectory(prefix)

/** A throw-away copy of the committed config, pricing snapshot, and sample. */
fun tempWorkspace(): Workspace {
    val root = tempDir("abstain-ws")
    val workspace = Workspace(root)
    Files.createDirectories(workspace.configFile.parent)
    Files.createDirectories(workspace.sampleFile.parent)
    Files.copy(repoRoot.resolve("config/experiment.json"), workspace.configFile)
    Files.copy(repoRoot.resolve("config/openrouter-models.snapshot.json"), workspace.snapshotFile)
    Files.copy(repoRoot.resolve("data/sample.json"), workspace.sampleFile)
    return workspace
}
