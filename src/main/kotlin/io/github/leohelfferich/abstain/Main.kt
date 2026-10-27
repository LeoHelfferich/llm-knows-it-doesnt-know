package io.github.leohelfferich.abstain

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.system.exitProcess

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

class Cli(
    private val ws: Workspace,
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

    private val valueFlags = setOf("source", "from-run", "run-id", "cap-eur", "run", "out", "items-out", "review-out", "disputed")

    private fun parse(args: Array<String>): Args {
        val command = args.firstOrNull() ?: throw UsageError("missing command")
        val flags = linkedMapOf<String, String?>()
        var i = 1
        while (i < args.size) {
            val a = args[i]
            if (!a.startsWith("--")) throw UsageError("unexpected argument '$a'")
            val name = a.removePrefix("--")
            if (name in valueFlags) {
                flags[name] = args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") } ?: throw UsageError("--$name needs a value")
                i += 2
            } else {
                flags[name] = null
                i += 1
            }
        }
        return Args(command, flags)
    }

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

    private fun fetchData(): Int {
        val target = ws.rawDir.resolve("dev-v2.0.json")
        if (!Files.exists(target)) {
            out.println("Downloading ${SquadSource.URL}")
            SquadSource.download(target)
        }
        SquadSource.verifyChecksum(target)
        out.println("OK: $target matches SHA-256 ${SquadSource.SHA256}")
        return 0
    }

    private fun sample(a: Args): Int {
        val config = ws.loadConfig()
        config.validate()
        val source = a.value("source")?.let { Path.of(it) } ?: ws.rawDir.resolve("dev-v2.0.json")
        check(Files.exists(source)) { "Source file $source not found; run `fetch-data` first" }
        SquadSource.verifyChecksum(source)
        val sample = Sampler.buildSampleFile(config, SquadParser.parse(Files.readString(source)))
        val json = PrettyJson.encodeToString(SampleFile.serializer(), sample) + "\n"
        if (a.has("check")) {
            val committed = Files.readString(ws.sampleFile)
            check(committed == json) { "${ws.sampleFile} differs from a fresh draw with seed ${config.seed}" }
            out.println("OK: ${ws.sampleFile} is reproduced exactly by seed ${config.seed}")
            return 0
        }
        Files.createDirectories(ws.sampleFile.parent)
        Files.writeString(ws.sampleFile, json)
        out.println("Wrote ${ws.sampleFile}: ${sample.items.count { it.kind == Kind.ANSWERABLE }} answerable, " +
            "${sample.items.count { it.kind == Kind.UNANSWERABLE }} unanswerable (seed ${sample.seed})")
        return 0
    }

    private fun loadSnapshot(config: ExperimentConfig, refresh: Boolean): PricingSnapshot {
        if (!refresh) return PricingSnapshot.load(ws.snapshotFile)
        val snap = PricingSnapshot.fromModelList(PricingSnapshot.fetchModelList(), config.models.map { it.openRouterId }, today())
        Files.createDirectories(ws.snapshotFile.parent)
        Files.writeString(ws.snapshotFile, PrettyJson.encodeToString(PricingSnapshot.serializer(), snap) + "\n")
        out.println("Refreshed ${ws.snapshotFile} from the public OpenRouter model list.")
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

    private fun estimate(a: Args): Int {
        val config = ws.loadConfig().also { it.validate() }
        val snapshot = loadSnapshot(config, a.has("refresh"))
        checkSnapshotMatchesConfig(config, snapshot)
        val sample = ws.loadSample()
        val observed = a.value("from-run")?.let { Estimator.observedFrom(CallLog.read(Path.of(it))) }.orEmpty()
        val dry = Estimator.estimate("dry-run", Sampler.dryRunSubset(sample, config.dryRun.answerable, config.dryRun.unanswerable), config.dryRun.reps, config, snapshot, observed)
        val full = Estimator.estimate("full", sample.items, config.reps, config, snapshot, observed)
        if (!a.has("dry-run")) { out.println(Estimator.render(full)); out.println() }
        out.println(Estimator.render(dry))
        out.println()
        out.println("Already spent (all real runs under data/runs): EUR %.2f".format(CallLog.spentUsdAcrossRuns(ws.runsDir) * config.eurPerUsd))
        return 0
    }

    private fun runCommand(a: Args): Int {
        val config0 = ws.loadConfig().also { it.validate() }
        val cap = a.value("cap-eur")?.let { it.toDoubleOrNull() ?: throw UsageError("--cap-eur must be a number") } ?: config0.capEur
        require(cap in 0.0..config0.capEur) { "--cap-eur may only lower the configured cap of EUR ${config0.capEur}" }
        val config = config0.copy(capEur = cap).also { it.validate() }
        val mock = a.has("mock")
        val dry = a.has("dry-run")
        val snapshot = loadSnapshot(config, refresh = false)
        checkSnapshotMatchesConfig(config, snapshot)
        val sample = ws.loadSample()
        val items = if (dry) Sampler.dryRunSubset(sample, config.dryRun.answerable, config.dryRun.unanswerable) else sample.items
        val reps = if (dry) config.dryRun.reps else config.reps
        val kind = if (dry) "dry-run" else "full"
        val runId = a.value("run-id") ?: kind
        require(Regex("[A-Za-z0-9._-]+").matches(runId)) { "--run-id may only contain letters, digits, '.', '_' and '-'" }
        val dir = (if (mock) ws.mockRunsDir else ws.runsDir).resolve(runId)

        val client: ChatClient = if (mock) {
            out.println("MOCK MODE: no network, no key, synthetic answers. Output goes to $dir and must not be published.")
            SimulatedChatClient(sample.items, config.models)
        } else {
            val key = Secrets.apiKeyFromEnv(env) ?: throw UsageError("${Secrets.ENV_VAR} is not set (the key is read from the environment only)", showUsage = false)
            clientFactory(key)
        }

        val observed = if (!dry && !mock) {
            val dryDir = ws.runsDir.resolve("dry-run")
            if (Files.exists(CallLog.file(dryDir))) Estimator.observedFrom(CallLog.read(dryDir)) else emptyMap()
        } else emptyMap()
        val estimate = Estimator.estimate(kind, items, reps, config, snapshot, observed)
        out.println(Estimator.render(estimate))
        out.println()

        if (!mock) {
            val spent = CallLog.spentUsdAcrossRuns(ws.runsDir) * config.eurPerUsd
            out.println("Already spent across real runs: EUR %.2f of EUR %.2f".format(spent, config.capEur))
            if (!estimate.expectedFitsCap || spent + estimate.expectedEur > config.capEur - config.safetyMarginEur) {
                err.println("Refusing to start: expected cost plus already spent exceeds the usable cap (EUR %.2f minus EUR %.2f safety margin).".format(config.capEur, config.safetyMarginEur))
                return 1
            }
            if (!a.has("yes")) {
                out.print("This will make up to ${estimate.totalCalls} paid calls (expected EUR %.2f). Type 'yes' to continue: ".format(estimate.expectedEur))
                out.flush()
                if (stdin.readLine()?.trim() != "yes") {
                    out.println("Aborted; nothing was sent.")
                    return 1
                }
            }
        }

        val runner = Runner(
            config, snapshot, sampleSha256 = sha256Hex(ws.sampleFile), configSha256 = sha256Hex(ws.configFile),
            client = client, log = { out.println(it) },
        )
        val summary = runner.execute(RunSpec(runId, kind, items, reps, dir, mock), estimate)
        out.println("Run '$runId' ${summary.status}: ${summary.okCalls} ok, ${summary.errorCalls} failed attempts, " +
            "EUR %.2f spent in this run.".format(summary.spentEur))
        summary.stopReason?.let { err.println("Stopped early: $it") }
        out.println("Results: $dir (commit calls.jsonl and manifest.json, then run `analyze`)")
        return if (summary.status == "complete") 0 else 1
    }

    private fun analyze(a: Args): Int {
        val config = ws.loadConfig()
        val runDir = a.value("run")?.let { Path.of(it) } ?: ws.runsDir.resolve("full")
        check(Files.exists(runDir.resolve("manifest.json"))) { "No manifest.json in $runDir" }
        val manifest = PrettyJson.decodeFromString(Manifest.serializer(), Files.readString(runDir.resolve("manifest.json")))
        val explicitOut = a.has("out")
        if (manifest.mock && explicitOut && !a.has("allow-mock")) {
            throw UsageError("run '${manifest.runId}' is a MOCK run (synthetic); pass --allow-mock to write it anywhere explicitly")
        }
        val publishable = manifest.kind == "full" && !manifest.mock
        val results = a.value("out")?.let { Path.of(it) }
            ?: if (publishable) ws.docsData.resolve("results.json") else runDir.resolve("analysis.json")
        val itemsOut = a.value("items-out")?.let { Path.of(it) }
            ?: if (publishable) ws.docsData.resolve("items.json") else runDir.resolve("items.json")
        val reviewOut = a.value("review-out")?.let { Path.of(it) } ?: runDir.resolve("review-candidates.json")
        val disputed = a.value("disputed")?.let { Path.of(it) } ?: ws.disputedFile

        val output = AnalysisPipeline.analyze(runDir, ws.loadSample(), config, sha256Hex(ws.sampleFile), disputed)
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

fun main(args: Array<String>) {
    val cli = Cli(
        Workspace(Path.of("").toAbsolutePath()), System.out, System.err, System.getenv(),
        BufferedReader(InputStreamReader(System.`in`)),
    )
    exitProcess(cli.run(args))
}
