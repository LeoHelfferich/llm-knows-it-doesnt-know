package io.github.leohelfferich.abstain.squad.model

/** One passage and its questions, in file order. */
data class SquadParagraph(
    val articleIndex: Int,
    val article: String,
    val paragraphIndex: Int,
    val context: String,
    val questions: List<SquadQuestion>,
)
