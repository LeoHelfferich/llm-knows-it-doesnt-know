package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

import io.github.leohelfferich.abstain.execution.model.ManifestModel
import io.github.leohelfferich.abstain.execution.model.PromptInfo

/** The slice of the manifest the published results need. */
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
    val servingProviders: List<ServingProvider> = emptyList(),
    val failedAttemptCounts: Map<String, Int> = emptyMap(),
)
