package io.github.leohelfferich.abstain.cli

import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

import io.github.leohelfferich.abstain.analysis.AnalysisPipeline
import io.github.leohelfferich.abstain.analysis.model.ItemsFile
import io.github.leohelfferich.abstain.analysis.model.Results
import io.github.leohelfferich.abstain.analysis.model.ReviewFile
import io.github.leohelfferich.abstain.client.ChatClient
import io.github.leohelfferich.abstain.client.OpenRouterClient
import io.github.leohelfferich.abstain.client.Secrets
import io.github.leohelfferich.abstain.client.SimulatedChatClient
import io.github.leohelfferich.abstain.config.model.ExperimentConfig
import io.github.leohelfferich.abstain.config.Workspace
import io.github.leohelfferich.abstain.config.model.validate
import io.github.leohelfferich.abstain.cost.model.Estimate
import io.github.leohelfferich.abstain.cost.Estimator
import io.github.leohelfferich.abstain.execution.CallLog
import io.github.leohelfferich.abstain.execution.model.Manifest
import io.github.leohelfferich.abstain.execution.model.RunSpec
import io.github.leohelfferich.abstain.execution.model.RunSummary
import io.github.leohelfferich.abstain.execution.Runner
import io.github.leohelfferich.abstain.utils.PrettyJson
import io.github.leohelfferich.abstain.utils.formatUs
import io.github.leohelfferich.abstain.utils.sha256Hex
import io.github.leohelfferich.abstain.pricing.model.PricingSnapshot
import io.github.leohelfferich.abstain.squad.model.Item
import io.github.leohelfferich.abstain.squad.model.Kind
import io.github.leohelfferich.abstain.squad.model.SampleFile
import io.github.leohelfferich.abstain.squad.Sampler
import io.github.leohelfferich.abstain.squad.SquadParser
import io.github.leohelfferich.abstain.squad.SquadSource

private val USAGE = """
llm-knows-it-doesnt-know: do LLMs admit when the answer is not in the context?

Usage: ./gradlew run --args="<command> [options]"

Commands:
  fetch-data              Download SQuAD 2.0 dev set to data/raw/ and verify its SHA-256
  sample                  Draw the seeded sample into data/sample.json   [--source FILE] [--check]
  estimate                Expected calls and cost, full run and dry run  [--refresh] [--from-run DIR] [--dry-run]
  run                     Execute calls                                  [--dry-run] [--mock] [--yes] [--run-id ID] [--cap-eur X]
  analyze                 Compute metrics from a run                     [--run DIR] [--out FILE] [--items-out FILE]
                                                                         [--review-out FILE] [--disputed FILE] [--allow-mock]

The API key is read from the OPENROUTER_API_KEY environment variable and is never printed or stored.
""".trimIndent()

/**
 * Command line for the experiment.
 *
 * The API key is taken from the environment map passed in, and from nowhere else. Exit codes are
 * 0 for success, 1 for a runtime failure or a refused run, and 2 for a usage error.
 */
