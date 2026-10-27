package io.github.leohelfferich.abstain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

object SquadSource {
    const val NAME = "SQuAD 2.0 dev set (dev-v2.0.json)"
    const val URL = "https://rajpurkar.github.io/SQuAD-explorer/dataset/dev-v2.0.json"
    const val SHA256 = "80a5225e94905956a6446d296ca1093975c4d3b3260f1d6c8f68bc2ab77182d8"
    const val LICENSE = "CC BY-SA 4.0"
    val source get() = SampleSource(NAME, URL, SHA256, LICENSE)

    fun download(target: Path) {
        Files.createDirectories(target.parent)
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val response = client.send(
            HttpRequest.newBuilder(URI.create(URL)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        check(response.statusCode() == 200) { "Download failed: HTTP ${response.statusCode()}" }
        Files.write(target, response.body())
    }

    fun verifyChecksum(file: Path) {
        val actual = sha256Hex(file)
        check(actual == SHA256) { "Checksum mismatch for $file: expected $SHA256 but got $actual" }
    }
}

data class SquadQuestion(
    val id: String,
    val question: String,
    val isImpossible: Boolean,
    val answers: List<String>,
    val plausibleAnswers: List<String>,
)

data class SquadParagraph(
    val articleIndex: Int,
    val article: String,
    val paragraphIndex: Int,
    val context: String,
    val questions: List<SquadQuestion>,
)

object SquadParser {
    fun parse(json: String): List<SquadParagraph> {
        val root = Json.parseToJsonElement(json).jsonObject
        val out = mutableListOf<SquadParagraph>()
        root.getValue("data").jsonArray.forEachIndexed { ai, article ->
            val title = article.jsonObject.getValue("title").jsonPrimitive.content
            article.jsonObject.getValue("paragraphs").jsonArray.forEachIndexed { pi, paragraph ->
                val p = paragraph.jsonObject
                val questions = p.getValue("qas").jsonArray.map { qa ->
                    val q = qa.jsonObject
                    SquadQuestion(
                        id = q.getValue("id").jsonPrimitive.content,
                        question = q.getValue("question").jsonPrimitive.content,
                        isImpossible = q["is_impossible"]?.jsonPrimitive?.boolean ?: false,
                        answers = texts(q["answers"]),
                        plausibleAnswers = texts(q["plausible_answers"]),
                    )
                }
                out += SquadParagraph(ai, title, pi, p.getValue("context").jsonPrimitive.content, questions)
            }
        }
        return out
    }

    private fun texts(element: kotlinx.serialization.json.JsonElement?): List<String> =
        (element as? JsonArray)?.map { (it as JsonObject).getValue("text").jsonPrimitive.content }?.distinct().orEmpty()
}

/**
 * Seeded sampler. Algorithm (also documented in the README):
 *  1. Keep paragraphs in file order. Eligible = at least one answerable and one unanswerable question.
 *  2. Shuffle the eligible paragraphs with a Fisher-Yates shuffle driven by java.util.Random(seed)
 *     (a specified LCG, so the result is identical on every JVM).
 *  3. For the first N paragraphs, draw one answerable and one unanswerable question (each from its own
 *     Fisher-Yates shuffle of that paragraph's questions in file order, using the same Random stream).
 *     Every pair therefore shares one passage and no passage is used twice.
 *  4. If the two requested counts differ or there are too few eligible paragraphs, the remainder is
 *     taken one question per unused paragraph from a second shuffle of the remaining paragraphs
 *     (not used for the committed 100 + 100 sample, where every draw is a pair).
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

    fun sample(paragraphs: List<SquadParagraph>, seed: Long, answerable: Int, unanswerable: Int): List<Item> {
        val rnd = Random(seed)
        val eligible = shuffled(paragraphs.filter { p -> p.questions.any { !it.isImpossible } && p.questions.any { it.isImpossible } }, rnd)
        val pairs = minOf(answerable, unanswerable, eligible.size)
        val items = mutableListOf<Item>()

        fun toItem(p: SquadParagraph, q: SquadQuestion, pairIndex: Int) = Item(
            id = q.id,
            kind = if (q.isImpossible) Kind.UNANSWERABLE else Kind.ANSWERABLE,
            pairIndex = pairIndex,
            article = p.article,
            paragraphIndex = p.paragraphIndex,
            context = p.context,
            question = q.question,
            answers = q.answers,
            plausibleAnswers = q.plausibleAnswers,
        )

        for (i in 0 until pairs) {
            val p = eligible[i]
            val a = shuffled(p.questions.filter { !it.isImpossible }, rnd).first()
            val u = shuffled(p.questions.filter { it.isImpossible }, rnd).first()
            items += toItem(p, a, i)
            items += toItem(p, u, i)
        }

        var pairIndex = pairs
        val paired = eligible.take(pairs).toSet()
        val rest by lazy { shuffled(paragraphs.filter { it !in paired }, rnd) }
        var cursor = 0
        fun fill(missing: Int, wantImpossible: Boolean) {
            var remaining = missing
            cursor = 0
            val taken = items.map { it.id }.toMutableSet()
            while (remaining > 0) {
                val p = rest.getOrNull(cursor++) ?: error("Not enough paragraphs to draw $missing more ${if (wantImpossible) "unanswerable" else "answerable"} questions")
                val q = shuffled(p.questions.filter { it.isImpossible == wantImpossible && it.id !in taken }, rnd).firstOrNull() ?: continue
                items += toItem(p, q, pairIndex++)
                taken += q.id
                remaining--
            }
        }
        fill(answerable - pairs, wantImpossible = false)
        fill(unanswerable - pairs, wantImpossible = true)
        check(items.map { it.id }.toSet().size == items.size) { "Duplicate question ids in sample" }
        return items
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
