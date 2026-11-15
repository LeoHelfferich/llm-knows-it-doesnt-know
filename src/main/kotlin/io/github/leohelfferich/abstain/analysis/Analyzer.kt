package io.github.leohelfferich.abstain.analysis

import java.util.Random

import io.github.leohelfferich.abstain.execution.CallLog
import io.github.leohelfferich.abstain.execution.model.CallRecord
import io.github.leohelfferich.abstain.utils.formatUs
import io.github.leohelfferich.abstain.parsing.model.Outcome
import io.github.leohelfferich.abstain.parsing.model.ParsedResponse
import io.github.leohelfferich.abstain.parsing.ResponseParser
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.prompt.Prompts
import io.github.leohelfferich.abstain.scoring.SquadMetrics
import io.github.leohelfferich.abstain.squad.model.Item
import io.github.leohelfferich.abstain.squad.model.Kind
import io.github.leohelfferich.abstain.analysis.model.Interval
import io.github.leohelfferich.abstain.analysis.model.Consistency
import io.github.leohelfferich.abstain.analysis.model.CellMetrics
import io.github.leohelfferich.abstain.analysis.model.Tradeoff
import io.github.leohelfferich.abstain.analysis.model.Scope
import io.github.leohelfferich.abstain.analysis.model.DisputedItem
import io.github.leohelfferich.abstain.analysis.model.ResponseView
import io.github.leohelfferich.abstain.analysis.model.ItemView
import io.github.leohelfferich.abstain.analysis.model.ReviewCandidate

/** One successful call after the reply has been classified. */
private data class Parsed(val rep: Int, val parsed: ParsedResponse, val record: CallRecord)

/**
 * Computes the published metrics from raw calls.
 *
 * Rates are means over items, so a chatty item with more repetitions does not outweigh a quiet one.
 * Intervals are percentile bootstrap intervals over items, with a fixed seed.
 *
 * ```
 * calls
 *   |
 *   +-- CallLog.okByKey          latest success per (item, model, prompt, rep)
 *   +-- ResponseParser.parse
 *   |
 *   v
 * grouped by (model, prompt, item)
 *   |
 *   +-- cell()       one model x one prompt
 *   +-- tradeoff()   strict minus neutral, paired on the same item
 *   +-- scope()      every model x both prompts, after dropping disputed ids
 *   +-- itemViews()  the per-reply rows the item pages render
 *   +-- reviewCandidates()  items a person should look at before trusting the gold label
 * ```
 */
