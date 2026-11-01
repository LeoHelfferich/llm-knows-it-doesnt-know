package io.github.leohelfferich.abstain.analysis

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

import io.github.leohelfferich.abstain.execution.model.CallRecord
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.squad.model.Item
import io.github.leohelfferich.abstain.squad.model.Kind
import io.github.leohelfferich.abstain.testing.testItems
import io.github.leohelfferich.abstain.analysis.model.DisputedItem

class AnalyzerTest {
    private val items = testItems(4) // a0..a3, u0..u3

    @Test
    fun `should count non-abstaining replies on unanswerable items as inventions`() {
        // ---
        val c = analyzer().cell("alpha", PromptVariant.NEUTRAL, emptySet())
        // ---

        // u0: 0, u1: 2/3, u2: 1, u3: 1
        val invented = checkNotNull(c.invented.value)
        val low = checkNotNull(c.invented.lo)
        val high = checkNotNull(c.invented.hi)
        assertThat(invented).isCloseTo((0 + 2.0 / 3 + 1 + 1) / 4, within(1e-9))
        assertThat(c.unanswerableItems).isEqualTo(4)
        assertThat(c.unanswerableCalls).isEqualTo(12)
        assertThat(low).isLessThanOrEqualTo(invented)
        assertThat(invented).isLessThanOrEqualTo(high)
    }

    @Test
    fun `should measure over-refusal and correctness on answerable items`() {
        // ---
        val strict = analyzer().cell("alpha", PromptVariant.STRICT, emptySet())
        // ---

        assertThat(strict.overRefusal.value!!).isCloseTo(0.25, within(1e-9))
        assertThat(strict.invented.value!!).isCloseTo(0.0, within(1e-9))
        assertThat(strict.exactMatch.value!!).isCloseTo(0.75, within(1e-9))
        assertThat(strict.exactMatchWhenAnswered!!).isCloseTo(1.0, within(1e-9))
        val neutral = analyzer().cell("alpha", PromptVariant.NEUTRAL, emptySet())
        assertThat(neutral.overRefusal.value!!).isCloseTo(0.0, within(1e-9))
        assertThat(neutral.exactMatch.value!!).isCloseTo(0.75, within(1e-9)) // "the Paris" normalizes to "paris"
        assertThat(neutral.exactMatchWhenAnswered!!).isCloseTo(0.75, within(1e-9))
    }

    @Test
    fun `should count phrase abstentions separately from sentinel abstentions`() {
        // ---
        val c = analyzer().cell("alpha", PromptVariant.NEUTRAL, emptySet())
        // ---

        assertThat(c.phraseAbstentions).isEqualTo(1)
        assertThat(c.sentinelAbstentions).isEqualTo(3)
        assertThat(c.emptyCalls).isEqualTo(0)
    }

