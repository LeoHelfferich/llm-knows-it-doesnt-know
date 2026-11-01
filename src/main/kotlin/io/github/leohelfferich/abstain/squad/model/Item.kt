package io.github.leohelfferich.abstain.squad.model

import kotlinx.serialization.Serializable

/** One sampled SQuAD question, paired with the passage it was drawn from. */
@Serializable
data class Item(
    val id: String,
    val kind: Kind,
    val pairIndex: Int,
    val article: String,
    val paragraphIndex: Int,
    val context: String,
    val question: String,
    val answers: List<String>,
    val plausibleAnswers: List<String>,
)
