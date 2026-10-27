package io.github.leohelfferich.abstain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseParserTest {
    private fun parse(s: String?) = ResponseParser.parse(s)

    @Test
    fun `plain answer line`() {
        val p = parse("ANSWER: seismic waves")
        assertEquals(Outcome.ANSWER, p.outcome)
        assertEquals("seismic waves", p.answer)
        assertFalse(p.formatViolation)
    }

    @Test
    fun `exact sentinel is an abstention`() {
        listOf("NOT_IN_CONTEXT", "  NOT_IN_CONTEXT \n", "`NOT_IN_CONTEXT`", "**NOT_IN_CONTEXT**", "NOT_IN_CONTEXT.").forEach {
            val p = parse(it)
            assertEquals(Outcome.ABSTAIN_SENTINEL, p.outcome, it)
            assertNull(p.answer)
            assertFalse(p.formatViolation, it)
        }
    }

    @Test
    fun `sentinel behind an ANSWER prefix still counts but is a format violation only when extra lines follow`() {
        assertEquals(Outcome.ABSTAIN_SENTINEL, parse("ANSWER: NOT_IN_CONTEXT").outcome)
        val p = parse("NOT_IN_CONTEXT\nThe passage never mentions this.")
        assertEquals(Outcome.ABSTAIN_SENTINEL, p.outcome)
        assertTrue(p.formatViolation)
    }

    @Test
    fun `refusal wording behind ANSWER is an abstention (neutral prompt has no sentinel)`() {
        listOf(
            "ANSWER: The context does not mention this.",
            "ANSWER: Not mentioned in the context",
            "ANSWER: The passage doesn't say.",
            "ANSWER: There is no information about this in the context.",
            "ANSWER: I cannot determine this from the text.",
            "ANSWER: N/A",
            "ANSWER: Unknown",
        ).forEach { assertEquals(Outcome.ABSTAIN_PHRASE, parse(it).outcome, it) }
    }

    @Test
    fun `long answers with hedging are answers but flagged ambiguous`() {
        val text = "ANSWER: " + "The Normans were a people who settled in the north of France during the tenth century, " +
            "although the context does not say exactly when the first settlers arrived there"
        val p = parse(text)
        assertEquals(Outcome.ANSWER, p.outcome)
        assertTrue(p.ambiguous)
    }

    @Test
    fun `ordinary answers containing words like na or none inside names are not refusals`() {
        assertEquals(Outcome.ANSWER, parse("ANSWER: Diana").outcome)
        assertEquals(Outcome.ANSWER, parse("ANSWER: Fedora").outcome)
        assertEquals(Outcome.ANSWER, parse("ANSWER: nonetheless the Roman Empire").outcome)
    }

    @Test
    fun `missing prefix is a format violation but still classified`() {
        val p = parse("Paris")
        assertEquals(Outcome.ANSWER, p.outcome)
        assertEquals("Paris", p.answer)
        assertTrue(p.formatViolation)
    }

    @Test
    fun `answer on the following line and extra lines`() {
        val p = parse("ANSWER:\nParis")
        assertEquals("Paris", p.answer)
        val q = parse("ANSWER: Paris\nBecause it is the capital.")
        assertEquals("Paris", q.answer)
        assertTrue(q.formatViolation)
    }

    @Test
    fun `markdown bold prefix is tolerated`() {
        assertEquals("Paris", parse("**ANSWER:** Paris").answer)
        assertEquals("Paris", parse("answer: Paris").answer)
    }

    @Test
    fun `empty and blank replies`() {
        assertEquals(Outcome.EMPTY, parse(null).outcome)
        assertEquals(Outcome.EMPTY, parse("   \n").outcome)
        assertEquals(Outcome.EMPTY, parse("ANSWER:").outcome)
    }
}
