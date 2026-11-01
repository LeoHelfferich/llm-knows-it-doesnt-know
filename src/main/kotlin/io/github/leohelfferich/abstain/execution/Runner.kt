package io.github.leohelfferich.abstain.execution

import java.io.BufferedWriter
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import io.github.leohelfferich.abstain.client.ChatClient
import io.github.leohelfferich.abstain.client.model.ChatException
import io.github.leohelfferich.abstain.client.model.ChatRequest
import io.github.leohelfferich.abstain.config.model.ExperimentConfig
import io.github.leohelfferich.abstain.config.model.ModelConfig
import io.github.leohelfferich.abstain.config.model.validate
import io.github.leohelfferich.abstain.cost.CostGuard
import io.github.leohelfferich.abstain.cost.model.Estimate
import io.github.leohelfferich.abstain.cost.payableUsd
import io.github.leohelfferich.abstain.utils.CompactJson
import io.github.leohelfferich.abstain.utils.PrettyJson
import io.github.leohelfferich.abstain.utils.formatUs
import io.github.leohelfferich.abstain.utils.sha256Hex
import io.github.leohelfferich.abstain.pricing.model.PricingSnapshot
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.prompt.Prompts
import io.github.leohelfferich.abstain.squad.model.Item
import io.github.leohelfferich.abstain.squad.model.Kind
import io.github.leohelfferich.abstain.squad.SquadSource
import io.github.leohelfferich.abstain.execution.model.CallRecord
import io.github.leohelfferich.abstain.execution.model.Manifest
import io.github.leohelfferich.abstain.execution.model.ManifestModel
import io.github.leohelfferich.abstain.execution.model.PromptInfo
import io.github.leohelfferich.abstain.execution.model.RunSpec
import io.github.leohelfferich.abstain.execution.model.RunSummary

