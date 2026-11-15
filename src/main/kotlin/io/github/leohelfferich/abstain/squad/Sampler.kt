package io.github.leohelfferich.abstain.squad

import java.util.Random

import io.github.leohelfferich.abstain.config.model.ExperimentConfig
import io.github.leohelfferich.abstain.squad.model.Kind
import io.github.leohelfferich.abstain.squad.model.Item
import io.github.leohelfferich.abstain.squad.model.SampleFile
import io.github.leohelfferich.abstain.squad.model.SquadQuestion
import io.github.leohelfferich.abstain.squad.model.SquadParagraph

/**
 * Seeded sampler. The draw is [sample]; the diagram there is the algorithm `paired-paragraph-v1`.
 * `java.util.Random` is a specified LCG, so the same seed yields the same sample on every JVM.
 */
object Sampler {
    const val ALGORITHM = "paired-paragraph-v1: shuffle eligible paragraphs with java.util.Random(seed) Fisher-Yates; " +
        "per paragraph draw 1 answerable + 1 unanswerable question; one pair per passage"

    private fun <T> shuffled(list: List<T>, rnd: Random): MutableList<T> {
        val a = list.toMutableList()
        for (i in a.size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
        return a
    }

    /**
     * Draws [answerable] and [unanswerable] questions. File order is the order before the shuffle.
     *
     * ```
     * paragraphs, file order
     *   |
     *   v
     * eligible: at least one answerable AND one unanswerable question
     *   |
     *   v
     * Fisher-Yates with java.util.Random(seed)
     *   |
     *   v
     * first min(answerable, unanswerable, eligible) paragraphs
     *   |
     *   +-- Fisher-Yates of its answerable questions   --> take the first
     *   +-- Fisher-Yates of its unanswerable questions --> take the first
     *        both share pairIndex; that passage is not used again
     *   |
     *   v
     * if one count is still short:
     *   one matching question per unused paragraph, from a second shuffle
     *   (the committed 100 + 100 sample never reaches this branch)
     * ```
     */
    fun sample(paragraphs: List<SquadParagraph>, seed: Long, answerable: Int, unanswerable: Int): List<Item> {
        val rnd = Random(seed)
        val eligible = shuffled(paragraphs.filter { p -> p.questions.any { !it.isImpossible } && p.questions.any { it.isImpossible } }, rnd)
        val pairs = minOf(answerable, unanswerable, eligible.size)
        val items = mutableListOf<Item>()

        for (i in 0 until pairs) {
            val paragraph = eligible[i]
            val answerableQuestion = shuffled(paragraph.questions.filter { !it.isImpossible }, rnd).first()
            val unanswerableQuestion = shuffled(paragraph.questions.filter { it.isImpossible }, rnd).first()
            items += toItem(paragraph, answerableQuestion, i)
            items += toItem(paragraph, unanswerableQuestion, i)
        }

        val paired = eligible.take(pairs).toSet()
        // Shuffled only if a requested count is larger than the number of pairs. The committed sample never reaches this.
        val rest = lazy { shuffled(paragraphs.filter { it !in paired }, rnd) }
        var pairIndex = pairs
        pairIndex = fill(items, rnd, rest, answerable - pairs, wantImpossible = false, pairIndex)
        fill(items, rnd, rest, unanswerable - pairs, wantImpossible = true, pairIndex)
        check(items.map { it.id }.toSet().size == items.size) { "Duplicate question ids in sample" }
        return items
    }

    private fun toItem(paragraph: SquadParagraph, question: SquadQuestion, pairIndex: Int) = Item(
        id = question.id,
        kind = if (question.isImpossible) Kind.UNANSWERABLE else Kind.ANSWERABLE,
        pairIndex = pairIndex,
        article = paragraph.article,
        paragraphIndex = paragraph.paragraphIndex,
        context = paragraph.context,
        question = question.question,
        answers = question.answers,
        plausibleAnswers = question.plausibleAnswers,
    )

    /**
     * Tops up one kind after the pairs are taken.
     * A paragraph with no remaining question of that kind is skipped.
     * Returns the next free pair index.
     */
    private fun fill(
        items: MutableList<Item>,
        rnd: Random,
        rest: Lazy<List<SquadParagraph>>,
        missing: Int,
        wantImpossible: Boolean,
        startPairIndex: Int,
    ): Int {
        var remaining = missing
        var cursor = 0
        var pairIndex = startPairIndex
        val taken = items.map { it.id }.toMutableSet()
        while (remaining > 0) {
            val paragraph = rest.value.getOrNull(cursor++)
                ?: error("Not enough paragraphs to draw $missing more ${if (wantImpossible) "unanswerable" else "answerable"} questions")
            val question = shuffled(
                paragraph.questions.filter { it.isImpossible == wantImpossible && it.id !in taken },
                rnd,
            ).firstOrNull() ?: continue
            items += toItem(paragraph, question, pairIndex)
            pairIndex++
            taken += question.id
            remaining--
        }
        return pairIndex
    }

    fun buildSampleFile(config: ExperimentConfig, paragraphs: List<SquadParagraph>): SampleFile =
        SampleFile(
            seed = config.seed,
            algorithm = ALGORITHM,
            source = SquadSource.source,
            answerableCount = config.answerableCount,
            unanswerableCount = config.unanswerableCount,
            items = sample(paragraphs, config.seed, config.answerableCount, config.unanswerableCount),
        )

    /** Dry-run subset: the first N pairs of the sample, so answerable and unanswerable share passages. */
    fun dryRunSubset(sample: SampleFile, answerable: Int, unanswerable: Int): List<Item> {
        val a = sample.items.filter { it.kind == Kind.ANSWERABLE }.sortedBy { it.pairIndex }.take(answerable)
        val u = sample.items.filter { it.kind == Kind.UNANSWERABLE }.sortedBy { it.pairIndex }.take(unanswerable)
        return (a + u).sortedWith(compareBy({ it.pairIndex }, { it.kind }))
    }
}
