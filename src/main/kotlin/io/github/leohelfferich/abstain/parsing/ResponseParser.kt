package io.github.leohelfferich.abstain.parsing

import io.github.leohelfferich.abstain.prompt.Prompts
import io.github.leohelfferich.abstain.parsing.model.Outcome
import io.github.leohelfferich.abstain.parsing.model.ParsedResponse

/**
 * Deterministic, judge-free classification of a model reply.
 * The decision tree is on [parse].
 */
object ResponseParser {
    const val MAX_ABSTENTION_WORDS = 25

    private val answerPrefix = Regex("^\\s*\\**\\s*answer\\s*\\**\\s*:\\s*\\**", RegexOption.IGNORE_CASE)
    private val wrapper = Regex("^[\\s`'\"*_.!]+|[\\s`'\"*_.!]+$")
    private val sentinelAnywhere = Regex("(?<![A-Za-z_])NOT_IN_CONTEXT(?![A-Za-z_])")

    private val lexicon: List<Regex> = listOf(
        "not (explicitly |directly |clearly )?(mentioned|stated|specified|provided|given|available|found|included|addressed|indicated|covered|discussed)",
        "(does|do|did|is|are|was|were) ?n[o']t (explicitly |directly |clearly )?(say|mention|specify|state|provide|contain|include|give|indicate|address|cover|discuss|tell|name|identify|list|describe)",
        "doesn['’]t (explicitly |directly |clearly )?(say|mention|specify|state|provide|contain|include|give|indicate|address|cover|discuss|tell|name|identify|list|describe)",
        "no (such |relevant |specific |explicit )?(information|mention|answer|details?|data)",
        "there is no ",
        "(cannot|can['’]t|can not|could not|couldn['’]t|unable to) (be )?(determine|determined|answer|answered|find|found|tell|say|identify)",
        "unanswerable",
        "i (do not|don['’]t) know",
        "(not|n['’]t) enough information",
        "insufficient (information|context)",
        "(isn['’]t|is not|not) (in|within) the (context|passage|text|provided)",
        "no (answer|information) (is )?(in|given|provided|available)",
        "^(n/?a|none|unknown|not applicable)[.!]?$",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun matchesRefusalLexicon(text: String): Boolean = lexicon.any { it.containsMatchIn(text) }

    private fun wordCount(text: String) = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun isSentinel(text: String) = wrapper.replace(text, "").equals(Prompts.SENTINEL, ignoreCase = false)

    /**
     * Classifies one reply. Empty and a missing body are the same outcome.
     *
     * ```
     * content
     *   |
     *   +-- blank -------------------------------------------> EMPTY
     *   |
     *   +-- first line is NOT_IN_CONTEXT --------------------> ABSTAIN_SENTINEL
     *   |     extra lines set formatViolation
     *   |
     *   v
     * strip a leading "ANSWER:" (markdown asterisks allowed)
     *   a bare "ANSWER:" takes the next line as the payload
     *   |
     *   +-- payload blank -----------------------------------> EMPTY
     *   +-- payload is NOT_IN_CONTEXT -----------------------> ABSTAIN_SENTINEL
     *   +-- no ANSWER: prefix, but NOT_IN_CONTEXT appears ---> ABSTAIN_SENTINEL
     *   |     (formatViolation; the token must be a whole word)
     *   |
     *   +-- refusal lexicon
     *   |     <= 25 words -----------------------------------> ABSTAIN_PHRASE
     *   |     longer ----------------------------------------> ANSWER, ambiguous=true
     *   |       counted as an answer, flagged for review
     *   |
     *   +-- otherwise ---------------------------------------> ANSWER
     *
     * formatViolation is set unless the reply is exactly one "ANSWER:" line
     * (a lone sentinel line is not a violation).
     * ```
     */
    fun parse(content: String?): ParsedResponse {
        val text = content?.trim().orEmpty()
        if (text.isEmpty()) return ParsedResponse(Outcome.EMPTY, null, formatViolation = true, ambiguous = false)

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.first()
        val multiLine = lines.size > 1

        if (isSentinel(first)) {
            return ParsedResponse(Outcome.ABSTAIN_SENTINEL, null, formatViolation = multiLine, ambiguous = false)
        }

        val prefixMatch = answerPrefix.find(first)
        val hasPrefix = prefixMatch != null
        var payload = if (prefixMatch != null) first.substring(prefixMatch.range.last + 1).trim() else first
        var payloadLines = lines
        if (hasPrefix && payload.isEmpty() && lines.size > 1) {
            payload = lines[1]
            payloadLines = lines.drop(1)
        }
        payload = payload.trim().trimEnd('*').trim()

        if (payload.isEmpty()) return ParsedResponse(Outcome.EMPTY, null, formatViolation = true, ambiguous = false)

        val violation = !hasPrefix || payloadLines.size > 1
        if (isSentinel(payload)) {
            return ParsedResponse(Outcome.ABSTAIN_SENTINEL, null, formatViolation = violation, ambiguous = false)
        }
        if (!hasPrefix && sentinelAnywhere.containsMatchIn(text)) {
            return ParsedResponse(Outcome.ABSTAIN_SENTINEL, null, formatViolation = true, ambiguous = false)
        }
        if (matchesRefusalLexicon(payload)) {
            return if (wordCount(payload) <= MAX_ABSTENTION_WORDS) {
                ParsedResponse(Outcome.ABSTAIN_PHRASE, null, formatViolation = violation, ambiguous = false)
            } else {
                ParsedResponse(Outcome.ANSWER, payload, formatViolation = violation, ambiguous = true)
            }
        }
        return ParsedResponse(Outcome.ANSWER, payload, formatViolation = violation, ambiguous = false)
    }
}