    @Test
    fun `should measure agreement of decisions and answers across repetitions`() {
        // ---
        val c = analyzer().cell("alpha", PromptVariant.NEUTRAL, emptySet())
        // ---

        // only u1 flips (1 of 3 pairs agree... abstain, answer, answer -> pairs: (a,b)=no,(a,c)=no,(b,c)=yes => 1/3)
        assertThat(c.consistency.decisionAgreement!!).isCloseTo((7 * 1.0 + 1.0 / 3) / 8, within(1e-9))
        assertThat(c.consistency.fullyConsistentItems!!).isCloseTo(7.0 / 8, within(1e-9))
        assertThat(c.consistency.decisionAgreementAnswerable!!).isCloseTo(1.0, within(1e-9))
        assertThat(c.consistency.decisionAgreementUnanswerable!!).isCloseTo((3 * 1.0 + 1.0 / 3) / 4, within(1e-9))
        assertThat(c.consistency.answerAgreement!!).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `should compute the strict versus neutral trade-off pairwise per item`() {
        // ---
        val t = analyzer().tradeoff("alpha", emptySet())
        // ---

        assertThat(t.deltaInvented.value!!).isCloseTo(0.0 - (2.0 / 3 + 1 + 1) / 4 * 1.0, within(1e-9))
        assertThat(t.deltaOverRefusal.value!!).isCloseTo(0.25, within(1e-9))
        assertThat(t.deltaExactMatch.value!!).isCloseTo(0.0, within(1e-9))
        assertThat(t.inventedAvoidedPerExtraRefusal!! > 1.0).isTrue()
        // one refusal-prone item out of four is too little evidence for a clear over-refusal effect
        assertThat(t.pattern).isEqualTo("invention-down-without-clear-over-refusal")
    }

    @Test
    fun `should recompute every metric without disputed items`() {
        val all = analyzer().scope("all", emptySet())

        // ---
        val without = analyzer().scope("excludingDisputed", setOf("u0", "a3"))
        // ---

        assertThat(all.excludedItems).isEqualTo(0)
        assertThat(without.excludedItems).isEqualTo(2)
        assertThat(without.unanswerableItems).isEqualTo(3)
        assertThat(without.answerableItems).isEqualTo(3)
        val n = without.cells.first { it.prompt == "neutral" }
        assertThat(n.exactMatch.value!!).isCloseTo(1.0, within(1e-9)) // the wrong answer a3 is gone: 3/3
        assertThat(n.invented.value!!).isCloseTo((2.0 / 3 + 1 + 1) / 3, within(1e-9)) // u0, the only abstention, is gone
        assertThat(n.unanswerableCalls).isEqualTo(9)
        val inventedOnEverything = checkNotNull(all.cells.first { it.prompt == "neutral" }.invented.value)
        val inventedWithoutDisputed = checkNotNull(n.invented.value)
        assertThat(inventedOnEverything).isLessThan(inventedWithoutDisputed)
    }

    @Test
    fun `should keep metrics defined when calls are missing`() {
        val partial = calls().filter { !(it.itemId == "u2" && it.rep == 3) } +
                CallRecord("u3", Kind.UNANSWERABLE, "alpha", "test/alpha", PromptVariant.NEUTRAL, 3, "error", "t", 0.7, 10, error = "boom")

        // ---
        val c = Analyzer(items, partial, listOf("alpha"), 100, 1).cell("alpha", PromptVariant.NEUTRAL, emptySet())
        // ---

        assertThat(c.unanswerableCalls).isEqualTo(11)
        val none = Analyzer(items, emptyList(), listOf("alpha"), 100, 1).cell("alpha", PromptVariant.NEUTRAL, emptySet())
        assertThat(none.invented.value).isNull()
        assertThat(none.consistency.decisionAgreement).isNull()
    }

    @Test
    fun `should report empty replies as neither inventions nor refusals`() {
        val withEmpty = calls().map { if (it.itemId == "u2" && it.prompt == PromptVariant.NEUTRAL) it.copy(content = "") else it }

        // ---
        val c = Analyzer(items, withEmpty, listOf("alpha"), 100, 1).cell("alpha", PromptVariant.NEUTRAL, emptySet())
        // ---

        assertThat(c.emptyCalls).isEqualTo(3)
        assertThat(c.invented.value!!).isCloseTo((0 + 2.0 / 3 + 0 + 1) / 4, within(1e-9))
    }

    @Test
    fun `should flag consensus inventions and hedged replies for review`() {
        val models = listOf("m1", "m2", "m3", "m4")
        val rows = buildList {
            for (m in models) for (rep in 1..3) {
                add(rec(item("u0"), m, PromptVariant.STRICT, rep, "ANSWER: Gérard Collomb"))
                add(rec(item("u1"), m, PromptVariant.STRICT, rep, "NOT_IN_CONTEXT"))
                add(rec(item("a0"), m, PromptVariant.NEUTRAL, rep, "ANSWER: Lyon"))
                add(rec(item("a1"), m, PromptVariant.NEUTRAL, rep, "ANSWER: Paris"))
            }
            add(rec(item("u2"), "m1", PromptVariant.NEUTRAL, 1, "ANSWER: " + "word ".repeat(30) + "although it is not mentioned"))
        }

        // ---
        val candidates = Analyzer(items, rows, models, 50, 1).reviewCandidates().associateBy { it.id }
        // ---

        assertThat(candidates.getValue("u0").reasons.single().contains("strict prompt")).isTrue()
        assertThat(candidates.getValue("a0").reasons.single().contains("gold answer may be wrong")).isTrue()
        assertThat(candidates.getValue("u2").reasons.single().contains("mix an answer with refusal wording")).isTrue()
        assertThat("u1" !in candidates && "a1" !in candidates).isTrue()
    }

    @Test
    fun `should keep raw text only when it differs from the standard form`() {
        val views = analyzer().itemViews(mapOf("u0" to DisputedItem("u0", "looks answerable")))

        // ---
        val u0 = views.first { it.id == "u0" }
        // ---

        assertThat(u0.disputed).isTrue()
        assertThat(u0.disputedReason).isEqualTo("looks answerable")
        assertThat(u0.responses.all { it.raw == null }).isTrue()
        val u1 = views.first { it.id == "u1" }
        assertThat(u1.responses.first { it.prompt == "neutral" && it.rep == 1 }.raw).isEqualTo("ANSWER: The context does not say.")
    }

    private fun rec(item: Item, model: String, variant: PromptVariant, rep: Int, content: String?, cost: Double = 0.001) =
        CallRecord(item.id, item.kind, model, "test/$model", variant, rep, "ok", "t", 0.7, 100, content, "stop", 50, 5, 0, cost, "reported")

    private fun item(id: String) = items.first { it.id == id }

    /**
     * alpha/neutral: invents on 3 of 4 unanswerables in every rep, one item flips between reps;
     * answers 4 of 4 answerables, one of them wrong.
     * alpha/strict: never invents, refuses 1 of 4 answerables in every rep.
     */
    private fun calls(): List<CallRecord> = buildList {
        for (rep in 1..3) {
            items.forEach { it ->
                val neutralPrompt = PromptVariant.NEUTRAL
                val strictPrompt = PromptVariant.STRICT
                if (it.kind == Kind.UNANSWERABLE) {
                    val neutral = when (it.id) {
                        "u0" -> "NOT_IN_CONTEXT"
                        "u1" -> if (rep == 1) "ANSWER: The context does not say." else "ANSWER: Gérard Collomb"
                        else -> "ANSWER: Gérard Collomb"
                    }
                    add(rec(it, "alpha", neutralPrompt, rep, neutral))
                    add(rec(it, "alpha", strictPrompt, rep, "NOT_IN_CONTEXT"))
                } else {
                    add(rec(it, "alpha", neutralPrompt, rep, if (it.id == "a3") "ANSWER: Lyon" else "ANSWER: the Paris"))
                    add(rec(it, "alpha", strictPrompt, rep, if (it.id == "a0") "NOT_IN_CONTEXT" else "ANSWER: Paris"))
                }
            }
        }
    }

    private fun analyzer() = Analyzer(items, calls(), listOf("alpha"), 300, 1)
}
