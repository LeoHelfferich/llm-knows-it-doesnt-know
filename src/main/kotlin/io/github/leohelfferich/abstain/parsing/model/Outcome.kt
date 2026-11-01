package io.github.leohelfferich.abstain.parsing.model

/** How [io.github.leohelfferich.abstain.parsing.ResponseParser] classified a reply. */
enum class Outcome { ANSWER, ABSTAIN_SENTINEL, ABSTAIN_PHRASE, EMPTY;
    val isAbstention get() = this == ABSTAIN_SENTINEL || this == ABSTAIN_PHRASE
}
