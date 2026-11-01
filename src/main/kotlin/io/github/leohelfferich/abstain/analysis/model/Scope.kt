package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/** One cut of the sample, for example everything, or everything except disputed items. */
@Serializable
data class Scope(
    val name: String,
    val excludedItems: Int,
    val unanswerableItems: Int,
    val answerableItems: Int,
    val cells: List<CellMetrics>,
    val tradeoffs: List<Tradeoff>,
)
