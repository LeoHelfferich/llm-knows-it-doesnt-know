package io.github.leohelfferich.abstain

import kotlinx.serialization.Serializable
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class CallRecord(
    val itemId: String,
    val kind: Kind,
    val model: String,
    val openRouterId: String,
    val prompt: PromptVariant,
    val rep: Int,
    val status: String,
    val requestedAt: String,
    val temperature: Double?,
    val promptChars: Int,
    val content: String? = null,
    val finishReason: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val costUsd: Double? = null,
    val costSource: String? = null,
    val provider: String? = null,
    val responseModel: String? = null,
    val generationId: String? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
) {
    val key get() = callKey(itemId, model, prompt, rep)
}

fun callKey(itemId: String, model: String, prompt: PromptVariant, rep: Int) = "$itemId|$model|${prompt.key}|$rep"

@Serializable
data class PromptInfo(val version: Int, val sha256: String)

@Serializable
data class ManifestModel(
    val alias: String,
    val openRouterId: String,
    val canonicalSlug: String?,
    val name: String,
    val promptUsdPerMillion: Double,
    val completionUsdPerMillion: Double,
    val temperatureSent: Double?,
    val reasoning: ReasoningConfig?,
)

@Serializable
data class Manifest(
    val schemaVersion: Int = 1,
    val runId: String,
    val kind: String,
    val mock: Boolean,
    val status: String,
    val stopReason: String? = null,
    val startedAt: String,
    val finishedAt: String? = null,
    val gitCommit: String? = null,
    val gitDirty: Boolean? = null,
    val java: String,
    val fingerprint: String,
    val configSha256: String,
    val sampleSha256: String,
    val sourceDatasetSha256: String,
    val seed: Long,
    val reps: Int,
    val temperature: Double,
    val maxTokens: Int,
    val itemCount: Int,
    val answerableItems: Int,
    val unanswerableItems: Int,
    val prompts: Map<String, PromptInfo>,
    val models: List<ManifestModel>,
    val plannedCalls: Int,
    val okCalls: Int = 0,
    val errorCalls: Int = 0,
    val spentUsdThisRun: Double = 0.0,
    val spentEurThisRun: Double = 0.0,
    val eurPerUsd: Double,
    val capEur: Double,
    val estimate: Estimate? = null,
)

object CallLog {
    fun file(runDir: Path): Path = runDir.resolve("calls.jsonl")

    fun read(runDir: Path): List<CallRecord> {
        val f = file(runDir)
        if (!Files.exists(f)) return emptyList()
        return Files.readAllLines(f).filter { it.isNotBlank() }.map { CompactJson.decodeFromString(CallRecord.serializer(), it) }
    }

    /** Latest ok record per key. */
    fun okByKey(records: List<CallRecord>): Map<String, CallRecord> =
        records.filter { it.status == "ok" }.associateBy { it.key }

    fun spentUsdAcrossRuns(runsDir: Path): Double {
        if (!Files.isDirectory(runsDir)) return 0.0
        Files.list(runsDir).use { dirs ->
            return dirs.filter { Files.isDirectory(it) }.toList().sumOf { d -> read(d).sumOf { it.costUsd ?: 0.0 } }
        }
    }
}

data class RunSpec(
    val runId: String,
    val kind: String,
    val items: List<Item>,
    val reps: Int,
    val dir: Path,
    val mock: Boolean,
)

data class RunSummary(val status: String, val stopReason: String?, val okCalls: Int, val errorCalls: Int, val spentEur: Double)