/**
 * Executes a run and makes it safe to start again.
 *
 * Finished calls are skipped. Failed calls are retried. The directory is locked so two processes
 * cannot spend the same budget, and a fingerprint check refuses to append to a directory whose
 * configuration, sample, or prompts have changed.
 */
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

    /** One call per repetition, item, model, and prompt, in that order so a stop leaves finished repetitions behind. */
    private fun plan(spec: RunSpec): List<Task> =
        (1..spec.reps).flatMap { rep ->
            spec.items.flatMap { item ->
                config.models.flatMap { model ->
                    PromptVariant.entries.map { variant ->
                        Task(item, model, variant, rep, Prompts.render(variant, item.context, item.question))
                    }
                }
            }
        }

    fun execute(spec: RunSpec, estimate: Estimate?): RunSummary {
        config.validate()
        Files.createDirectories(spec.dir)
        // Two processes on one directory would each reserve up to the cap.
        return FileChannel.open(spec.dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val fileLock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
            // Same JVM: a second thread throws instead of getting a lock. That is also "already running".
            if (fileLock == null) error("Run '${spec.runId}' is already in progress")
            try {
                runLocked(spec, estimate)
            } finally {
                fileLock.release()
            }
        }
    }

    private fun runLocked(spec: RunSpec, estimate: Estimate?): RunSummary {
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
        val previousEstimate = previous?.estimate

        Files.writeString(
            manifestFile,
            PrettyJson.encodeToString(
                Manifest.serializer(),
                currentManifest(spec, "running", null, false, startedAt, commit, dirty, fingerprint, all.size, estimate, previousEstimate),
            ),
        )
        log("Run '${spec.runId}': ${all.size} planned calls, ${done.size} already done, ${pending.size} to do.")
        if (pending.isEmpty()) {
            val m = currentManifest(spec, "complete", null, true, startedAt, commit, dirty, fingerprint, all.size, estimate, previousEstimate)
            Files.writeString(manifestFile, PrettyJson.encodeToString(Manifest.serializer(), m))
            return RunSummary("complete", null, m.okCalls, m.errorCalls, m.spentEurThisRun)
        }

        val stop = AtomicBoolean(false)
        val stopReason = AtomicReference<String?>(null)
        val completed = AtomicInteger(0)
        val consecutiveErrors = AtomicInteger(0)
        val lock = Any()
        val writer: BufferedWriter = Files.newBufferedWriter(
            CallLog.file(spec.dir), StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
        val pool = Executors.newFixedThreadPool(config.concurrency)
        try {
            for (task in pending) {
                pool.submit {
                    if (stop.get()) return@submit
                    val price = snapshot.model(task.model.openRouterId)
                    val worst = CostGuard.worstCaseUsd(task.prompt, config.maxTokens, price)
                    if (!guard.tryReserve(worst)) {
                        recordStop(stop, stopReason, "cost cap reached (spent EUR ${"%.2f".formatUs(guard.spentEur())} of EUR ${"%.2f".formatUs(config.capEur)}, safety margin EUR ${"%.2f".formatUs(config.safetyMarginEur)})")
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
                        val (cost, source) = payableUsd(result.costUsd, computed, worst)
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
                        if (e.status in listOf(401, 402, 403)) recordStop(stop, stopReason, "fatal API error: ${e.message}")
                        // Twenty failures in a row means the provider is down, not one bad question.
                        else if (consecutiveErrors.incrementAndGet() >= 20) recordStop(stop, stopReason, "20 consecutive failed calls; last error: ${e.message}")
                    } catch (e: Exception) {
                        record = CallRecord(
                            task.item.id, task.item.kind, task.model.alias, task.model.openRouterId, task.variant, task.rep,
                            "error", started, temperature, task.prompt.length, costUsd = 0.0, costSource = "none",
                            error = "Unexpected ${e.javaClass.simpleName}",
                        )
                        recordStop(stop, stopReason, "unexpected error ${e.javaClass.simpleName}")
                    }
                    // Persist before releasing the reservation, so a crash cannot forget a call the cap already counted.
                    var settled = false
                    try {
                        synchronized(lock) {
                            writer.write(CompactJson.encodeToString(CallRecord.serializer(), record))
                            writer.newLine()
                            writer.flush()
                        }
                        guard.settle(worst, actualUsd)
                        settled = true
                    } catch (e: Exception) {
                        if (!settled) guard.settle(worst, maxOf(actualUsd, worst.coerceAtLeast(0.0)))
                        recordStop(stop, stopReason, "could not append the call log (${e.javaClass.simpleName})")
                    }
                    val n = completed.incrementAndGet()
                    // A line every 25 calls keeps a 6000-call run readable.
                    if (n % 25 == 0 || n == pending.size) {
                        log("  [$n/${pending.size}] spent EUR ${"%.2f".formatUs(guard.spentEur())}, usable budget left EUR ${"%.2f".formatUs(guard.remainingEur())}")
                    }
                }
            }
        } finally {
            pool.shutdown()
            // Longer than any run we would leave going. The cap stops a real run well before this.
            pool.awaitTermination(7, TimeUnit.DAYS)
            synchronized(lock) { writer.close() }
        }

        val reason = stopReason.get()
        val okNow = CallLog.okByKey(CallLog.read(spec.dir)).size
        // Failures are retried on the next launch. Calling that complete would let analyze publish a partial run without the partial-run warning.
        val (status, recordedReason) = when {
            okNow >= all.size -> "complete" to null
            reason != null -> "stopped" to reason
            else -> "incomplete" to "$okNow of ${all.size} calls succeeded; re-run the same command to retry the failed ones"
        }
        val m = currentManifest(spec, status, recordedReason, true, startedAt, commit, dirty, fingerprint, all.size, estimate, previousEstimate)
        Files.writeString(manifestFile, PrettyJson.encodeToString(Manifest.serializer(), m))
        return RunSummary(status, recordedReason, m.okCalls, m.errorCalls, m.spentEurThisRun)
    }

    private fun recordStop(stop: AtomicBoolean, stopReason: AtomicReference<String?>, reason: String) {
        if (stop.compareAndSet(false, true)) stopReason.set(reason)
    }

    /** Manifest rebuilt from the call log, so a crash between calls cannot publish a stale count. */
    private fun currentManifest(
        spec: RunSpec,
        status: String,
        stopReason: String?,
        finished: Boolean,
        startedAt: String,
        commit: String?,
        dirty: Boolean?,
        fingerprint: String,
        plannedCalls: Int,
        estimate: Estimate?,
        previousEstimate: Estimate?,
    ): Manifest {
        val records = CallLog.read(spec.dir)
        val spentUsd = records.sumOf { it.costUsd ?: 0.0 }
        return Manifest(
            runId = spec.runId,
            kind = spec.kind,
            mock = spec.mock,
            status = status,
            stopReason = stopReason,
            startedAt = startedAt,
            finishedAt = if (finished) Instant.now().toString() else null,
            gitCommit = commit,
            gitDirty = dirty,
            java = "${System.getProperty("java.vendor")} ${System.getProperty("java.version")}",
            fingerprint = fingerprint,
            configSha256 = configSha256,
            sampleSha256 = sampleSha256,
            sourceDatasetSha256 = sourceSha256,
            seed = config.seed,
            reps = spec.reps,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            itemCount = spec.items.size,
            answerableItems = spec.items.count { it.kind == Kind.ANSWERABLE },
            unanswerableItems = spec.items.count { it.kind == Kind.UNANSWERABLE },
            prompts = PromptVariant.entries.associate { variant ->
                variant.key to PromptInfo(Prompts.VERSION, Prompts.templateSha256(variant))
            },
            models = config.models.map { model ->
                val price = snapshot.model(model.openRouterId)
                ManifestModel(
                    model.alias,
                    model.openRouterId,
                    price.canonicalSlug,
                    price.name,
                    price.promptPrice * 1e6,
                    price.completionPrice * 1e6,
                    if (model.sendTemperature) config.temperature else null,
                    model.reasoning,
                )
            },
            plannedCalls = plannedCalls,
            okCalls = CallLog.okByKey(records).size,
            errorCalls = records.count { it.status == "error" },
            spentUsdThisRun = spentUsd,
            spentEurThisRun = spentUsd * config.eurPerUsd,
            eurPerUsd = config.eurPerUsd,
            capEur = config.capEur,
            estimate = estimate ?: previousEstimate,
        )
    }
}
