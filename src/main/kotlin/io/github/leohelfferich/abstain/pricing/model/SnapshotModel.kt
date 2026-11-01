package io.github.leohelfferich.abstain.pricing.model

import kotlinx.serialization.Serializable

/** One row of the committed OpenRouter price list. Prices are USD per token. */
@Serializable
data class SnapshotModel(
    val id: String,
    val canonicalSlug: String?,
    val name: String,
    val promptUsdPerToken: String,
    val completionUsdPerToken: String,
    val internalReasoningUsdPerToken: String?,
    val contextLength: Int?,
    val supportedParameters: List<String>,
) {
    val promptPrice get() = promptUsdPerToken.toBigDecimal().toDouble()
    val completionPrice get() = completionUsdPerToken.toBigDecimal().toDouble()
    val supportsTemperature get() = "temperature" in supportedParameters

    fun requireBillable() {
        require(promptPrice.isFinite() && promptPrice >= 0.0) { "Prompt price for '$id' must be a non-negative finite USD amount" }
        // A zero completion price makes the worst-case reservation free, so the cap would not bind.
        require(completionPrice.isFinite() && completionPrice > 0.0) { "Completion price for '$id' must be a positive USD amount" }
    }
}
