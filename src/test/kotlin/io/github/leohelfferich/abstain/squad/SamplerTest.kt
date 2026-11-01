package io.github.leohelfferich.abstain.squad

import java.nio.file.Files

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

import io.github.leohelfferich.abstain.config.Workspace
import io.github.leohelfferich.abstain.testing.repoRoot
import io.github.leohelfferich.abstain.squad.model.Kind
import io.github.leohelfferich.abstain.squad.model.SampleFile
import io.github.leohelfferich.abstain.squad.model.SquadQuestion
import io.github.leohelfferich.abstain.squad.model.SquadParagraph

class SamplerTest {
    @Test
    fun `should pair questions on one passage and not reuse passages`() {
        // ---
        val items = Sampler.sample(paragraphs(40), seed = 1, answerable = 10, unanswerable = 10)
        // ---

        assertThat(items.size).isEqualTo(20)
        assertThat(items.count { it.kind == Kind.ANSWERABLE }).isEqualTo(10)
        assertThat(items.count { it.kind == Kind.UNANSWERABLE }).isEqualTo(10)
        items.groupBy { it.pairIndex }.values.forEach { pair ->
            assertThat(pair.size).isEqualTo(2)
            assertThat(pair.map { it.context }.distinct().size).isEqualTo(1)
            assertThat(pair.map { it.kind }.toSet()).isEqualTo(setOf(Kind.ANSWERABLE, Kind.UNANSWERABLE))
        }
        assertThat(items.map { it.context }.distinct().size).isEqualTo(10)
        assertThat(items.map { it.id }.distinct().size).isEqualTo(20)
    }

    @Test
    fun `should reproduce the sample for the same seed`() {
        val p = paragraphs(60)

        // ---
        val a = Sampler.sample(p, 5, 10, 10)
        // ---

        assertThat(Sampler.sample(p, 5, 10, 10)).isEqualTo(a)
        assertThat(Sampler.sample(p, 6, 10, 10).map { it.id }).isNotEqualTo(a.map { it.id })
    }

    @Test
    fun `should fall back to unpaired draws when too few paragraphs have both kinds`() {
        val p = paragraphs(30) { it < 4 }

        // ---
        val items = Sampler.sample(p, 3, answerable = 8, unanswerable = 4)
        // ---

        assertThat(items.count { it.kind == Kind.ANSWERABLE }).isEqualTo(8)
        assertThat(items.count { it.kind == Kind.UNANSWERABLE }).isEqualTo(4)
        assertThat(items.groupBy { it.pairIndex }.values.count { it.size == 2 }).isEqualTo(4)
    }

    @Test
    fun `should fail when the data cannot satisfy the request`() {
        val paragraphs = paragraphs(3)

        // ---
        val thrown = catchThrowable { Sampler.sample(paragraphs, 1, 10, 10) }
        // ---

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `should take the first pairs for the dry run`() {
        val sample = SampleFile(
            1,
            "x",
            SquadSource.source,
            10,
            10,
            Sampler.sample(paragraphs(30), 1, 10, 10)
        )

        // ---
        val subset = Sampler.dryRunSubset(sample, 5, 5)
        // ---

        assertThat(subset.size).isEqualTo(10)
        assertThat(subset.count { it.kind == Kind.ANSWERABLE }).isEqualTo(5)
        assertThat(subset.map { it.pairIndex }.toSet()).isEqualTo(setOf(0, 1, 2, 3, 4))
    }

    @Test
    fun `should read the SQuAD 2 structure`() {
        val json = """{"version":"v2.0","data":[{"title":"T","paragraphs":[{"context":"C","qas":[
            {"question":"q1","id":"1","answers":[{"text":"x","answer_start":0},{"text":"x","answer_start":0}],"is_impossible":false},
            {"question":"q2","id":"2","answers":[],"plausible_answers":[{"text":"y","answer_start":1}],"is_impossible":true}]}]}]}"""

        // ---
        val ps = SquadParser.parse(json)
        // ---

        assertThat(ps.size).isEqualTo(1)
        assertThat(ps[0].questions[0].answers).isEqualTo(listOf("x"))
        assertThat(ps[0].questions[1].isImpossible).isTrue()
        assertThat(ps[0].questions[1].plausibleAnswers).isEqualTo(listOf("y"))
    }

    @Test
    fun `should commit 100 answerable and 100 unanswerable paired items`() {
        // ---
        val sample = tempWorkspaceSample()
        // ---

        assertThat(sample.items.count { it.kind == Kind.ANSWERABLE }).isEqualTo(100)
        assertThat(sample.items.count { it.kind == Kind.UNANSWERABLE }).isEqualTo(100)
        assertThat(sample.source.sha256).isEqualTo(SquadSource.SHA256)
        assertThat(sample.items.map { it.context }.distinct().size).isEqualTo(100)
        sample.items.groupBy { it.pairIndex }.values.forEach { assertThat(it.map { i -> i.context }.distinct().size).isEqualTo(1) }
        assertThat(sample.items.filter { it.kind == Kind.ANSWERABLE }.all { it.answers.isNotEmpty() }).isTrue()
        assertThat(sample.items.filter { it.kind == Kind.UNANSWERABLE }.all { it.answers.isEmpty() }).isTrue()
    }

    @Test
    fun `should reproduce the committed sample from the raw dataset`() {
        // ---
        val raw = repoRoot.resolve("data/raw/dev-v2.0.json")
        // ---

        assumeTrue(Files.exists(raw), "raw dataset not downloaded; run fetch-data to enable this check")
        SquadSource.verifyChecksum(raw)
        val config = Workspace(repoRoot).loadConfig()
        val fresh = Sampler.buildSampleFile(config, SquadParser.parse(Files.readString(raw)))
        assertThat(Workspace(repoRoot).loadSample()).isEqualTo(fresh)
    }

    private fun tempWorkspaceSample() = Workspace(repoRoot).loadSample()

    private fun paragraphs(count: Int, withUnanswerable: (Int) -> Boolean = { true }) = (0 until count).map { i ->
        val questions = buildList {
            repeat(2) { add(SquadQuestion("p$i-a$it", "Q a$it of $i", false, listOf("ans$it"), emptyList())) }
            if (withUnanswerable(i)) repeat(2) { add(SquadQuestion("p$i-u$it", "Q u$it of $i", true, emptyList(), listOf("plausible"))) }
        }
        SquadParagraph(i / 5, "Article ${i / 5}", i % 5, "Context $i", questions)
    }
}
