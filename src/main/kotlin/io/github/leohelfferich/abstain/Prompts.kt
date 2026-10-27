package io.github.leohelfferich.abstain

/**
 * The two prompt variants. Both ask for the same output format so abstentions can be detected
 * mechanically. The only intended difference is the explicit abstention instruction in STRICT.
 */
object Prompts {
    const val SENTINEL = "NOT_IN_CONTEXT"
    const val VERSION = 1

    private const val NEUTRAL_TEMPLATE = """Answer the question using the context.

Context:
{context}

Question: {question}

Reply with exactly one line in this format, with a short answer (a few words, copied from the context where possible):
ANSWER: <your answer>"""

    private const val STRICT_TEMPLATE = """Answer the question using only the context. If the context does not contain the answer, reply exactly NOT_IN_CONTEXT.

Context:
{context}

Question: {question}

Reply with exactly one line, in one of these two formats. When you answer, give a short answer (a few words, copied from the context where possible):
ANSWER: <your answer>
NOT_IN_CONTEXT"""

    fun template(variant: PromptVariant): String = when (variant) {
        PromptVariant.NEUTRAL -> NEUTRAL_TEMPLATE
        PromptVariant.STRICT -> STRICT_TEMPLATE
    }

    private val placeholder = Regex("\\{(context|question)}")

    fun render(variant: PromptVariant, context: String, question: String): String =
        placeholder.replace(template(variant)) { if (it.groupValues[1] == "context") context.trim() else question.trim() }

    fun templateSha256(variant: PromptVariant): String = sha256Hex(template(variant))
}
