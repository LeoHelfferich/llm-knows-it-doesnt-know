package io.github.leohelfferich.abstain.execution.model

import kotlinx.serialization.Serializable

import io.github.leohelfferich.abstain.config.model.ReasoningConfig

/** The model as it was at run time: canonical slug, prices, and what was actually sent. */
@Serializable
data class ManifestModel(
    val alias: String,
    val openRouterId: String,
    val canonicalSlug: String?,
    val name: String,
    val promptUsdPerMillion: Double,
    val completionUsdPerMillion: Double,
    val temperatureSent: Double?,
    val reasoning: ReasoningConfig?,
)
