package io.github.leohelfferich.abstain.analysis

import java.nio.file.Files
import java.nio.file.Path

import io.github.leohelfferich.abstain.config.model.ExperimentConfig
import io.github.leohelfferich.abstain.execution.CallLog
import io.github.leohelfferich.abstain.execution.model.Manifest
import io.github.leohelfferich.abstain.utils.PrettyJson
import io.github.leohelfferich.abstain.utils.formatUs
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.prompt.Prompts
import io.github.leohelfferich.abstain.squad.model.Kind
import io.github.leohelfferich.abstain.squad.model.SampleFile
import io.github.leohelfferich.abstain.analysis.model.Scope
import io.github.leohelfferich.abstain.analysis.model.DisputedFile
import io.github.leohelfferich.abstain.analysis.model.RunInfo
import io.github.leohelfferich.abstain.analysis.model.SampleInfo
import io.github.leohelfferich.abstain.analysis.model.Results
import io.github.leohelfferich.abstain.analysis.model.ItemsFile
import io.github.leohelfferich.abstain.analysis.model.ReviewFile

/**
 * Checks that a run still matches the sample and the prompt templates, then writes metrics.
 *
 * A mismatch is refused. Scoring calls against a different sample or a rewritten prompt would
 * publish numbers the calls were not made for.
 */
object AnalysisPipeline {
    const val SCHEMA_VERSION = 1
    const val ATTRIBUTION = "Questions and passages: SQuAD 2.0 (Rajpurkar, Jia & Liang, 2018), CC BY-SA 4.0, " +
        "https://rajpurkar.github.io/SQuAD-explorer/"

    class Output(val results: Results, val items: ItemsFile, val review: ReviewFile)

    fun analyze(runDir: Path, sample: SampleFile, config: ExperimentConfig, sampleSha256: String, disputedFile: Path): Output {
        val manifest = PrettyJson.decodeFromString(Manifest.serializer(), Files.readString(runDir.resolve("manifest.json")))
        require(manifest.sampleSha256 == sampleSha256) {
            "Sample file does not match this run (manifest ${manifest.sampleSha256}, file $sampleSha256). Refusing to score calls against a different sample."
        }
        PromptVariant.entries.forEach { variant ->
            val recorded = manifest.prompts[variant.key]?.sha256
            val current = Prompts.templateSha256(variant)
            require(recorded == current) {
                "Prompt '${variant.key}' changed since the run (manifest $recorded, code $current). Refusing to publish metrics under a different prompt."
            }
        }
        val calls = CallLog.read(runDir)
        val runItemIds = calls.map { it.itemId }.toSet()
        val items = sample.items.filter { it.id in runItemIds }
        val disputed = DisputedFile.load(disputedFile)
        val unknown = disputed.items.filter { d -> sample.items.none { it.id == d.id } }
        require(unknown.isEmpty()) { "disputed.json lists ids that are not in the sample: ${unknown.map { it.id }}" }
        val disputedInRun = disputed.items.filter { d -> items.any { it.id == d.id } }
        val aliases = manifest.models.map { it.alias }
        val analyzer = Analyzer(items, calls, aliases, config.bootstrapResamples, config.bootstrapSeed)
        val excluded = disputedInRun.map { it.id }.toSet()
        val scopes = buildList {
            add(analyzer.scope("all", emptySet()))
            add(analyzer.scope("excludingDisputed", excluded))
        }
        val results = Results(
            schemaVersion = SCHEMA_VERSION,
            run = RunInfo(
                manifest.runId,
                manifest.kind,
                manifest.mock,
                manifest.status,
                manifest.startedAt,
                manifest.finishedAt,
                manifest.gitCommit,
                manifest.reps,
                manifest.temperature,
                manifest.maxTokens,
                manifest.models,
                manifest.prompts,
                PromptVariant.entries.associate { it.key to Prompts.template(it) },
                manifest.plannedCalls,
                manifest.okCalls,
                manifest.errorCalls,
                manifest.spentEurThisRun,
                manifest.capEur,
            ),
            sample = SampleInfo(
                sample.seed, sample.algorithm, items.count { it.kind == Kind.ANSWERABLE }, items.count { it.kind == Kind.UNANSWERABLE },
                sample.source, sampleSha256, ATTRIBUTION,
            ),
            disputed = disputedInRun,
            scopes = scopes,
        )
        val itemsFile = ItemsFile(manifest.runId, analyzer.itemViews(disputedInRun.associateBy { it.id }))
        val review = ReviewFile(
            manifest.runId,
            "Candidates only. Inspect each item (docs/items.html), then copy confirmed ids into data/disputed.json " +
                    "with a reason and re-run `analyze`; nothing is excluded automatically.",
            analyzer.reviewCandidates(),
        )
        return Output(results, itemsFile, review)
    }

    fun renderTable(results: Results): String = buildString {
        results.scopes.forEach { scope ->
            appendLine("Scope '${scope.name}': ${scope.unanswerableItems} unanswerable / ${scope.answerableItems} answerable items" +
                if (scope.excludedItems > 0) " (${scope.excludedItems} disputed excluded)" else "")
            appendLine("  %-18s %-8s %9s %11s %6s %6s %12s".formatUs("model", "prompt", "invented", "over-refuse", "EM", "F1", "consistency"))
            scope.cells.forEach { c ->
                appendLine("  %-18s %-8s %9s %11s %6s %6s %12s".formatUs(
                    c.model, c.prompt, pct(c.invented.value), pct(c.overRefusal.value), pct(c.exactMatch.value),
                    pct(c.f1.value), pct(c.consistency.decisionAgreement)))
            }
            appendLine("  strict vs neutral:")
            scope.tradeoffs.forEach { t ->
                appendLine("    %-18s invented %s, over-refusal %s, EM %s -> %s".formatUs(
                    t.model, pp(t.deltaInvented.value), pp(t.deltaOverRefusal.value), pp(t.deltaExactMatch.value), t.pattern))
            }
            appendLine()
        }
    }

    private fun pct(v: Double?) = if (v == null) "n/a" else "%.1f%%".formatUs(v * 100)
    private fun pp(v: Double?) = if (v == null) "n/a" else "%+.1f pp".formatUs(v * 100)
}
