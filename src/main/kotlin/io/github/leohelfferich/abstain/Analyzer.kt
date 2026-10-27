package io.github.leohelfferich.abstain

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

@Serializable
data class Interval(val value: Double?, val lo: Double?, val hi: Double?)

@Serializable
data class Consistency(
    val decisionAgreement: Double?,
    val decisionAgreementUnanswerable: Double?,
    val decisionAgreementAnswerable: Double?,
    val fullyConsistentItems: Double?,
    val answerAgreement: Double?,
)

@Serializable
data class CellMetrics(
    val model: String,
    val prompt: String,
    val unanswerableItems: Int,
    val answerableItems: Int,
    val unanswerableCalls: Int,
    val answerableCalls: Int,
    val invented: Interval,
    val overRefusal: Interval,
    val exactMatch: Interval,
    val f1: Interval,
    val exactMatchWhenAnswered: Double?,
    val f1WhenAnswered: Double?,
    val abstentionBalancedAccuracy: Double?,
    val inventedMatchesPlausible: Double?,
    val sentinelAbstentions: Int,
    val phraseAbstentions: Int,
    val emptyCalls: Int,
    val formatViolations: Int,
    val ambiguousCalls: Int,
    val consistency: Consistency,
    val costUsd: Double,
    val meanCompletionTokens: Double?,
)

@Serializable
data class Tradeoff(
    val model: String,
    val deltaInvented: Interval,
    val deltaOverRefusal: Interval,
    val deltaExactMatch: Interval,
    val deltaF1: Interval,
    val inventedAvoidedPerExtraRefusal: Double?,
    val pattern: String,
)

@Serializable
data class Scope(
    val name: String,
    val excludedItems: Int,
    val unanswerableItems: Int,
    val answerableItems: Int,
    val cells: List<CellMetrics>,
    val tradeoffs: List<Tradeoff>,
)

@Serializable
data class DisputedItem(val id: String, val reason: String = "", val reviewer: String? = null)

@Serializable
data class DisputedFile(val items: List<DisputedItem> = emptyList()) {
    companion object {
        fun load(path: Path): DisputedFile =
            if (Files.exists(path)) PrettyJson.decodeFromString(serializer(), Files.readString(path)) else DisputedFile()
    }
}

@Serializable
data class RunInfo(
    val runId: String,
    val kind: String,
    val mock: Boolean,
    val status: String,
    val startedAt: String,
    val finishedAt: String?,
    val gitCommit: String?,
    val reps: Int,
    val temperature: Double,
    val maxTokens: Int,
    val models: List<ManifestModel>,
    val prompts: Map<String, PromptInfo>,
    val promptTemplates: Map<String, String>,
    val plannedCalls: Int,
    val okCalls: Int,
    val errorCalls: Int,
    val spentEur: Double,
    val capEur: Double,
)

@Serializable
data class SampleInfo(
    val seed: Long,
    val algorithm: String,
    val answerable: Int,
    val unanswerable: Int,
    val source: SampleSource,
    val sampleSha256: String,
    val attribution: String,
)

@Serializable
data class Results(
    val schemaVersion: Int,
    val run: RunInfo,
    val sample: SampleInfo,
    val disputed: List<DisputedItem>,
    val scopes: List<Scope>,
)

@Serializable
data class ResponseView(
    val model: String,
    val prompt: String,
    val rep: Int,
    val outcome: String,
    val answer: String?,
    /** Raw reply, only included when it differs from the standard `ANSWER: <x>` / sentinel form. */
    val raw: String?,
    val formatViolation: Boolean,
    val ambiguous: Boolean,
    val correct: Boolean?,
)

@Serializable
data class ItemView(
    val id: String,
    val kind: Kind,
    val pairIndex: Int,
    val article: String,
    val context: String,
    val question: String,
    val answers: List<String>,
    val plausibleAnswers: List<String>,
    val disputed: Boolean,
    val disputedReason: String?,
    val responses: List<ResponseView>,
)

@Serializable
data class ItemsFile(val runId: String, val items: List<ItemView>)

@Serializable
data class ReviewCandidate(val id: String, val kind: Kind, val question: String, val reasons: List<String>)

@Serializable
data class ReviewFile(val runId: String, val howToUse: String, val candidates: List<ReviewCandidate>)

private data class Parsed(val rep: Int, val parsed: ParsedResponse, val record: CallRecord)

