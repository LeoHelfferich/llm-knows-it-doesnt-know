package io.github.leohelfferich.abstain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyzerTest {
    private fun rec(item: Item, model: String, variant: PromptVariant, rep: Int, content: String?, cost: Double = 0.001) =
        CallRecord(item.id, item.kind, model, "test/$model", variant, rep, "ok", "t", 0.7, 100, content, "stop", 50, 5, 0, cost, "reported")

    private val items = testItems(4) // a0..a3, u0..u3
    private fun item(id: String) = items.first { it.id == id }

    /**
     * alpha/neutral: invents on 3 of 4 unanswerables in every rep, one item flips between reps;
     *                answers 4 of 4 answerables, one of them wrong
     * alpha/strict:  never invents, refuses 1 of 4 answerables in every rep
     */
    private fun calls(): List<CallRecord> = buildList {
        for (rep in 1..3) {
            items.forEach { it ->
                val n = PromptVariant.NEUTRAL
                val s = PromptVariant.STRICT
                if (it.kind == Kind.UNANSWERABLE) {
                    val neutral = when (it.id) {
                        "u0" -> "NOT_IN_CONTEXT"
                        "u1" -> if (rep == 1) "ANSWER: The context does not say." else "ANSWER: Gérard Collomb"
                        else -> "ANSWER: Gérard Collomb"
                    }
                    add(rec(it, "alpha", n, rep, neutral))
                    add(rec(it, "alpha", s, rep, "NOT_IN_CONTEXT"))
                } else {
                    add(rec(it, "alpha", n, rep, if (it.id == "a3") "ANSWER: Lyon" else "ANSWER: the Paris"))
                    add(rec(it, "alpha", s, rep, if (it.id == "a0") "NOT_IN_CONTEXT" else "ANSWER: Paris"))
                }
            }
        }
    }

    private fun analyzer() = Analyzer(items, calls(), listOf("alpha"), 300, 1)

    @Test
    fun `invented-answer rate counts non-abstaining replies on unanswerable items`() {
        val c = analyzer().cell("alpha", PromptVariant.NEUTRAL, emptySet())
        // u0: 0, u1: 2/3, u2: 1, u3: 1
        assertEquals((0 + 2.0 / 3 + 1 + 1) / 4, c.invented.value!!, 1e-9)
        assertEquals(4, c.unanswerableItems)
        assertEquals(12, c.unanswerableCalls)
        assertNotNull(c.invented.lo)
        assertTrue(c.invented.lo!! <= c.invented.value!! && c.invented.value!! <= c.invented.hi!!)
    }

    @Test
    fun `over-refusal and correctness on answerable items`() {
        val strict = analyzer().cell("alpha", PromptVariant.STRICT, emptySet())
        assertEquals(0.25, strict.overRefusal.value!!, 1e-9)
        assertEquals(0.0, strict.invented.value!!, 1e-9)
        assertEquals(0.75, strict.exactMatch.value!!, 1e-9)
        assertEquals(1.0, strict.exactMatchWhenAnswered!!, 1e-9)
        val neutral = analyzer().cell("alpha", PromptVariant.NEUTRAL, emptySet())
        assertEquals(0.0, neutral.overRefusal.value!!, 1e-9)
        assertEquals(0.75, neutral.exactMatch.value!!, 1e-9) // "the Paris" normalizes to "paris"
        assertEquals(0.75, neutral.exactMatchWhenAnswered!!, 1e-9)
    }

    @Test
    fun `phrase abstentions are counted separately from sentinel abstentions`() {
        val c = analyzer().cell("alpha", PromptVariant.NEUTRAL, emptySet())
        assertEquals(1, c.phraseAbstentions)
        assertEquals(3, c.sentinelAbstentions)
        assertEquals(0, c.emptyCalls)
    }

    @Test
    fun `self-consistency measures agreement of decisions and answers across repetitions`() {
        val c = analyzer().cell("alpha", PromptVariant.NEUTRAL, emptySet())
        // only u1 flips (1 of 3 pairs agree... abstain, answer, answer -> pairs: (a,b)=no,(a,c)=no,(b,c)=yes => 1/3)
        assertEquals((7 * 1.0 + 1.0 / 3) / 8, c.consistency.decisionAgreement!!, 1e-9)
        assertEquals(7.0 / 8, c.consistency.fullyConsistentItems!!, 1e-9)
        assertEquals(1.0, c.consistency.decisionAgreementAnswerable!!, 1e-9)
        assertEquals((3 * 1.0 + 1.0 / 3) / 4, c.consistency.decisionAgreementUnanswerable!!, 1e-9)
        assertEquals(1.0, c.consistency.answerAgreement!!, 1e-9)
    }

    @Test
    fun `strict versus neutral trade-off is computed pairwise per item`() {
        val t = analyzer().tradeoff("alpha", emptySet())
        assertEquals(0.0 - (2.0 / 3 + 1 + 1) / 4 * 1.0, t.deltaInvented.value!!, 1e-9)
        assertEquals(0.25, t.deltaOverRefusal.value!!, 1e-9)
        assertEquals(0.0, t.deltaExactMatch.value!!, 1e-9)
        assertTrue(t.inventedAvoidedPerExtraRefusal!! > 1.0)
        // one refusal-prone item out of four is too little evidence for a clear over-refusal effect
        assertEquals("invention-down-without-clear-over-refusal", t.pattern)
    }

    @Test
    fun `excluding disputed items recomputes every metric without them`() {
        val all = analyzer().scope("all", emptySet())
        val without = analyzer().scope("excludingDisputed", setOf("u0", "a3"))
        assertEquals(0, all.excludedItems)
        assertEquals(2, without.excludedItems)
        assertEquals(3, without.unanswerableItems)
        assertEquals(3, without.answerableItems)
        val n = without.cells.first { it.prompt == "neutral" }
        assertEquals(1.0, n.exactMatch.value!!, 1e-9) // the wrong answer a3 is gone: 3/3
        assertEquals((2.0 / 3 + 1 + 1) / 3, n.invented.value!!, 1e-9) // u0, the only abstention, is gone
        assertEquals(9, n.unanswerableCalls)
        assertTrue(all.cells.first { it.prompt == "neutral" }.invented.value!! < n.invented.value!!)
    }

    @Test
    fun `unknown disputed ids are ignored in a scope but rejected by the pipeline`() {
        val scope = analyzer().scope("x", setOf("does-not-exist"))
        assertEquals(0, scope.excludedItems)
    }

    @Test
    fun `bootstrap intervals are deterministic for a fixed seed`() {
        val a = analyzer().cell("alpha", PromptVariant.NEUTRAL, emptySet())
        val b = analyzer().cell("alpha", PromptVariant.NEUTRAL, emptySet())
        assertEquals(a.invented, b.invented)
    }

    @Test
    fun `failed calls and partial data do not break the metrics`() {
        val partial = calls().filter { !(it.itemId == "u2" && it.rep == 3) } +
            CallRecord("u3", Kind.UNANSWERABLE, "alpha", "test/alpha", PromptVariant.NEUTRAL, 3, "error", "t", 0.7, 10, error = "boom")
        val c = Analyzer(items, partial, listOf("alpha"), 100, 1).cell("alpha", PromptVariant.NEUTRAL, emptySet())
        assertEquals(11, c.unanswerableCalls)
        val none = Analyzer(items, emptyList(), listOf("alpha"), 100, 1).cell("alpha", PromptVariant.NEUTRAL, emptySet())
        assertNull(none.invented.value)
        assertNull(none.consistency.decisionAgreement)
    }

    @Test
    fun `empty replies are reported and are neither inventions nor refusals`() {
        val withEmpty = calls().map { if (it.itemId == "u2" && it.prompt == PromptVariant.NEUTRAL) it.copy(content = "") else it }
        val c = Analyzer(items, withEmpty, listOf("alpha"), 100, 1).cell("alpha", PromptVariant.NEUTRAL, emptySet())
        assertEquals(3, c.emptyCalls)
        assertEquals((0 + 2.0 / 3 + 0 + 1) / 4, c.invented.value!!, 1e-9)
    }

    @Test
    fun `review candidates flag consensus inventions under the strict prompt and hedged replies`() {
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
        val candidates = Analyzer(items, rows, models, 50, 1).reviewCandidates().associateBy { it.id }
        assertTrue(candidates.getValue("u0").reasons.single().contains("strict prompt"))
        assertTrue(candidates.getValue("a0").reasons.single().contains("gold answer may be wrong"))
        assertTrue(candidates.getValue("u2").reasons.single().contains("mix an answer with refusal wording"))
        assertTrue("u1" !in candidates && "a1" !in candidates)
    }

    @Test
    fun `item views carry raw text only when it differs from the standard form`() {
        val views = analyzer().itemViews(mapOf("u0" to DisputedItem("u0", "looks answerable")))
        val u0 = views.first { it.id == "u0" }
        assertTrue(u0.disputed)
        assertEquals("looks answerable", u0.disputedReason)
        assertTrue(u0.responses.all { it.raw == null })
        val u1 = views.first { it.id == "u1" }
        assertEquals("ANSWER: The context does not say.", u1.responses.first { it.prompt == "neutral" && it.rep == 1 }.raw)
    }
}
