package io.github.leohelfferich.abstain.config.model

import io.github.leohelfferich.abstain.config.HARD_CAP_EUR
import kotlinx.serialization.Serializable

/**
 * Everything that changes the calls or the budget, loaded from `config/experiment.json`.
 * [validate] is the list of values the runner will not start with.
 */
@Serializable
data class ExperimentConfig(
    /** `java.util.Random` seed. The sampler is the only consumer. */
    val seed: Long,
    /** Answerable questions in the full sample. */
    val answerableCount: Int,
    /** Unanswerable questions in the full sample, paired with [answerableCount] on shared passages. */
    val unanswerableCount: Int,
    /** Repetitions of each (item, model, prompt) in the full run. */
    val reps: Int,
    /** Sent only for models whose [ModelConfig.sendTemperature] is true. */
    val temperature: Double,
    /** Completion budget, and the output side of every worst-case reservation. */
    val maxTokens: Int,
    /** Euro cap across every real run. Cannot exceed the hard cap, and `--cap-eur` can only lower it. */
    val capEur: Double,
    /** Held back from [capEur] before a call is reserved, so the last calls cannot land on the cap. */
    val safetyMarginEur: Double,
    /** EUR per USD. Set above the market rate on purpose, so the cap is conservative. */
    val eurPerUsd: Double,
    /** Calls in flight. Each one reserves its worst case before it is sent. */
    val concurrency: Int,
    /** Assumed characters per input token when no earlier run has been observed. */
    val charsPerToken: Double,
    /** Assumed completion tokens for an answer, before a model's reasoning allowance. */
    val assumedAnswerTokens: Int,
    /** Bootstrap draws inside each interval. Zero keeps the point and leaves the bounds null. */
    val bootstrapResamples: Int,
    /** Seed for the bootstrap. Independent of [seed]. */
    val bootstrapSeed: Long,
    val dryRun: DryRunConfig,
    val models: List<ModelConfig>,
)

/**
 * Rejects a configuration the runner must not start.
 *
 * The euro cap can be lowered, and it cannot be raised past [HARD_CAP_EUR].
 */
fun ExperimentConfig.validate() {
    require(reps >= 1) { "reps must be >= 1" }
    require(capEur in 0.0..HARD_CAP_EUR) { "capEur must be within 0..${HARD_CAP_EUR} (hard cap)" }
    require(safetyMarginEur in 0.0..<capEur) { "safetyMarginEur must be in [0, capEur)" }
    require(eurPerUsd > 0) { "eurPerUsd must be positive" }
    require(concurrency >= 1) { "concurrency must be >= 1" }
    require(maxTokens >= 1) { "maxTokens must be >= 1" }
    require(models.map { it.alias }.toSet().size == models.size) { "model aliases must be unique" }
    require(models.map { it.openRouterId }.toSet().size == models.size) { "model ids must be unique" }
}