class Analyzer(
    private val items: List<Item>,
    private val calls: List<CallRecord>,
    private val modelAliases: List<String>,
    private val bootstrapResamples: Int,
    private val bootstrapSeed: Long,
) {
    private val itemsById = items.associateBy { it.id }

    private val parsed: Map<Triple<String, PromptVariant, String>, List<Parsed>> =
        CallLog.okByKey(calls).values
            .filter { it.itemId in itemsById }
            .groupBy { Triple(it.model, it.prompt, it.itemId) }
            .mapValues { (_, rows) -> rows.sortedBy { it.rep }.map { Parsed(it.rep, ResponseParser.parse(it.content), it) } }

    private fun rows(model: String, prompt: PromptVariant, item: Item) = parsed[Triple(model, prompt, item.id)].orEmpty()

    private fun correctness(item: Item, p: ParsedResponse): Pair<Double, Double> =
        if (p.outcome == Outcome.ANSWER && p.answer != null) {
            SquadMetrics.exactMatch(p.answer, item.answers) to SquadMetrics.maxF1(p.answer, item.answers)
        } else 0.0 to 0.0

    private fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.sum() / values.size

    private fun interval(values: List<Double>): Interval {
        if (values.isEmpty()) return Interval(null, null, null)
        val point = values.average()
        if (bootstrapResamples <= 0 || values.size < 2) return Interval(point, null, null)
        val rnd = Random(bootstrapSeed)
        val n = values.size
        val means = DoubleArray(bootstrapResamples) {
            var s = 0.0
            repeat(n) { s += values[rnd.nextInt(n)] }
            s / n
        }
        means.sort()
        val lo = means[(0.025 * bootstrapResamples).toInt()]
        val hi = means[minOf(bootstrapResamples - 1, (0.975 * bootstrapResamples).toInt())]
        return Interval(point, lo, hi)
    }

    private fun itemRate(rows: List<Parsed>, f: (ParsedResponse) -> Double): Double? =
        if (rows.isEmpty()) null else rows.map { f(it.parsed) }.average()

    private fun category(o: Outcome) = if (o.isAbstention) "abstain" else o.name

    private fun pairwiseAgreement(values: List<String>): Double? {
        if (values.size < 2) return null
        var agree = 0
        var total = 0
        for (i in values.indices) for (j in i + 1 until values.size) {
            total++
            if (values[i] == values[j]) agree++
        }
        return agree.toDouble() / total
    }

    fun cell(model: String, prompt: PromptVariant, excluded: Set<String>): CellMetrics {
        val unans = items.filter { it.kind == Kind.UNANSWERABLE && it.id !in excluded }
        val ans = items.filter { it.kind == Kind.ANSWERABLE && it.id !in excluded }
        val unansRows = unans.associateWith { rows(model, prompt, it) }.filterValues { it.isNotEmpty() }
        val ansRows = ans.associateWith { rows(model, prompt, it) }.filterValues { it.isNotEmpty() }
        val allRows = (unansRows.values + ansRows.values).flatten()

        val inventedPerItem = unansRows.values.map { r -> itemRate(r) { if (it.outcome == Outcome.ANSWER) 1.0 else 0.0 }!! }
        val refusalPerItem = ansRows.values.map { r -> itemRate(r) { if (it.outcome.isAbstention) 1.0 else 0.0 }!! }
        val emPerItem = ansRows.map { (item, r) -> r.map { correctness(item, it.parsed).first }.average() }
        val f1PerItem = ansRows.map { (item, r) -> r.map { correctness(item, it.parsed).second }.average() }

        val answeredCalls = ansRows.flatMap { (item, r) -> r.filter { it.parsed.outcome == Outcome.ANSWER }.map { item to it } }
        val emAnswered = mean(answeredCalls.map { (item, p) -> correctness(item, p.parsed).first })
        val f1Answered = mean(answeredCalls.map { (item, p) -> correctness(item, p.parsed).second })

        val invented = interval(inventedPerItem)
        val refusal = interval(refusalPerItem)
        val balanced = if (invented.value != null && refusal.value != null) ((1 - invented.value) + (1 - refusal.value)) / 2 else null

        val plausibleHits = unansRows.flatMap { (item, r) ->
            if (item.plausibleAnswers.isEmpty()) emptyList()
            else r.filter { it.parsed.outcome == Outcome.ANSWER }.map {
                if (SquadMetrics.maxF1(it.parsed.answer!!, item.plausibleAnswers) >= 0.5) 1.0 else 0.0
            }
        }

        val unansAgreement = unansRows.values.mapNotNull { r -> pairwiseAgreement(r.map { category(it.parsed.outcome) }) }
        val ansAgreement = ansRows.values.mapNotNull { r -> pairwiseAgreement(r.map { category(it.parsed.outcome) }) }
        val fullyConsistent = (unansRows.values + ansRows.values).filter { it.size >= 2 }
            .map { r -> if (r.map { category(it.parsed.outcome) }.distinct().size == 1) 1.0 else 0.0 }
        var answerPairs = 0
        var answerPairsEqual = 0
        (unansRows.values + ansRows.values).forEach { r ->
            val answers = r.filter { it.parsed.outcome == Outcome.ANSWER }.map { SquadMetrics.normalize(it.parsed.answer!!) }
            for (i in answers.indices) for (j in i + 1 until answers.size) {
                answerPairs++
                if (answers[i] == answers[j]) answerPairsEqual++
            }
        }

        return CellMetrics(
            model = model, prompt = prompt.key,
            unanswerableItems = unansRows.size, answerableItems = ansRows.size,
            unanswerableCalls = unansRows.values.sumOf { it.size }, answerableCalls = ansRows.values.sumOf { it.size },
            invented = invented, overRefusal = refusal,
            exactMatch = interval(emPerItem), f1 = interval(f1PerItem),
            exactMatchWhenAnswered = emAnswered, f1WhenAnswered = f1Answered,
            abstentionBalancedAccuracy = balanced,
            inventedMatchesPlausible = mean(plausibleHits),
            sentinelAbstentions = allRows.count { it.parsed.outcome == Outcome.ABSTAIN_SENTINEL },
            phraseAbstentions = allRows.count { it.parsed.outcome == Outcome.ABSTAIN_PHRASE },
            emptyCalls = allRows.count { it.parsed.outcome == Outcome.EMPTY },
            formatViolations = allRows.count { it.parsed.formatViolation },
            ambiguousCalls = allRows.count { it.parsed.ambiguous },
            consistency = Consistency(
                decisionAgreement = mean(unansAgreement + ansAgreement),
                decisionAgreementUnanswerable = mean(unansAgreement),
                decisionAgreementAnswerable = mean(ansAgreement),
                fullyConsistentItems = mean(fullyConsistent),
                answerAgreement = if (answerPairs == 0) null else answerPairsEqual.toDouble() / answerPairs,
            ),
            costUsd = allRows.sumOf { it.record.costUsd ?: 0.0 },
            meanCompletionTokens = mean(allRows.mapNotNull { it.record.completionTokens?.toDouble() }),
        )
    }

    private fun pairedDelta(model: String, kindFilter: Kind, excluded: Set<String>, f: (Item, List<Parsed>) -> Double): Interval {
        val deltas = items.filter { it.kind == kindFilter && it.id !in excluded }.mapNotNull { item ->
            val s = rows(model, PromptVariant.STRICT, item)
            val n = rows(model, PromptVariant.NEUTRAL, item)
            if (s.isEmpty() || n.isEmpty()) null else f(item, s) - f(item, n)
        }
        return interval(deltas)
    }

    fun tradeoff(model: String, excluded: Set<String>): Tradeoff {
        val dInv = pairedDelta(model, Kind.UNANSWERABLE, excluded) { _, r -> r.map { if (it.parsed.outcome == Outcome.ANSWER) 1.0 else 0.0 }.average() }
        val dRef = pairedDelta(model, Kind.ANSWERABLE, excluded) { _, r -> r.map { if (it.parsed.outcome.isAbstention) 1.0 else 0.0 }.average() }
        val dEm = pairedDelta(model, Kind.ANSWERABLE, excluded) { item, r -> r.map { correctness(item, it.parsed).first }.average() }
        val dF1 = pairedDelta(model, Kind.ANSWERABLE, excluded) { item, r -> r.map { correctness(item, it.parsed).second }.average() }
        val invDown = dInv.hi?.let { it < 0 } ?: false
        val refUp = dRef.lo?.let { it > 0 } ?: false
        val pattern = when {
            dInv.value == null || dRef.value == null -> "insufficient-data"
            invDown && refUp -> "trade-off"
            invDown -> "invention-down-without-clear-over-refusal"
            refUp -> "over-refusal-up-without-clear-invention-drop"
            else -> "no-clear-effect"
        }
        val ratio = if (dInv.value != null && dRef.value != null && dInv.value < 0 && dRef.value > 0) -dInv.value / dRef.value else null
        return Tradeoff(model, dInv, dRef, dEm, dF1, ratio, pattern)
    }

    fun scope(name: String, excluded: Set<String>): Scope {
        val present = excluded.filter { it in itemsById }.toSet()
        val cells = modelAliases.flatMap { m -> PromptVariant.entries.map { cell(m, it, present) } }
        return Scope(
            name = name,
            excludedItems = present.size,
            unanswerableItems = items.count { it.kind == Kind.UNANSWERABLE && it.id !in present },
            answerableItems = items.count { it.kind == Kind.ANSWERABLE && it.id !in present },
            cells = cells,
            tradeoffs = modelAliases.map { tradeoff(it, present) },
        )
    }

    fun itemViews(disputed: Map<String, DisputedItem>): List<ItemView> = items.map { item ->
        val responses = modelAliases.flatMap { m ->
            PromptVariant.entries.flatMap { v ->
                rows(m, v, item).map { r ->
                    val correct = if (item.kind == Kind.ANSWERABLE) correctness(item, r.parsed).first == 1.0 else null
                    val raw = r.record.content
                    val standard = raw != null && (raw.trim() == Prompts.SENTINEL || raw.trim() == "ANSWER: ${r.parsed.answer}")
                    ResponseView(m, v.key, r.rep, r.parsed.outcome.name, r.parsed.answer, if (standard) null else raw,
                        r.parsed.formatViolation, r.parsed.ambiguous, correct)
                }
            }
        }
        ItemView(item.id, item.kind, item.pairIndex, item.article, item.context, item.question, item.answers,
            item.plausibleAnswers, item.id in disputed, disputed[item.id]?.reason, responses)
    }

    fun reviewCandidates(): List<ReviewCandidate> = items.mapNotNull { item ->
        val reasons = mutableListOf<String>()
        fun majority(model: String, v: PromptVariant, predicate: (ParsedResponse) -> Boolean): Boolean? {
            val r = rows(model, v, item)
            return if (r.isEmpty()) null else r.count { predicate(it.parsed) } * 3 >= r.size * 2
        }
        if (item.kind == Kind.UNANSWERABLE) {
            val n = modelAliases.count { majority(it, PromptVariant.STRICT) { p -> p.outcome == Outcome.ANSWER } == true }
            if (n >= 3) reasons += "$n of ${modelAliases.size} models gave an answer under the strict prompt (in at least 2/3 of reps); check whether the context actually contains one"
        } else {
            val n = modelAliases.count { majority(it, PromptVariant.STRICT) { p -> p.outcome.isAbstention } == true }
            if (n >= 3) reasons += "$n of ${modelAliases.size} models refused under the strict prompt (in at least 2/3 of reps); check whether the gold answer is findable"
            val f1s = modelAliases.mapNotNull { m ->
                val r = rows(m, PromptVariant.NEUTRAL, item)
                if (r.isEmpty()) null else r.map { correctness(item, it.parsed).second }.average()
            }
            if (f1s.size >= 3 && f1s.average() < 0.2) reasons += "mean neutral-prompt F1 across models is %.2f; gold answer may be wrong or ambiguous".format(f1s.average())
        }
        val ambiguous = parsed.filterKeys { it.third == item.id }.values.flatten().count { it.parsed.ambiguous }
        if (ambiguous > 0) reasons += "$ambiguous reply(ies) mix an answer with refusal wording; the abstention rule could not decide"
        if (reasons.isEmpty()) null else ReviewCandidate(item.id, item.kind, item.question, reasons)
    }
}

