package io.github.leohelfferich.abstain.squad.model

/** One question inside a SQuAD paragraph, before sampling. */
data class SquadQuestion(
    val id: String,
    val question: String,
    val isImpossible: Boolean,
    val answers: List<String>,
    val plausibleAnswers: List<String>,
)
