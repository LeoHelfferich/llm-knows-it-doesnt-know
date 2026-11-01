package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

import io.github.leohelfferich.abstain.squad.model.SampleSource

/** The slice of the sample the published results need, plus attribution. */
@Serializable
data class SampleInfo(
    val seed: Long,
    val algorithm: String,
    val answerable: Int,
    val unanswerable: Int,
    val source: SampleSource,
    val sampleSha256: String,
    val attribution: String,
)
