package io.github.leohelfferich.abstain.squad.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Whether the gold answer is in the passage. */
@Serializable
enum class Kind {
    @SerialName("answerable") ANSWERABLE,
    @SerialName("unanswerable") UNANSWERABLE,
}