class Analyzer(
    private val items: List<Item>,
    calls: List<CallRecord>,
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

    /**
     * Point estimate plus a 95% percentile bootstrap over items.
     *
     * ```
     * one number per item
     *   |
     *   +-- empty ------------------------> Interval(null, null, null)
     *   +-- one item, or resamples <= 0 --> point only; lo and hi stay null
     *   |
     *   v
     * draw n items with replacement, `bootstrapResamples` times, seed fixed
     *   |
     *   v
     * sort the means
     *   lo = index floor(0.025 * resamples)
     *   hi = index floor(0.975 * resamples), clamped to the last mean
     * ```
     */
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

    /**
     * One model and one prompt, after dropping [excluded] item ids.
     * An item with no successful calls is left out of the mean. It is not counted as a zero.
     *
     * ```
     * unanswerable items                         answerable items
     *   |                                          |
     *   v                                          v
     * fraction of reps that answered             fraction of reps that abstained
     * ("invented")                               ("over-refusal")
     *   |                                          |
     *   +---- mean across items, then interval() -+
     *
     * answerable reps that did answer --> exact match and token F1 against the gold spans
     * reps of the same item           --> pairwise agreement of the decision
     *                                      (abstain vs the outcome name)
     *
     * abstention balanced accuracy = ((1 - invented) + (1 - over-refusal)) / 2
     * ```
     */
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

    /**
     * Strict prompt minus neutral prompt, paired on the same item.
     * A bound counts only when the whole interval sits on one side of zero.
     *
     * ```
     * per item: mean(strict reps) - mean(neutral reps)
     *   |
     *   +-- either prompt has no calls ---------> "insufficient-data"
     *   +-- invented hi < 0 AND refusal lo > 0 -> "trade-off"
     *   +-- invented hi < 0 -------------------> "invention-down-without-clear-over-refusal"
     *   +-- refusal lo > 0 --------------------> "over-refusal-up-without-clear-invention-drop"
     *   +-- otherwise --------------------------> "no-clear-effect"
     *
     * inventedAvoidedPerExtraRefusal = -deltaInvented / deltaOverRefusal
     *   only when invention fell and over-refusal rose
     * ```
     */
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

    /**
     * Every model crossed with both prompts, on the items that remain after [excluded].
     * Ids in [excluded] that are not in this sample are ignored, so a disputed file
     * from a larger sample does not change the counts.
     */
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

    /**
     * One view per sample item. A reply whose text is exactly the sentinel, or exactly
     * `ANSWER: ` plus the parsed answer, stores no raw text; anything else keeps [ResponseView.raw]
     * so the page can show the deviation.
     */
    fun itemViews(disputed: Map<String, DisputedItem>): List<ItemView> = items.map { item ->
        val responses = modelAliases.flatMap { m ->
            PromptVariant.entries.flatMap { v ->
                rows(m, v, item).map { r ->
                    val correct = if (item.kind == Kind.ANSWERABLE) correctness(item, r.parsed).first == 1.0 else null
                    val raw = r.record.content
                    val standard = raw != null && (raw.trim() == Prompts.SENTINEL || raw.trim() == "ANSWER: ${r.parsed.answer}")
                    ResponseView(
                        m, v.key, r.rep, r.parsed.outcome.name, r.parsed.answer, if (standard) null else raw,
                        r.parsed.formatViolation, r.parsed.ambiguous, correct
                    )
                }
            }
        }
        ItemView(
            item.id, item.kind, item.pairIndex, item.article, item.context, item.question, item.answers,
            item.plausibleAnswers, item.id in disputed, disputed[item.id]?.reason, responses
        )
    }

    /**
     * Items whose gold label looks doubtful, or whose reply the parser could not decide.
     *
     * ```
     * unanswerable: >= 3 models answered under strict, in at least 2/3 of reps
     * answerable:   >= 3 models abstained under strict, in at least 2/3 of reps
     *               OR mean neutral-prompt F1 across >= 3 models is below 0.2
     * either kind:  any reply mixed an answer with refusal wording
     * ```
     *
     * Nothing here is excluded. A person copies confirmed ids into `data/disputed.json`.
     */
    fun reviewCandidates(): List<ReviewCandidate> = items.mapNotNull { item ->
        val reasons = mutableListOf<String>()
        if (item.kind == Kind.UNANSWERABLE) {
            val n = modelAliases.count { majority(item, it, PromptVariant.STRICT) { p -> p.outcome == Outcome.ANSWER } == true }
            if (n >= 3) reasons += "$n of ${modelAliases.size} models gave an answer under the strict prompt (in at least 2/3 of reps); check whether the context actually contains one"
        } else {
            val n = modelAliases.count { majority(item, it, PromptVariant.STRICT) { p -> p.outcome.isAbstention } == true }
            if (n >= 3) reasons += "$n of ${modelAliases.size} models refused under the strict prompt (in at least 2/3 of reps); check whether the gold answer is findable"
            val f1s = modelAliases.mapNotNull { m ->
                val r = rows(m, PromptVariant.NEUTRAL, item)
                if (r.isEmpty()) null else r.map { correctness(item, it.parsed).second }.average()
            }
            if (f1s.size >= 3 && f1s.average() < 0.2) reasons += "mean neutral-prompt F1 across models is %.2f; gold answer may be wrong or ambiguous".formatUs(f1s.average())
        }
        val ambiguous = parsed.filterKeys { it.third == item.id }.values.flatten().count { it.parsed.ambiguous }
        if (ambiguous > 0) reasons += "$ambiguous reply(ies) mix an answer with refusal wording; the abstention rule could not decide"
        if (reasons.isEmpty()) null else ReviewCandidate(item.id, item.kind, item.question, reasons)
    }

    /** True when at least two thirds of the repetitions match. Null when this model has no calls. */
    private fun majority(item: Item, model: String, variant: PromptVariant, predicate: (ParsedResponse) -> Boolean): Boolean? {
        val replies = rows(model, variant, item)
        if (replies.isEmpty()) return null
        return replies.count { predicate(it.parsed) } * 3 >= replies.size * 2
    }
}
