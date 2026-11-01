package io.github.leohelfferich.abstain.prompt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.github.leohelfferich.abstain.prompt.model.PromptVariant

class PromptsTest {
    @Test
    fun `should render context, question, and the fixed output format`() {
        val context = "THE CONTEXT"
        val question = "THE QUESTION?"

        // ---
        val rendered = PromptVariant.entries.map { Prompts.render(it, context, question) }
        // ---

        assertThat(rendered).allSatisfy { text ->
            assertThat(text).contains("THE CONTEXT")
            assertThat(text).contains("Question: THE QUESTION?")
            assertThat(text).contains("ANSWER: <your answer>")
            assertThat(text).doesNotContain("{context}")
            assertThat(text).doesNotContain("{question}")
        }
    }

    @Test
    fun `should offer the sentinel only in the strict prompt`() {
        val context = "c"
        val question = "q"

        // ---
        val neutral = Prompts.render(PromptVariant.NEUTRAL, context, question)
        val strict = Prompts.render(PromptVariant.STRICT, context, question)
        // ---

        assertThat(neutral).doesNotContain(Prompts.SENTINEL)
        assertThat(strict).contains("reply exactly NOT_IN_CONTEXT")
    }

    @Test
    fun `should leave template markers inside the context untouched`() {
        val context = "uses {question} literally"

        // ---
        val rendered = Prompts.render(PromptVariant.NEUTRAL, context, "Q?")
        // ---

        assertThat(rendered).contains("uses {question} literally")
    }
}
