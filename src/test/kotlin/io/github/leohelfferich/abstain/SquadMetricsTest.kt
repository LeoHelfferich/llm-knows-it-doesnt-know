package io.github.leohelfferich.abstain

import kotlin.test.Test
import kotlin.test.assertEquals

class SquadMetricsTest {
    @Test
    fun `normalization lowercases, strips punctuation and articles, collapses whitespace`() {
        assertEquals("cats hat", SquadMetrics.normalize("The  Cat's   Hat!"))
        assertEquals("", SquadMetrics.normalize("a an the"))
        assertEquals("usa", SquadMetrics.normalize("U.S.A."))
    }

    @Test
    fun `exact match uses any gold answer after normalization`() {
        assertEquals(1.0, SquadMetrics.exactMatch("the Normans", listOf("Vikings", "Normans")))
        assertEquals(0.0, SquadMetrics.exactMatch("Norman", listOf("Normans")))
    }

    @Test
    fun `f1 is token overlap with multiset semantics`() {
        assertEquals(0.8, SquadMetrics.f1("the quick brown fox", "quick fox"), 1e-9)
        assertEquals(1.0, SquadMetrics.f1("Paris", "paris."), 1e-9)
        assertEquals(0.0, SquadMetrics.f1("London", "Paris"), 1e-9)
        assertEquals(0.8, SquadMetrics.f1("new new york", "new york"), 1e-9)
    }

    @Test
    fun `f1 of two empty strings is one and of one empty string is zero`() {
        assertEquals(1.0, SquadMetrics.f1("", "the"))
        assertEquals(0.0, SquadMetrics.f1("", "paris"))
    }

    @Test
    fun `max f1 takes the best gold answer`() {
        assertEquals(1.0, SquadMetrics.maxF1("seismic waves", listOf("waves", "seismic waves")), 1e-9)
        assertEquals(0.0, SquadMetrics.maxF1("x", emptyList()))
    }
}
