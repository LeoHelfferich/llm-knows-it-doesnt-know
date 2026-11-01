package io.github.leohelfferich.abstain.config.model

import kotlinx.serialization.Serializable

/** Size of the cheap pipeline check that runs before the full experiment. */
@Serializable
data class DryRunConfig(
    val answerable: Int = 5,
    val unanswerable: Int = 5,
    val reps: Int = 1,
)
