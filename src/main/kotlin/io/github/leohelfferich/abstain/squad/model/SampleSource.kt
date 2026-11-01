package io.github.leohelfferich.abstain.squad.model

import kotlinx.serialization.Serializable

/** Provenance of the dataset the sample was drawn from. */
@Serializable
data class SampleSource(
    val name: String,
    val url: String,
    val sha256: String,
    val license: String,
)
