package io.github.leohelfferich.abstain.squad.model

import kotlinx.serialization.Serializable

/** One sampled SQuAD question, paired with the passage it was drawn from. */
@Serializable
data class Item(
    /** SQuAD question id. Stable across draws; the seed decides which ids are kept. */
    val id: String,
    val kind: Kind,
    /** Shared by the answerable and unanswerable question drawn from the same passage. */
    val pairIndex: Int,
    val article: String,
    val paragraphIndex: Int,
    val context: String,
    val question: String,
    val answers: List<String>,
    val plausibleAnswers: List<String>,
)
