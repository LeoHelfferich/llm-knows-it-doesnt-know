package io.github.leohelfferich.abstain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PromptsTest {
    @Test
    fun `both prompts contain context, question and the fixed output format`() {
        PromptVariant.entries.forEach {
            val text = Prompts.render(it, "THE CONTEXT", "THE QUESTION?")
            assertTrue("THE CONTEXT" in text)
            assertTrue("Question: THE QUESTION?" in text)
            assertTrue("ANSWER: <your answer>" in text)
            assertFalse("{context}" in text || "{question}" in text)
        }
    }

    @Test
    fun `only the strict prompt offers the sentinel`() {
        assertFalse(Prompts.SENTINEL in Prompts.render(PromptVariant.NEUTRAL, "c", "q"))
        val strict = Prompts.render(PromptVariant.STRICT, "c", "q")
        assertTrue("reply exactly NOT_IN_CONTEXT" in strict)
    }

    @Test
    fun `template hashes differ and are stable`() {
        assertNotEquals(Prompts.templateSha256(PromptVariant.NEUTRAL), Prompts.templateSha256(PromptVariant.STRICT))
        assertEquals(Prompts.templateSha256(PromptVariant.NEUTRAL), sha256Hex(Prompts.template(PromptVariant.NEUTRAL)))
    }

    @Test
    fun `context containing template markers is not re-substituted`() {
        val text = Prompts.render(PromptVariant.NEUTRAL, "uses {question} literally", "Q?")
        assertTrue("uses {question} literally" in text)
    }
}
