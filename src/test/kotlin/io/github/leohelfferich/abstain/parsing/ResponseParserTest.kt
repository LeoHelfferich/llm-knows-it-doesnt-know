package io.github.leohelfferich.abstain.parsing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.github.leohelfferich.abstain.parsing.model.Outcome

class ResponseParserTest {
    @Test
    fun `should parse a plain answer line`() {
        val reply = "ANSWER: seismic waves"

        // ---
        val parsed = ResponseParser.parse(reply)
        // ---

        assertThat(parsed.outcome).isEqualTo(Outcome.ANSWER)
        assertThat(parsed.answer).isEqualTo("seismic waves")
        assertThat(parsed.formatViolation).isFalse()
    }

    @Test
    fun `should treat an exact sentinel as an abstention`() {
        val replies = listOf(
            "NOT_IN_CONTEXT",
            "  NOT_IN_CONTEXT \n",
            "`NOT_IN_CONTEXT`",
            "**NOT_IN_CONTEXT**",
            "NOT_IN_CONTEXT.",
        )

        // ---
        val parsed = replies.map { ResponseParser.parse(it) }
        // ---

        parsed.forEachIndexed { index, result ->
            assertThat(result.outcome).describedAs(replies[index]).isEqualTo(Outcome.ABSTAIN_SENTINEL)
            assertThat(result.answer).describedAs(replies[index]).isNull()
            assertThat(result.formatViolation).describedAs(replies[index]).isFalse()
        }
    }

    @Test
    fun `should treat a sentinel after the answer prefix as an abstention`() {
        val prefixed = "ANSWER: NOT_IN_CONTEXT"
        val withExtraLine = "NOT_IN_CONTEXT\nThe passage never mentions this."

        // ---
        val prefixedReply = ResponseParser.parse(prefixed)
        val extraReply = ResponseParser.parse(withExtraLine)
        // ---

        assertThat(prefixedReply.outcome).isEqualTo(Outcome.ABSTAIN_SENTINEL)
        assertThat(extraReply.outcome).isEqualTo(Outcome.ABSTAIN_SENTINEL)
        assertThat(extraReply.formatViolation).isTrue()
    }

    @Test
    fun `should treat short refusal wording as a phrase abstention`() {
        val replies = listOf(
            "ANSWER: The context does not mention this.",
            "ANSWER: Not mentioned in the context",
            "ANSWER: The passage doesn't say.",
            "ANSWER: There is no information about this in the context.",
            "ANSWER: I cannot determine this from the text.",
            "ANSWER: N/A",
            "ANSWER: Unknown",
        )

        // ---
        val parsed = replies.map { ResponseParser.parse(it) }
        // ---

        parsed.forEachIndexed { index, result ->
            assertThat(result.outcome).describedAs(replies[index]).isEqualTo(Outcome.ABSTAIN_PHRASE)
        }
    }

    @Test
    fun `should keep a long hedged reply as an ambiguous answer`() {
        val reply = "ANSWER: " +
            "The Normans were a people who settled in the north of France during the tenth century, " +
            "although the context does not say exactly when the first settlers arrived there"

        // ---
        val parsed = ResponseParser.parse(reply)
        // ---

        assertThat(parsed.outcome).isEqualTo(Outcome.ANSWER)
        assertThat(parsed.ambiguous).isTrue()
    }

    @Test
    fun `should not treat names that contain refusal words as refusals`() {
        val replies = listOf("ANSWER: Diana", "ANSWER: Fedora", "ANSWER: nonetheless the Roman Empire")

        // ---
        val parsed = replies.map { ResponseParser.parse(it) }
        // ---

        assertThat(parsed.map { it.outcome }).containsOnly(Outcome.ANSWER)
    }

    @Test
    fun `should classify a reply that misses the answer prefix`() {
        val reply = "Paris"

        // ---
        val parsed = ResponseParser.parse(reply)
        // ---

        assertThat(parsed.outcome).isEqualTo(Outcome.ANSWER)
        assertThat(parsed.answer).isEqualTo("Paris")
        assertThat(parsed.formatViolation).isTrue()
    }

    @Test
    fun `should read an answer placed on the following line`() {
        val onTheNextLine = "ANSWER:\nParis"
        val withAnExtraLine = "ANSWER: Paris\nBecause it is the capital."

        // ---
        val nextLine = ResponseParser.parse(onTheNextLine)
        val extraLine = ResponseParser.parse(withAnExtraLine)
        // ---

        assertThat(nextLine.answer).isEqualTo("Paris")
        assertThat(extraLine.answer).isEqualTo("Paris")
        assertThat(extraLine.formatViolation).isTrue()
    }

    @Test
    fun `should tolerate a markdown answer prefix`() {
        val bold = "**ANSWER:** Paris"
        val lowercase = "answer: Paris"

        // ---
        val boldReply = ResponseParser.parse(bold)
        val lowercaseReply = ResponseParser.parse(lowercase)
        // ---

        assertThat(boldReply.answer).isEqualTo("Paris")
        assertThat(lowercaseReply.answer).isEqualTo("Paris")
    }

    @Test
    fun `should classify empty replies as empty`() {
        val replies = listOf(null, "   \n", "ANSWER:")

        // ---
        val parsed = replies.map { ResponseParser.parse(it) }
        // ---

        assertThat(parsed.map { it.outcome }).containsOnly(Outcome.EMPTY)
    }
}
