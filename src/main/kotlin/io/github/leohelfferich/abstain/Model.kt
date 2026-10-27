package io.github.leohelfferich.abstain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

val PrettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

val CompactJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

fun sha256Hex(path: Path): String = sha256Hex(Files.readAllBytes(path))

@Serializable
enum class Kind {
    @SerialName("answerable") ANSWERABLE,
    @SerialName("unanswerable") UNANSWERABLE,
}

@Serializable
enum class PromptVariant(val key: String) {
    @SerialName("neutral") NEUTRAL("neutral"),
    @SerialName("strict") STRICT("strict"),
}

@Serializable
data class Item(
    val id: String,
    val kind: Kind,
    val pairIndex: Int,
    val article: String,
    val paragraphIndex: Int,
    val context: String,
    val question: String,
    val answers: List<String>,
    val plausibleAnswers: List<String>,
)

@Serializable
data class SampleSource(
    val name: String,
    val url: String,
    val sha256: String,
    val license: String,
)

@Serializable
data class SampleFile(
    val seed: Long,
    val algorithm: String,
    val source: SampleSource,
    val answerableCount: Int,
    val unanswerableCount: Int,
    val items: List<Item>,
)

@Serializable
data class ReasoningConfig(
    val effort: String? = null,
    val exclude: Boolean? = null,
)

@Serializable
data class ModelConfig(
    val alias: String,
    val openRouterId: String,
    val sendTemperature: Boolean = true,
    val reasoning: ReasoningConfig? = null,
    val assumedReasoningTokens: Int = 0,
)

@Serializable
data class DryRunConfig(
    val answerable: Int = 5,
    val unanswerable: Int = 5,
    val reps: Int = 1,
)

@Serializable
data class ExperimentConfig(
    val seed: Long,
    val answerableCount: Int,
    val unanswerableCount: Int,
    val reps: Int,
    val temperature: Double,
    val maxTokens: Int,
    val capEur: Double,
    val safetyMarginEur: Double,
    val eurPerUsd: Double,
    val concurrency: Int,
    val charsPerToken: Double,
    val assumedAnswerTokens: Int,
    val bootstrapResamples: Int,
    val bootstrapSeed: Long,
    val dryRun: DryRunConfig,
    val models: List<ModelConfig>,
)

/** Hard upper limit for the total project spend; config and CLI can lower it but never raise it. */
const val HARD_CAP_EUR = 10.0

/** All repo-relative locations in one place so tests can run against a temporary workspace. */
class Workspace(val root: Path) {
    val configFile: Path = root.resolve("config/experiment.json")
    val snapshotFile: Path = root.resolve("config/openrouter-models.snapshot.json")
    val rawDir: Path = root.resolve("data/raw")
    val sampleFile: Path = root.resolve("data/sample.json")
    val disputedFile: Path = root.resolve("data/disputed.json")
    val runsDir: Path = root.resolve("data/runs")
    val mockRunsDir: Path = root.resolve("build/mock-runs")
    val docsData: Path = root.resolve("docs/data")

    fun loadConfig(): ExperimentConfig =
        PrettyJson.decodeFromString(ExperimentConfig.serializer(), Files.readString(configFile))

    fun loadSample(): SampleFile =
        PrettyJson.decodeFromString(SampleFile.serializer(), Files.readString(sampleFile))
}

fun ExperimentConfig.validate() {
    require(reps >= 1) { "reps must be >= 1" }
    require(capEur in 0.0..HARD_CAP_EUR) { "capEur must be within 0..$HARD_CAP_EUR (hard cap)" }
    require(safetyMarginEur >= 0 && safetyMarginEur < capEur) { "safetyMarginEur must be in [0, capEur)" }
    require(eurPerUsd > 0) { "eurPerUsd must be positive" }
    require(concurrency >= 1) { "concurrency must be >= 1" }
    require(models.map { it.alias }.toSet().size == models.size) { "model aliases must be unique" }
    require(models.map { it.openRouterId }.toSet().size == models.size) { "model ids must be unique" }
}
