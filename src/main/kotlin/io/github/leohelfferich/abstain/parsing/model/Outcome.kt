package io.github.leohelfferich.abstain.parsing.model

/**
 * How [io.github.leohelfferich.abstain.parsing.ResponseParser] classified a reply.
 *
 * ```
 * ANSWER            an answer. On an unanswerable item this is an invention.
 * ABSTAIN_SENTINEL  the reply is NOT_IN_CONTEXT
 * ABSTAIN_PHRASE    a short refusal. The neutral prompt has no sentinel, so this is how it abstains.
 * EMPTY             blank, or "ANSWER:" with nothing after it. Not an abstention.
 * ```
 */
enum class Outcome {
    /** The reply gave an answer. On an unanswerable item this is an invention. */
    ANSWER,
    /** The reply is the sentinel `NOT_IN_CONTEXT`. */
    ABSTAIN_SENTINEL,
    /** A short reply matching the refusal lexicon. */
    ABSTAIN_PHRASE,
    /** Blank content, or an `ANSWER:` prefix with nothing after it. */
    EMPTY;

    /** Sentinel or phrase. [EMPTY] is a format failure, not an abstention. */
    val isAbstention get() = this == ABSTAIN_SENTINEL || this == ABSTAIN_PHRASE
}
