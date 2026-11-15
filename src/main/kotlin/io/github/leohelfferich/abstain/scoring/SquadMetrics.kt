package io.github.leohelfferich.abstain.scoring

import java.util.Locale

/**
 * Re-implementation of the official SQuAD v1.1/v2.0 evaluation normalization, EM and token F1.
 *
 * ```
 * normalize
 *   lowercase with Locale.ROOT          (default locale turns "I" into "ı" under Turkish)
 *   drop ASCII punctuation
 *   drop the articles a / an / the
 *   collapse whitespace
 *
 * exactMatch = 1 when the normalized prediction equals any normalized gold
 *
 * token F1, against one gold
 *   common    = size of the token-multiset overlap
 *   precision = common / |prediction|
 *   recall    = common / |gold|
 *   F1        = 2PR / (P + R)
 *   both empty is 1; one side empty is 0
 *
 * maxF1 = the best gold, so extra annotations do not punish a correct span
 * ```
 */
object SquadMetrics {
    private const val ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private val articles = Regex("\\b(a|an|the)\\b")

    fun normalize(text: String): String {
        // Locale.ROOT matches Python str.lower(); the default locale turns "I" into "ı" under Turkish.
        val lower = text.lowercase(Locale.ROOT)
        val noPunct = lower.filter { it !in ASCII_PUNCTUATION }
        val noArticles = articles.replace(noPunct, " ")
        return noArticles.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun tokens(text: String): List<String> = normalize(text).split(" ").filter { it.isNotEmpty() }

    /** 1 when [prediction] matches any gold after [normalize], else 0. */
    fun exactMatch(prediction: String, golds: List<String>): Double {
        val p = normalize(prediction)
        return if (golds.any { normalize(it) == p }) 1.0 else 0.0
    }

    /** Token F1 of [prediction] against one [gold], after [normalize]. */
    fun f1(prediction: String, gold: String): Double {
        val p = tokens(prediction)
        val g = tokens(gold)
        if (p.isEmpty() || g.isEmpty()) return if (p == g) 1.0 else 0.0
        val gCounts = g.groupingBy { it }.eachCount()
        var common = 0
        p.groupingBy { it }.eachCount().forEach { (token, count) -> common += minOf(count, gCounts[token] ?: 0) }
        if (common == 0) return 0.0
        val precision = common.toDouble() / p.size
        val recall = common.toDouble() / g.size
        return 2 * precision * recall / (precision + recall)
    }

    /** Best [f1] against [golds]. No golds is 0. */
    fun maxF1(prediction: String, golds: List<String>): Double = golds.maxOfOrNull { f1(prediction, it) } ?: 0.0
}