object AnalysisPipeline {
    const val SCHEMA_VERSION = 1
    const val ATTRIBUTION = "Questions and passages: SQuAD 2.0 (Rajpurkar, Jia & Liang, 2018), CC BY-SA 4.0, " +
        "https://rajpurkar.github.io/SQuAD-explorer/"

    class Output(val results: Results, val items: ItemsFile, val review: ReviewFile)

    fun analyze(runDir: Path, sample: SampleFile, config: ExperimentConfig, sampleSha256: String, disputedFile: Path): Output {
        val manifest = PrettyJson.decodeFromString(Manifest.serializer(), Files.readString(runDir.resolve("manifest.json")))
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
                manifest.runId, manifest.kind, manifest.mock, manifest.status, manifest.startedAt, manifest.finishedAt,
                manifest.gitCommit, manifest.reps, manifest.temperature, manifest.maxTokens, manifest.models, manifest.prompts,
                PromptVariant.entries.associate { it.key to Prompts.template(it) }, manifest.plannedCalls, manifest.okCalls, manifest.errorCalls, manifest.spentEurThisRun, manifest.capEur,
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
            appendLine("  %-18s %-8s %9s %11s %6s %6s %12s".format("model", "prompt", "invented", "over-refuse", "EM", "F1", "consistency"))
            scope.cells.forEach { c ->
                appendLine("  %-18s %-8s %9s %11s %6s %6s %12s".format(
                    c.model, c.prompt, pct(c.invented.value), pct(c.overRefusal.value), pct(c.exactMatch.value),
                    pct(c.f1.value), pct(c.consistency.decisionAgreement)))
            }
            appendLine("  strict vs neutral:")
            scope.tradeoffs.forEach { t ->
                appendLine("    %-18s invented %s, over-refusal %s, EM %s -> %s".format(
                    t.model, pp(t.deltaInvented.value), pp(t.deltaOverRefusal.value), pp(t.deltaExactMatch.value), t.pattern))
            }
            appendLine()
        }
    }

    private fun pct(v: Double?) = if (v == null) "n/a" else "%.1f%%".format(v * 100)
    private fun pp(v: Double?) = if (v == null) "n/a" else "%+.1f pp".format(v * 100)
}
