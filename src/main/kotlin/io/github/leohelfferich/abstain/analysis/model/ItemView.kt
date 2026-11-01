package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

import io.github.leohelfferich.abstain.squad.model.Kind

/** One sample item with every reply collected for it. */
@Serializable
data class ItemView(
    val id: String,
    val kind: Kind,
    val pairIndex: Int,
    val article: String,
    val context: String,
    val question: String,
    val answers: List<String>,
    val plausibleAnswers: List<String>,
    val disputed: Boolean,
    val disputedReason: String?,
    val responses: List<ResponseView>,
)
