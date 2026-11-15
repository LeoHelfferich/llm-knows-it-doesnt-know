package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/** How many successful calls for one model one provider served. */
@Serializable
data class ServingProvider(val model: String, val provider: String, val calls: Int)
