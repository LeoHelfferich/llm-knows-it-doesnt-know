package io.github.leohelfferich.abstain.squad.model

import kotlinx.serialization.Serializable

/** The committed sample: seed, algorithm, source, and the drawn items. */
@Serializable
data class SampleFile(
    val seed: Long,
    val algorithm: String,
    val source: SampleSource,
    val answerableCount: Int,
    val unanswerableCount: Int,
    val items: List<Item>,
)
