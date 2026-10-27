package io.github.leohelfferich.abstain

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SamplerTest {
    private fun paragraphs(count: Int, withUnanswerable: (Int) -> Boolean = { true }) = (0 until count).map { i ->
        val qs = buildList {
            repeat(2) { add(SquadQuestion("p$i-a$it", "Q a$it of $i", false, listOf("ans$it"), emptyList())) }
            if (withUnanswerable(i)) repeat(2) { add(SquadQuestion("p$i-u$it", "Q u$it of $i", true, emptyList(), listOf("plausible"))) }
        }
        SquadParagraph(i / 5, "Article ${i / 5}", i % 5, "Context $i", qs)
    }

    @Test
    fun `pairs share a passage and passages are not reused`() {
        val items = Sampler.sample(paragraphs(40), seed = 1, answerable = 10, unanswerable = 10)
        assertEquals(20, items.size)
        assertEquals(10, items.count { it.kind == Kind.ANSWERABLE })
        assertEquals(10, items.count { it.kind == Kind.UNANSWERABLE })
        items.groupBy { it.pairIndex }.values.forEach { pair ->
            assertEquals(2, pair.size)
            assertEquals(1, pair.map { it.context }.distinct().size)
            assertEquals(setOf(Kind.ANSWERABLE, Kind.UNANSWERABLE), pair.map { it.kind }.toSet())
        }
        assertEquals(10, items.map { it.context }.distinct().size)
        assertEquals(20, items.map { it.id }.distinct().size)
    }

    @Test
    fun `same seed gives identical sample and a different seed a different one`() {
        val p = paragraphs(60)
        val a = Sampler.sample(p, 5, 10, 10)
        assertEquals(a, Sampler.sample(p, 5, 10, 10))
        assertNotEquals(a.map { it.id }, Sampler.sample(p, 6, 10, 10).map { it.id })
    }

    @Test
    fun `falls back to unpaired draws when too few paragraphs have both kinds`() {
        val p = paragraphs(30) { it < 4 }
        val items = Sampler.sample(p, 3, answerable = 8, unanswerable = 4)
        assertEquals(8, items.count { it.kind == Kind.ANSWERABLE })
        assertEquals(4, items.count { it.kind == Kind.UNANSWERABLE })
        assertEquals(4, items.groupBy { it.pairIndex }.values.count { it.size == 2 })
    }

    @Test
    fun `fails loudly when the data cannot satisfy the request`() {
        assertFailsWith<IllegalStateException> { Sampler.sample(paragraphs(3), 1, 10, 10) }
    }

    @Test
    fun `dry-run subset takes the first pairs so passages stay shared`() {
        val sample = SampleFile(1, "x", SquadSource.source, 10, 10, Sampler.sample(paragraphs(30), 1, 10, 10))
        val subset = Sampler.dryRunSubset(sample, 5, 5)
        assertEquals(10, subset.size)
        assertEquals(5, subset.count { it.kind == Kind.ANSWERABLE })
        assertEquals(setOf(0, 1, 2, 3, 4), subset.map { it.pairIndex }.toSet())
    }

    @Test
    fun `parser reads the SQuAD 2 structure`() {
        val json = """{"version":"v2.0","data":[{"title":"T","paragraphs":[{"context":"C","qas":[
            {"question":"q1","id":"1","answers":[{"text":"x","answer_start":0},{"text":"x","answer_start":0}],"is_impossible":false},
            {"question":"q2","id":"2","answers":[],"plausible_answers":[{"text":"y","answer_start":1}],"is_impossible":true}]}]}]}"""
        val ps = SquadParser.parse(json)
        assertEquals(1, ps.size)
        assertEquals(listOf("x"), ps[0].questions[0].answers)
        assertTrue(ps[0].questions[1].isImpossible)
        assertEquals(listOf("y"), ps[0].questions[1].plausibleAnswers)
    }

    @Test
    fun `committed sample has 100 + 100 paired items from the pinned dataset`() {
        val sample = tempWorkspaceSample()
        assertEquals(100, sample.items.count { it.kind == Kind.ANSWERABLE })
        assertEquals(100, sample.items.count { it.kind == Kind.UNANSWERABLE })
        assertEquals(SquadSource.SHA256, sample.source.sha256)
        assertEquals(100, sample.items.map { it.context }.distinct().size)
        sample.items.groupBy { it.pairIndex }.values.forEach { assertEquals(1, it.map { i -> i.context }.distinct().size) }
        assertTrue(sample.items.filter { it.kind == Kind.ANSWERABLE }.all { it.answers.isNotEmpty() })
        assertTrue(sample.items.filter { it.kind == Kind.UNANSWERABLE }.all { it.answers.isEmpty() })
    }

    @Test
    fun `committed sample is reproduced exactly from the raw dataset when it is available locally`() {
        val raw = repoRoot.resolve("data/raw/dev-v2.0.json")
        assumeTrue(Files.exists(raw), "raw dataset not downloaded; run fetch-data to enable this check")
        SquadSource.verifyChecksum(raw)
        val config = Workspace(repoRoot).loadConfig()
        val fresh = Sampler.buildSampleFile(config, SquadParser.parse(Files.readString(raw)))
        assertEquals(fresh, Workspace(repoRoot).loadSample())
    }

    private fun tempWorkspaceSample() = Workspace(repoRoot).loadSample()
}