class Runner(
    private val config: ExperimentConfig,
    private val snapshot: PricingSnapshot,
    private val sampleSha256: String,
    private val configSha256: String,
    private val client: ChatClient,
    private val log: (String) -> Unit,
    private val sourceSha256: String = SquadSource.SHA256,
    private val gitInfo: () -> Pair<String?, Boolean?> = { GitInfo.read() },
) {
    private data class Task(val item: Item, val model: ModelConfig, val variant: PromptVariant, val rep: Int, val prompt: String)

    fun fingerprint(spec: RunSpec): String = sha256Hex(
        buildString {
            append(PrettyJson.encodeToString(ExperimentConfig.serializer(), config)).append('\n')
            append(spec.items.joinToString(",") { it.id }).append('\n')
            append(spec.reps).append('\n')
            PromptVariant.entries.forEach { append(Prompts.templateSha256(it)).append('\n') }
            append(snapshot.models.joinToString(",") { it.id + ":" + it.promptUsdPerToken + ":" + it.completionUsdPerToken })
        },
    )

    private fun plan(spec: RunSpec): List<Task> {
        val tasks = ArrayList<Task>()
        for (rep in 1..spec.reps) for (item in spec.items) for (model in config.models) for (variant in PromptVariant.entries) {
            tasks += Task(item, model, variant, rep, Prompts.render(variant, item.context, item.question))
        }
        return tasks
    }

    fun execute(spec: RunSpec, estimate: Estimate?): RunSummary {
        config.validate()
        Files.createDirectories(spec.dir)
        val fingerprint = fingerprint(spec)
        val manifestFile = spec.dir.resolve("manifest.json")
        val previous = if (Files.exists(manifestFile)) PrettyJson.decodeFromString(Manifest.serializer(), Files.readString(manifestFile)) else null
        if (previous != null) {
            check(previous.fingerprint == fingerprint) {
                "Run '${spec.runId}' already exists with a different configuration/sample/prompts. " +
                    "Use a new --run-id (existing results are never overwritten)."
            }
        }
        val startedAt = previous?.startedAt ?: Instant.now().toString()
        val (commit, dirty) = gitInfo()

        val all = plan(spec)
        val existing = CallLog.read(spec.dir)
        val done = CallLog.okByKey(existing).keys
        val pending = all.filter { callKey(it.item.id, it.model.alias, it.variant, it.rep) !in done }
        val priorSpentUsd = CallLog.spentUsdAcrossRuns(spec.dir.parent)
        val guard = CostGuard(config.capEur, config.safetyMarginEur, config.eurPerUsd, priorSpentUsd * config.eurPerUsd)

        fun manifest(status: String, stopReason: String?, finished: Boolean): Manifest {
            val records = CallLog.read(spec.dir)
            val ok = CallLog.okByKey(records).size
            val errors = records.count { it.status == "error" }
            val spentUsd = records.sumOf { it.costUsd ?: 0.0 }
            return Manifest(
                runId = spec.runId, kind = spec.kind, mock = spec.mock, status = status, stopReason = stopReason,
                startedAt = startedAt, finishedAt = if (finished) Instant.now().toString() else null,
                gitCommit = commit, gitDirty = dirty,
                java = "${System.getProperty("java.vendor")} ${System.getProperty("java.version")}",
                fingerprint = fingerprint, configSha256 = configSha256, sampleSha256 = sampleSha256,
                sourceDatasetSha256 = sourceSha256, seed = config.seed, reps = spec.reps,
                temperature = config.temperature, maxTokens = config.maxTokens,
                itemCount = spec.items.size,
                answerableItems = spec.items.count { it.kind == Kind.ANSWERABLE },
                unanswerableItems = spec.items.count { it.kind == Kind.UNANSWERABLE },
                prompts = PromptVariant.entries.associate { it.key to PromptInfo(Prompts.VERSION, Prompts.templateSha256(it)) },
                models = config.models.map { m ->
                    val p = snapshot.model(m.openRouterId)
                    ManifestModel(
                        m.alias, m.openRouterId, p.canonicalSlug, p.name, p.promptPrice * 1e6, p.completionPrice * 1e6,
                        if (m.sendTemperature) config.temperature else null, m.reasoning,
                    )
                },
                plannedCalls = all.size, okCalls = ok, errorCalls = errors,
                spentUsdThisRun = spentUsd, spentEurThisRun = spentUsd * config.eurPerUsd,
                eurPerUsd = config.eurPerUsd, capEur = config.capEur, estimate = estimate ?: previous?.estimate,
            )
        }

        Files.writeString(manifestFile, PrettyJson.encodeToString(Manifest.serializer(), manifest("running", null, false)))
        log("Run '${spec.runId}': ${all.size} planned calls, ${done.size} already done, ${pending.size} to do.")
        if (pending.isEmpty()) {
            val m = manifest("complete", null, true)
            Files.writeString(manifestFile, PrettyJson.encodeToString(Manifest.serializer(), m))
            return RunSummary("complete", null, m.okCalls, m.errorCalls, m.spentEurThisRun)
        }

        val stop = AtomicBoolean(false)
        val stopReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val completed = AtomicInteger(0)
        val consecutiveErrors = AtomicInteger(0)
        val lock = Any()
        val writer: BufferedWriter = Files.newBufferedWriter(
            CallLog.file(spec.dir), StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
        fun abort(reason: String) {
            if (stop.compareAndSet(false, true)) stopReason.set(reason)
        }

        val pool = Executors.newFixedThreadPool(config.concurrency)
        try {
            for (task in pending) {
                pool.submit {
                    if (stop.get()) return@submit
                    val price = snapshot.model(task.model.openRouterId)
                    val worst = CostGuard.worstCaseUsd(task.prompt, config.maxTokens, price)
                    if (!guard.tryReserve(worst)) {
                        abort("cost cap reached (spent EUR ${"%.2f".format(guard.spentEur())} of EUR ${"%.2f".format(config.capEur)}, safety margin EUR ${"%.2f".format(config.safetyMarginEur)})")
                        return@submit
                    }
                    val temperature = if (task.model.sendTemperature) config.temperature else null
                    val started = Instant.now().toString()
                    var record: CallRecord
                    var actualUsd = 0.0
                    try {
                        val result = client.complete(
                            ChatRequest(task.model, task.prompt, temperature, config.maxTokens, task.item.id, task.variant, task.rep),
                        )
                        val computed = if (result.promptTokens != null && result.completionTokens != null) {
                            result.promptTokens * price.promptPrice + result.completionTokens * price.completionPrice
                        } else null
                        val (cost, source) = when {
                            result.costUsd != null -> result.costUsd to "reported"
                            computed != null -> computed to "computed"
                            else -> worst to "worst-case"
                        }
                        actualUsd = cost
                        record = CallRecord(
                            task.item.id, task.item.kind, task.model.alias, task.model.openRouterId, task.variant, task.rep,
                            "ok", started, temperature, task.prompt.length, result.content, result.finishReason,
                            result.promptTokens, result.completionTokens, result.reasoningTokens, cost, source,
                            result.provider, result.responseModel, result.generationId, result.latencyMs,
                        )
                        consecutiveErrors.set(0)
                    } catch (e: ChatException) {
                        record = CallRecord(
                            task.item.id, task.item.kind, task.model.alias, task.model.openRouterId, task.variant, task.rep,
                            "error", started, temperature, task.prompt.length, costUsd = 0.0, costSource = "none",
                            error = e.message,
                        )
                        if (e.status in listOf(401, 402, 403)) abort("fatal API error: ${e.message}")
                        else if (consecutiveErrors.incrementAndGet() >= 20) abort("20 consecutive failed calls; last error: ${e.message}")
                    } catch (e: Exception) {
                        record = CallRecord(
                            task.item.id, task.item.kind, task.model.alias, task.model.openRouterId, task.variant, task.rep,
                            "error", started, temperature, task.prompt.length, costUsd = 0.0, costSource = "none",
                            error = "Unexpected ${e.javaClass.simpleName}",
                        )
                        abort("unexpected error ${e.javaClass.simpleName}")
                    } finally {
                        guard.settle(worst, actualUsd)
                    }
                    synchronized(lock) {
                        writer.write(CompactJson.encodeToString(CallRecord.serializer(), record))
                        writer.newLine()
                        writer.flush()
                    }
                    val n = completed.incrementAndGet()
                    if (n % 25 == 0 || n == pending.size) {
                        log("  [$n/${pending.size}] spent EUR ${"%.2f".format(guard.spentEur())}, usable budget left EUR ${"%.2f".format(guard.remainingEur())}")
                    }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(7, TimeUnit.DAYS)
            synchronized(lock) { writer.close() }
        }

        val reason = stopReason.get()
        val m = manifest(if (reason == null) "complete" else "stopped", reason, true)
        Files.writeString(manifestFile, PrettyJson.encodeToString(Manifest.serializer(), m))
        val finalStatus = if (m.okCalls >= all.size) "complete" else m.status
        return RunSummary(finalStatus, reason, m.okCalls, m.errorCalls, m.spentEurThisRun)
    }
}

object GitInfo {
    fun read(): Pair<String?, Boolean?> = try {
        val commit = run("git", "rev-parse", "HEAD")
        val dirty = run("git", "status", "--porcelain")?.isNotBlank()
        commit to dirty
    } catch (e: Exception) {
        null to null
    }

    private fun run(vararg cmd: String): String? {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        return if (p.waitFor() == 0) out else null
    }
}
