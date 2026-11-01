package io.github.leohelfferich.abstain.config.model

import io.github.leohelfferich.abstain.config.HARD_CAP_EUR
import kotlinx.serialization.Serializable

/** Everything that changes the calls or the budget, loaded from `config/experiment.json`. */
@Serializable
data class ExperimentConfig(
    val seed: Long,
    val answerableCount: Int,
    val unanswerableCount: Int,
    val reps: Int,
    val temperature: Double,
    val maxTokens: Int,
    val capEur: Double,
    val safetyMarginEur: Double,
    val eurPerUsd: Double,
    val concurrency: Int,
    val charsPerToken: Double,
    val assumedAnswerTokens: Int,
    val bootstrapResamples: Int,
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