class Cli(
    private val workspace: Workspace,
    private val out: PrintStream,
    private val err: PrintStream,
    private val env: Map<String, String>,
    private val stdin: BufferedReader,
    private val clientFactory: (String) -> ChatClient = { OpenRouterClient(it) },
    private val today: () -> String = { LocalDate.now().toString() },
) {
    private class UsageError(message: String, val showUsage: Boolean = true) : RuntimeException(message)

    private class Args(val command: String, val flags: Map<String, String?>) {
        fun has(name: String) = name in flags
        fun value(name: String) = flags[name]
    }

    /** A flag that takes the following argument, unless that argument is itself a flag. */
    private val valueFlags = setOf("source", "from-run", "run-id", "cap-eur", "run", "out", "items-out", "review-out", "disputed")

    private data class Flag(val name: String, val value: String?, val consumed: Int)

    /**
     * Splits the command from its flags.
     * A value flag consumes the next token. A token that starts with `--` is never that value.
     */
    private fun parse(args: Array<String>): Args {
        val command = args.firstOrNull() ?: throw UsageError("missing command")
        return Args(command, readFlags(args.drop(1)))
    }

    private fun readFlag(tokens: List<String>): Flag {
        val token = tokens.first()
        if (!token.startsWith("--")) throw UsageError("unexpected argument '$token'")

        val name = token.removePrefix("--")
        if (name !in valueFlags) return Flag(name, value = null, consumed = 1)

        val value = tokens.getOrNull(1)?.takeUnless { it.startsWith("--") }
            ?: throw UsageError("--$name needs a value")
        return Flag(name, value, consumed = 2)
    }

    private tailrec fun readFlags(tokens: List<String>, acc: Map<String, String?> = emptyMap()): Map<String, String?> {
        if (tokens.isEmpty()) return acc
        val flag = readFlag(tokens)
        return readFlags(tokens.drop(flag.consumed), acc + (flag.name to flag.value))
    }

    /** Dispatches one command. Returns the process exit code. */
    fun run(args: Array<String>): Int = try {
        val parsed = parse(args)
        when (parsed.command) {
            "fetch-data" -> fetchData()
            "sample" -> sample(parsed)
            "estimate" -> estimate(parsed)
            "run" -> runCommand(parsed)
            "analyze" -> analyze(parsed)
            "help", "--help", "-h" -> { out.println(USAGE); 0 }
            else -> throw UsageError("unknown command '${parsed.command}'")
        }
    } catch (e: UsageError) {
        err.println("error: ${e.message}")
        if (e.showUsage) {
            err.println()
            err.println(USAGE)
        }
        2
    } catch (e: Exception) {
        err.println("error: ${Secrets.redact(e.message ?: e.javaClass.simpleName, Secrets.apiKeyFromEnv(env))}")
        1
    }

    /** Downloads the SQuAD dev set if it is missing, then checks the pinned checksum. */
    private fun fetchData(): Int {
        val target = workspace.rawDir.resolve("dev-v2.0.json")
        if (!Files.exists(target)) {
            out.println("Downloading ${SquadSource.URL}")
            SquadSource.download(target)
        }
        SquadSource.verifyChecksum(target)
        out.println("OK: $target matches SHA-256 ${SquadSource.SHA256}")
        return 0
    }

    /** Draws the seeded sample. `--check` compares it to the committed file and writes nothing. */
    private fun sample(arguments: Args): Int {
        val config = workspace.loadConfig()
        config.validate()
        val source = arguments.value("source")?.let { Path.of(it) } ?: workspace.rawDir.resolve("dev-v2.0.json")
        check(Files.exists(source)) { "Source file $source not found; run `fetch-data` first" }
        SquadSource.verifyChecksum(source)
        val sample = Sampler.buildSampleFile(config, SquadParser.parse(Files.readString(source)))
        val json = PrettyJson.encodeToString(SampleFile.serializer(), sample) + "\n"
        if (arguments.has("check")) {
            val committed = Files.readString(workspace.sampleFile)
            check(committed == json) { "${workspace.sampleFile} differs from a fresh draw with seed ${config.seed}" }
            out.println("OK: ${workspace.sampleFile} is reproduced exactly by seed ${config.seed}")
            return 0
        }
        Files.createDirectories(workspace.sampleFile.parent)
        Files.writeString(workspace.sampleFile, json)
        out.println("Wrote ${workspace.sampleFile}: ${sample.items.count { it.kind == Kind.ANSWERABLE }} answerable, " +
            "${sample.items.count { it.kind == Kind.UNANSWERABLE }} unanswerable (seed ${sample.seed})")
        return 0
    }

    private fun loadSnapshot(config: ExperimentConfig, refresh: Boolean): PricingSnapshot {
        if (!refresh) return PricingSnapshot.load(workspace.snapshotFile)
        val snap = PricingSnapshot.fromModelList(PricingSnapshot.fetchModelList(), config.models.map { it.openRouterId }, today())
        Files.createDirectories(workspace.snapshotFile.parent)
        Files.writeString(workspace.snapshotFile, PrettyJson.encodeToString(PricingSnapshot.serializer(), snap) + "\n")
        out.println("Refreshed ${workspace.snapshotFile} from the public OpenRouter model list.")
        return snap
    }

    private fun checkSnapshotMatchesConfig(config: ExperimentConfig, snapshot: PricingSnapshot) {
        config.models.forEach { m ->
            val s = snapshot.model(m.openRouterId)
            check(m.sendTemperature == s.supportsTemperature) {
                "Config says sendTemperature=${m.sendTemperature} for ${m.openRouterId} but OpenRouter lists temperature support = ${s.supportsTemperature}"
            }
        }
    }

    /** Prints the dry-run cost and, unless `--dry-run` is set, the full-run cost as well. */
    private fun estimate(arguments: Args): Int {
        val config = workspace.loadConfig().also { it.validate() }
        val snapshot = loadSnapshot(config, arguments.has("refresh"))
        checkSnapshotMatchesConfig(config, snapshot)
        val sample = workspace.loadSample()
        val observed = arguments.value("from-run")?.let { Estimator.observedFrom(CallLog.read(Path.of(it))) }.orEmpty()
        val dry = Estimator.estimate("dry-run", Sampler.dryRunSubset(sample, config.dryRun.answerable, config.dryRun.unanswerable), config.dryRun.reps, config, snapshot, observed)
        val full = Estimator.estimate("full", sample.items, config.reps, config, snapshot, observed)
        if (!arguments.has("dry-run")) { out.println(Estimator.render(full)); out.println() }
        out.println(Estimator.render(dry))
        out.println()
        out.println("Already spent (all real runs under data/runs): EUR %.2f".formatUs(CallLog.spentUsdAcrossRuns(workspace.runsDir) * config.eurPerUsd))
        return 0
    }

    /** What one `run` will execute: which items, where, and whether any of it is paid. */
    private data class RunPlan(
        val config: ExperimentConfig,
        val snapshot: PricingSnapshot,
        val items: List<Item>,
        val reps: Int,
        val kind: String,
        val runId: String,
        val dir: Path,
        val mock: Boolean,
    )

    /**
     * Resolves the run, prints the estimate, and asks before spending money.
     * Returns 1 when the run is refused or does not finish every planned call.
     */
    private fun runCommand(arguments: Args): Int {
        val plan = runPlan(arguments)
        val client = chatClient(plan)
        val estimate = costEstimate(plan)

        out.println(Estimator.render(estimate))
        out.println()

        if (!plan.mock && !confirmSpend(arguments, plan, estimate)) return 1

        val summary = execute(plan, client, estimate)
        report(plan, summary)
        return if (summary.status == "complete") 0 else 1
    }

    private fun runPlan(arguments: Args): RunPlan {
        val configured = workspace.loadConfig().also { it.validate() }
        val config = configured.copy(capEur = capped(arguments, configured)).also { it.validate() }
        val mock = arguments.has("mock")
        val dry = arguments.has("dry-run")

        val snapshot = loadSnapshot(config, refresh = false)
        checkSnapshotMatchesConfig(config, snapshot)

        val sample = workspace.loadSample()
        val items = if (dry) Sampler.dryRunSubset(sample, config.dryRun.answerable, config.dryRun.unanswerable) else sample.items
        val reps = if (dry) config.dryRun.reps else config.reps
        val kind = if (dry) "dry-run" else "full"
        val runId = directoryRunId(arguments.value("run-id") ?: kind)

        return RunPlan(config, snapshot, items, reps, kind, runId, runDirectory(mock, runId), mock)
    }

    /** `--cap-eur` may lower the cap from the config. It may not raise it. */
    private fun capped(arguments: Args, configured: ExperimentConfig): Double {
        val cap = arguments.value("cap-eur")?.let { it.toDoubleOrNull() ?: throw UsageError("--cap-eur must be a number") }
            ?: configured.capEur
        require(cap in 0.0..configured.capEur) { "--cap-eur may only lower the configured cap of EUR ${configured.capEur}" }
        return cap
    }

    private fun directoryRunId(runId: String): String {
        // 80 characters is one directory name, not a path.
        val singleDirectoryName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")
        require(singleDirectoryName.matches(runId) && !runId.contains("..")) {
            "--run-id must be one directory name of letters, digits, '.', '_' and '-'"
        }
        return runId
    }

    private fun runDirectory(mock: Boolean, runId: String): Path {
        val runsRoot = (if (mock) workspace.mockRunsDir else workspace.runsDir).toAbsolutePath().normalize()
        val dir = runsRoot.resolve(runId).normalize()
        require(dir.startsWith(runsRoot) && dir.nameCount == runsRoot.nameCount + 1) {
            "--run-id must stay inside the runs directory"
        }
        return dir
    }

    private fun chatClient(plan: RunPlan): ChatClient {
        if (!plan.mock) {
            val key = Secrets.apiKeyFromEnv(env) ?: throw UsageError(
                "${Secrets.ENV_VAR} is not set (the key is read from the environment only)",
                showUsage = false,
            )
            return clientFactory(key)
        }
        out.println("MOCK MODE: no network, no key, synthetic answers. Output goes to ${plan.dir} and must not be published.")
        return SimulatedChatClient(workspace.loadSample().items, plan.config.models)
    }

    /** A full paid run reuses token counts from the dry run, when that log exists. */
    private fun costEstimate(plan: RunPlan): Estimate {
        val observed = if (plan.kind == "full" && !plan.mock) observedDryRun() else emptyMap()
        return Estimator.estimate(plan.kind, plan.items, plan.reps, plan.config, plan.snapshot, observed)
    }

    private fun observedDryRun() = workspace.runsDir.resolve("dry-run").let { dryDir ->
        if (Files.exists(CallLog.file(dryDir))) Estimator.observedFrom(CallLog.read(dryDir)) else emptyMap()
    }

    /**
     * Prints what is left to spend. Without `--yes`, waits for the word `yes`.
     * Returns false when the run must not start. Nothing has been sent yet.
     */
    private fun confirmSpend(arguments: Args, plan: RunPlan, estimate: Estimate): Boolean {
        val spent = CallLog.spentUsdAcrossRuns(workspace.runsDir) * plan.config.eurPerUsd
        val doneKeys = if (Files.exists(CallLog.file(plan.dir))) CallLog.okByKey(CallLog.read(plan.dir)).keys else emptySet()
        // Finished calls are already inside [spent]. Only the calls still to do are added on top.
        val (remainingCalls, remainingUsd) = Estimator.remainingExpectedUsd(estimate, plan.items, plan.reps, doneKeys)
        val remainingEur = remainingUsd * plan.config.eurPerUsd
        out.println("Already spent across real runs: EUR %.2f of EUR %.2f".formatUs(spent, plan.config.capEur))

        val usable = plan.config.capEur - plan.config.safetyMarginEur
        if (remainingCalls > 0 && (!estimate.expectedFitsCap || spent + remainingEur > usable)) {
            err.println(
                "Refusing to start: expected cost plus already spent exceeds the usable cap (EUR %.2f minus EUR %.2f safety margin)."
                    .formatUs(plan.config.capEur, plan.config.safetyMarginEur),
            )
            return false
        }
        if (arguments.has("yes")) return true

        out.print("This will make up to $remainingCalls paid calls (expected EUR %.2f). Type 'yes' to continue: ".formatUs(remainingEur))
        out.flush()
        if (stdin.readLine()?.trim() == "yes") return true
        out.println("Aborted; nothing was sent.")
        return false
    }

    private fun execute(plan: RunPlan, client: ChatClient, estimate: Estimate) = Runner(
        plan.config,
        plan.snapshot,
        sampleSha256 = sha256Hex(workspace.sampleFile),
        configSha256 = sha256Hex(workspace.configFile),
        client = client,
        log = { out.println(it) },
    ).execute(RunSpec(plan.runId, plan.kind, plan.items, plan.reps, plan.dir, plan.mock), estimate)

    private fun report(plan: RunPlan, summary: RunSummary) {
        out.println(
            "Run '${plan.runId}' ${summary.status}: ${summary.okCalls} ok, ${summary.errorCalls} failed attempts, " +
                "EUR %.2f spent in this run.".formatUs(summary.spentEur),
        )
        summary.stopReason?.let { err.println("Stopped early: $it") }
        out.println("Results: ${plan.dir} (commit calls.jsonl and manifest.json, then run `analyze`)")
    }

    /** Scores a finished run. Only a complete, non-mock full run is written under docs/. */
    private fun analyze(arguments: Args): Int {
        val config = workspace.loadConfig()
        val runDir = arguments.value("run")?.let { Path.of(it) } ?: workspace.runsDir.resolve("full")
        check(Files.exists(runDir.resolve("manifest.json"))) { "No manifest.json in $runDir" }
        val manifest = PrettyJson.decodeFromString(Manifest.serializer(), Files.readString(runDir.resolve("manifest.json")))
        val explicitOut = arguments.has("out")
        if (manifest.mock && explicitOut && !arguments.has("allow-mock")) {
            throw UsageError("run '${manifest.runId}' is a MOCK run (synthetic); pass --allow-mock to write it anywhere explicitly")
        }
        val publishable = manifest.kind == "full" && !manifest.mock
        val results = arguments.value("out")?.let { Path.of(it) }
            ?: if (publishable) workspace.docsData.resolve("results.json") else runDir.resolve("analysis.json")
        val itemsOut = arguments.value("items-out")?.let { Path.of(it) }
            ?: if (publishable) workspace.docsData.resolve("items.json") else runDir.resolve("items.json")
        val reviewOut = arguments.value("review-out")?.let { Path.of(it) } ?: runDir.resolve("review-candidates.json")
        val disputed = arguments.value("disputed")?.let { Path.of(it) } ?: workspace.disputedFile

        val output = AnalysisPipeline.analyze(runDir, workspace.loadSample(), config, sha256Hex(workspace.sampleFile), disputed)
        listOf(results to PrettyJson.encodeToString(Results.serializer(), output.results),
            itemsOut to PrettyJson.encodeToString(ItemsFile.serializer(), output.items),
            reviewOut to PrettyJson.encodeToString(ReviewFile.serializer(), output.review)).forEach { (path, json) ->
            Files.createDirectories(path.toAbsolutePath().parent)
            Files.writeString(path, json + "\n")
        }
        out.println(AnalysisPipeline.renderTable(output.results))
        if (manifest.mock) out.println("NOTE: this is a MOCK run; numbers are synthetic.")
        if (manifest.status != "complete") out.println("NOTE: run status is '${manifest.status}' (${manifest.okCalls}/${manifest.plannedCalls} calls); results are partial.")
        out.println("Wrote $results, $itemsOut and $reviewOut (${output.review.candidates.size} items flagged for manual review)")
        return 0
    }
}
