package io.github.leohelfferich.abstain.squad

import kotlinx.serialization.json.*
import io.github.leohelfferich.abstain.squad.model.SquadQuestion
import io.github.leohelfferich.abstain.squad.model.SquadParagraph

/**
 * Reads `dev-v2.0.json` into paragraphs. Answer texts are de-duplicated, file order is kept.
 *
 * ```
 * { "data": [ { "title", "paragraphs": [ { "context", "qas": [ question ] } ] } ] }
 *
 * question
 *   id, question
 *   is_impossible              missing is treated as false
 *   answers[].text             gold spans
 *   plausible_answers[].text   what crowdworkers thought looked answerable
 * ```
 *
 * Article index and paragraph index are the positions in that file, not a separate id.
 */
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
