package io.github.leohelfferich.abstain

import kotlinx.serialization.Serializable
import kotlin.math.ceil

const val PESSIMISTIC_OUTPUT_FACTOR = 3.0
const val PESSIMISTIC_INPUT_FACTOR = 1.2

/** Per-model token behaviour measured in an earlier (dry) run; replaces the built-in assumptions. */
data class ObservedUsage(val promptTokensPerChar: Double, val completionTokensPerCall: Double, val sampleCalls: Int)

@Serializable
data class ModelEstimate(
    val alias: String,
    val openRouterId: String,
    val calls: Int,
    val inputTokensPerCall: Double,
    val expectedOutputTokensPerCall: Double,
    val pessimisticOutputTokensPerCall: Double,
    val ceilingOutputTokensPerCall: Double,
    val promptUsdPerMillion: Double,
    val completionUsdPerMillion: Double,
    val expectedUsd: Double,
    val pessimisticUsd: Double,
    val ceilingUsd: Double,
)

@Serializable
data class Estimate(
    val label: String,
    val basis: String,
    val pricingRetrievedOn: String,
    val items: Int,
    val reps: Int,
    val promptVariants: Int,
    val models: List<ModelEstimate>,
    val totalCalls: Int,
    val expectedUsd: Double,
    val pessimisticUsd: Double,
    val ceilingUsd: Double,
    val eurPerUsd: Double,
    val expectedEur: Double,
    val pessimisticEur: Double,
    val ceilingEur: Double,
    val capEur: Double,
    val expectedFitsCap: Boolean,
    val pessimisticFitsCap: Boolean,
)

object Estimator {
    fun estimate(
        label: String,
        items: List<Item>,
        reps: Int,
        config: ExperimentConfig,
        snapshot: PricingSnapshot,
        observed: Map<String, ObservedUsage> = emptyMap(),
    ): Estimate {
        val variants = PromptVariant.entries
        val prompts = items.flatMap { item -> variants.map { Prompts.render(it, item.context, item.question) } }
        val avgChars = prompts.sumOf { it.length }.toDouble() / prompts.size
        val callsPerModel = items.size * variants.size * reps

        val models = config.models.map { m ->
            val price = snapshot.model(m.openRouterId)
            val obs = observed[m.alias]
            val input = if (obs != null) avgChars * obs.promptTokensPerChar else avgChars / config.charsPerToken
            val expectedOut = obs?.completionTokensPerCall
                ?: (config.assumedAnswerTokens + m.assumedReasoningTokens).toDouble()
            val pessimisticOut = expectedOut * PESSIMISTIC_OUTPUT_FACTOR
            val ceilingOut = config.maxTokens.toDouble()
            fun usd(inTokens: Double, outTokens: Double) =
                callsPerModel * (inTokens * price.promptPrice + outTokens * price.completionPrice)
            ModelEstimate(
                alias = m.alias,
                openRouterId = m.openRouterId,
                calls = callsPerModel,
                inputTokensPerCall = input,
                expectedOutputTokensPerCall = expectedOut,
                pessimisticOutputTokensPerCall = pessimisticOut,
                ceilingOutputTokensPerCall = ceilingOut,
                promptUsdPerMillion = price.promptPrice * 1e6,
                completionUsdPerMillion = price.completionPrice * 1e6,
                expectedUsd = usd(input, expectedOut),
                pessimisticUsd = usd(input * PESSIMISTIC_INPUT_FACTOR, pessimisticOut),
                ceilingUsd = usd(avgChars / 2.0, ceilingOut),
            )
        }
        val expected = models.sumOf { it.expectedUsd }
        val pessimistic = models.sumOf { it.pessimisticUsd }
        val ceiling = models.sumOf { it.ceilingUsd }
        val fx = config.eurPerUsd
        return Estimate(
            label = label,
            basis = if (observed.isEmpty()) "assumed token counts (config)" else "token counts observed in an earlier run",
            pricingRetrievedOn = snapshot.retrievedOn,
            items = items.size,
            reps = reps,
            promptVariants = variants.size,
            models = models,
            totalCalls = callsPerModel * models.size,
            expectedUsd = expected,
            pessimisticUsd = pessimistic,
            ceilingUsd = ceiling,
            eurPerUsd = fx,
            expectedEur = expected * fx,
            pessimisticEur = pessimistic * fx,
            ceilingEur = ceiling * fx,
            capEur = config.capEur,
            expectedFitsCap = expected * fx <= config.capEur - config.safetyMarginEur,
            pessimisticFitsCap = pessimistic * fx <= config.capEur - config.safetyMarginEur,
        )
    }

