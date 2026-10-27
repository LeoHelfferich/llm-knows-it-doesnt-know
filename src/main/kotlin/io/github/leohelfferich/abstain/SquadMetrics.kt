package io.github.leohelfferich.abstain

/** Re-implementation of the official SQuAD v1.1/v2.0 evaluation normalization, EM and token F1. */
object SquadMetrics {
    private const val ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private val articles = Regex("\\b(a|an|the)\\b")

    fun normalize(text: String): String {
        val lower = text.lowercase()
        val noPunct = lower.filter { it !in ASCII_PUNCTUATION }
        val noArticles = articles.replace(noPunct, " ")
        return noArticles.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun tokens(text: String): List<String> = normalize(text).split(" ").filter { it.isNotEmpty() }

    fun exactMatch(prediction: String, golds: List<String>): Double {
        val p = normalize(prediction)
        return if (golds.any { normalize(it) == p }) 1.0 else 0.0
    }

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

    fun maxF1(prediction: String, golds: List<String>): Double = golds.maxOfOrNull { f1(prediction, it) } ?: 0.0
}
