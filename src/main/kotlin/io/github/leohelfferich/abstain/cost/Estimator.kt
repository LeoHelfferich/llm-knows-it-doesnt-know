package io.github.leohelfferich.abstain.cost

import io.github.leohelfferich.abstain.config.model.ExperimentConfig
import io.github.leohelfferich.abstain.execution.model.CallRecord
import io.github.leohelfferich.abstain.execution.callKey
import io.github.leohelfferich.abstain.utils.formatUs
import io.github.leohelfferich.abstain.pricing.model.PricingSnapshot
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.prompt.Prompts
import io.github.leohelfferich.abstain.squad.model.Item
import io.github.leohelfferich.abstain.cost.model.Estimate
import io.github.leohelfferich.abstain.cost.model.ModelEstimate
import io.github.leohelfferich.abstain.cost.model.ObservedUsage

/**
 * Turns the sample, the prompt templates, and the pricing snapshot into a cost estimate.
 *
 * Expected cost uses assumed or previously observed tokens. Pessimistic cost inflates input and
 * output. Ceiling cost pretends every call uses the full output budget. The ceiling is a bound,
 * not a prediction.
 */
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
                expectedUsd = usd(callsPerModel, price.promptPrice, price.completionPrice, input, expectedOut),
                pessimisticUsd = usd(callsPerModel, price.promptPrice, price.completionPrice, input * PESSIMISTIC_INPUT_FACTOR, pessimisticOut),
                ceilingUsd = usd(callsPerModel, price.promptPrice, price.completionPrice, avgChars / 2.0, ceilingOut),
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

    private fun usd(calls: Int, promptPrice: Double, completionPrice: Double, inTokens: Double, outTokens: Double) =
        calls * (inTokens * promptPrice + outTokens * completionPrice)

    fun render(e: Estimate): String = buildString {
        appendLine("Cost estimate: ${e.label}")
        appendLine("  ${e.items} items x ${e.models.size} models x ${e.promptVariants} prompts x ${e.reps} rep(s) = ${e.totalCalls} calls")
        appendLine("  Basis: ${e.basis}; OpenRouter public pricing of ${e.pricingRetrievedOn}")
        appendLine("  Rate: 1 USD = ${"%.2f".formatUs(e.eurPerUsd)} EUR (config eurPerUsd; 1.00 is deliberately conservative)")
        appendLine()
        appendLine("  %-18s %6s %8s %8s %9s %9s %9s".formatUs("model", "calls", "in tok", "out tok", "expected", "pessim.", "ceiling"))
        e.models.forEach { m ->
            appendLine(
                "  %-18s %6d %8.0f %8.0f %9s %9s %9s".formatUs(
                    m.alias, m.calls, m.inputTokensPerCall, m.expectedOutputTokensPerCall,
                    eur(m.expectedUsd * e.eurPerUsd), eur(m.pessimisticUsd * e.eurPerUsd), eur(m.ceilingUsd * e.eurPerUsd),
                ),
            )
        }
        appendLine("  %-18s %6d %8s %8s %9s %9s %9s".formatUs("TOTAL", e.totalCalls, "", "", eur(e.expectedEur), eur(e.pessimisticEur), eur(e.ceilingEur)))
        appendLine()
        appendLine("  expected    = assumed/observed tokens per call (reasoning models: answer + reasoning allowance)")
        appendLine("  pessimistic = ${PESSIMISTIC_OUTPUT_FACTOR}x output tokens, ${PESSIMISTIC_INPUT_FACTOR}x input tokens")
        appendLine("  ceiling     = every call uses all ${e.models.first().ceilingOutputTokensPerCall.toInt()} max output tokens (only a bound; never reached in practice)")
        appendLine("  Hard cap: ${eur(e.capEur)} total across all real runs; the runner stops before it is exceeded.")
        append("  Expected fits under cap: ${if (e.expectedFitsCap) "yes" else "NO"}; pessimistic fits: ${if (e.pessimisticFitsCap) "yes" else "NO"}")
    }

    private fun eur(v: Double) = "EUR %.2f".formatUs(v)

    /**
     * Cost of the calls in [estimate] that are not yet in [doneKeys].
     * Finished calls are already in the spent total, so the preflight must not add them again.
     */
    fun remainingExpectedUsd(estimate: Estimate, items: List<Item>, reps: Int, doneKeys: Set<String>): Pair<Int, Double> {
        var calls = 0
        var usd = 0.0
        for (model in estimate.models) {
            if (model.calls == 0) continue
            var remaining = 0
            for (rep in 1..reps) for (item in items) for (variant in PromptVariant.entries) {
                if (callKey(item.id, model.alias, variant, rep) !in doneKeys) remaining++
            }
            calls += remaining
            usd += model.expectedUsd * remaining / model.calls.toDouble()
        }
        return calls to usd
    }

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
