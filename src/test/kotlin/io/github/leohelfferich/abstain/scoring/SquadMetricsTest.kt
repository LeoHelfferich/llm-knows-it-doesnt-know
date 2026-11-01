package io.github.leohelfferich.abstain.scoring

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.util.Locale

class SquadMetricsTest {
    @Test
    fun `should normalize case, punctuation, articles, and whitespace`() {
        val titled = "The  Cat's   Hat!"
        val articlesOnly = "a an the"
        val dottedAcronym = "U.S.A."

        // ---
        val titledNormalized = SquadMetrics.normalize(titled)
        val articlesNormalized = SquadMetrics.normalize(articlesOnly)
        val acronymNormalized = SquadMetrics.normalize(dottedAcronym)
        // ---

        assertThat(titledNormalized).isEqualTo("cats hat")
        assertThat(articlesNormalized).isEqualTo("")
        assertThat(acronymNormalized).isEqualTo("usa")
    }

    @Test
    fun `should normalize independently of the default locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            val input = "I"

            // ---
            val normalized = SquadMetrics.normalize(input)
            // ---

            assertThat(normalized).isEqualTo("i")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `should count an exact match against any gold answer`() {
        val golds = listOf("Vikings", "Normans")

        // ---
        val match = SquadMetrics.exactMatch("the Normans", golds)
        val miss = SquadMetrics.exactMatch("Norman", golds)
        // ---

        assertThat(match).isEqualTo(1.0)
        assertThat(miss).isEqualTo(0.0)
    }

    @Test
    fun `should score token overlap with multiset F1`() {
        val overlapping = "the quick brown fox" to "quick fox"
        val identical = "Paris" to "paris."
        val disjoint = "London" to "Paris"
        val repeated = "new new york" to "new york"

        // ---
        val overlapScore = SquadMetrics.f1(overlapping.first, overlapping.second)
        val identicalScore = SquadMetrics.f1(identical.first, identical.second)
        val disjointScore = SquadMetrics.f1(disjoint.first, disjoint.second)
        val repeatedScore = SquadMetrics.f1(repeated.first, repeated.second)
        // ---

        assertThat(overlapScore).isCloseTo(0.8, within(1e-9))
        assertThat(identicalScore).isCloseTo(1.0, within(1e-9))
        assertThat(disjointScore).isCloseTo(0.0, within(1e-9))
        assertThat(repeatedScore).isCloseTo(0.8, within(1e-9))
    }

    @Test
    fun `should score two empty answers as a perfect F1`() {
        val articlesOnly = "the"
        val realAnswer = "paris"

        // ---
        val bothEmpty = SquadMetrics.f1("", articlesOnly)
        val predictionEmpty = SquadMetrics.f1("", realAnswer)
        // ---

        assertThat(bothEmpty).isEqualTo(1.0)
        assertThat(predictionEmpty).isEqualTo(0.0)
    }

}
