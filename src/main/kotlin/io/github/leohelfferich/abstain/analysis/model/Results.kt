package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/** Published metrics. The pages under `docs/` load this file. */
@Serializable
data class Results(
    val schemaVersion: Int,
    val run: RunInfo,
    val sample: SampleInfo,
    val disputed: List<DisputedItem>,
    val scopes: List<Scope>,
)
