package io.github.leohelfferich.abstain.execution.model

import kotlinx.serialization.Serializable

import io.github.leohelfferich.abstain.cost.model.Estimate

/** Binds a run directory to its configuration, sample, prompts, and pricing. */
@Serializable
data class Manifest(
    val schemaVersion: Int = 1,
    val runId: String,
    /** `"dry-run"` or `"full"`. */
    val kind: String,
    /** Synthetic client. Analyze will not publish this run under `docs/` unless `--allow-mock` is passed. */
    val mock: Boolean,
    /** `"running"`, `"complete"`, `"stopped"`, or `"incomplete"`. */
    val status: String,
    val stopReason: String? = null,
    val startedAt: String,
    val finishedAt: String? = null,
    val gitCommit: String? = null,
    val gitDirty: Boolean? = null,
    val java: String,
    /**
     * SHA-256 of the config, the item ids, the repetition count, both prompt templates, and the prices.
     * A resumed run whose fingerprint differs is refused.
     */
    val fingerprint: String,
    val configSha256: String,
    val sampleSha256: String,
    val sourceDatasetSha256: String,
    val seed: Long,
    val reps: Int,
    val temperature: Double,
    val maxTokens: Int,
    val itemCount: Int,
    val answerableItems: Int,
    val unanswerableItems: Int,
    val prompts: Map<String, PromptInfo>,
    val models: List<ManifestModel>,
    val plannedCalls: Int,
    val okCalls: Int = 0,
    val errorCalls: Int = 0,
    val spentUsdThisRun: Double = 0.0,
    val spentEurThisRun: Double = 0.0,
    val eurPerUsd: Double,
    val capEur: Double,
    val estimate: Estimate? = null,
)