    fun render(e: Estimate): String = buildString {
        appendLine("Cost estimate: ${e.label}")
        appendLine("  ${e.items} items x ${e.models.size} models x ${e.promptVariants} prompts x ${e.reps} rep(s) = ${e.totalCalls} calls")
        appendLine("  Basis: ${e.basis}; OpenRouter public pricing of ${e.pricingRetrievedOn}")
        appendLine("  Rate: 1 USD = ${"%.2f".format(e.eurPerUsd)} EUR (config eurPerUsd; 1.00 is deliberately conservative)")
        appendLine()
        appendLine("  %-18s %6s %8s %8s %9s %9s %9s".format("model", "calls", "in tok", "out tok", "expected", "pessim.", "ceiling"))
        e.models.forEach { m ->
            appendLine(
                "  %-18s %6d %8.0f %8.0f %9s %9s %9s".format(
                    m.alias, m.calls, m.inputTokensPerCall, m.expectedOutputTokensPerCall,
                    eur(m.expectedUsd * e.eurPerUsd), eur(m.pessimisticUsd * e.eurPerUsd), eur(m.ceilingUsd * e.eurPerUsd),
                ),
            )
        }
        appendLine("  %-18s %6d %8s %8s %9s %9s %9s".format("TOTAL", e.totalCalls, "", "", eur(e.expectedEur), eur(e.pessimisticEur), eur(e.ceilingEur)))
        appendLine()
        appendLine("  expected    = assumed/observed tokens per call (reasoning models: answer + reasoning allowance)")
        appendLine("  pessimistic = ${PESSIMISTIC_OUTPUT_FACTOR}x output tokens, ${PESSIMISTIC_INPUT_FACTOR}x input tokens")
        appendLine("  ceiling     = every call uses all ${e.models.first().ceilingOutputTokensPerCall.toInt()} max output tokens (only a bound; never reached in practice)")
        appendLine("  Hard cap: ${eur(e.capEur)} total across all real runs; the runner stops before it is exceeded.")
        append("  Expected fits under cap: ${if (e.expectedFitsCap) "yes" else "NO"}; pessimistic fits: ${if (e.pessimisticFitsCap) "yes" else "NO"}")
    }

    private fun eur(v: Double) = "EUR %.2f".format(v)

    fun observedFrom(calls: List<CallRecord>): Map<String, ObservedUsage> =
        calls.filter { it.status == "ok" && it.promptTokens != null && it.completionTokens != null && it.promptChars > 0 }
            .groupBy { it.model }
            .mapValues { (_, rows) ->
                ObservedUsage(
                    promptTokensPerChar = rows.sumOf { it.promptTokens!! }.toDouble() / rows.sumOf { it.promptChars },
                    completionTokensPerCall = rows.sumOf { it.completionTokens!! }.toDouble() / rows.size,
                    sampleCalls = rows.size,
                )
            }
}

/**
 * Enforces the hard cap while calls are in flight. Before every call the worst case for that call is
 * reserved; the call is refused if spent + reserved + worst case would exceed the usable budget
 * (cap minus safety margin). Thread-safe.
 */
class CostGuard(capEur: Double, safetyMarginEur: Double, private val eurPerUsd: Double, alreadySpentEur: Double) {
    private val budgetUsd = (capEur - safetyMarginEur) / eurPerUsd
    private var spentUsd = alreadySpentEur / eurPerUsd
    private var reservedUsd = 0.0

    init {
        require(capEur <= HARD_CAP_EUR) { "cap above the hard cap of EUR $HARD_CAP_EUR" }
    }

    @Synchronized
    fun tryReserve(worstCaseUsd: Double): Boolean {
        if (spentUsd + reservedUsd + worstCaseUsd > budgetUsd) return false
        reservedUsd += worstCaseUsd
        return true
    }

    @Synchronized
    fun settle(reservedWorstCaseUsd: Double, actualUsd: Double) {
        reservedUsd -= reservedWorstCaseUsd
        spentUsd += actualUsd
    }

    @Synchronized
    fun spentEur(): Double = spentUsd * eurPerUsd

    @Synchronized
    fun remainingEur(): Double = (budgetUsd - spentUsd - reservedUsd) * eurPerUsd

    companion object {
        fun worstCaseUsd(prompt: String, maxTokens: Int, price: SnapshotModel): Double =
            ceil(prompt.length / 2.0) * price.promptPrice + maxTokens * price.completionPrice
    }
}
