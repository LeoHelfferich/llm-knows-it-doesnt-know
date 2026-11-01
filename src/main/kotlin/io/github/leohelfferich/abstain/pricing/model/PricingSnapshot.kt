package io.github.leohelfferich.abstain.pricing.model

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import io.github.leohelfferich.abstain.utils.CompactJson
import io.github.leohelfferich.abstain.utils.PrettyJson

/**
 * OpenRouter's public model list, pinned in the repo so estimates do not change under us.
 *
 * `estimate --refresh` rewrites it. No API key is required; the list is public.
 */
@Serializable
data class PricingSnapshot(
    val retrievedOn: String,
    val source: String,
    val note: String,
    val models: List<SnapshotModel>,
) {
    fun model(id: String): SnapshotModel {
        val found = models.firstOrNull { it.id == id } ?: error("Model '$id' not found in pricing snapshot; run `estimate --refresh`")
        found.requireBillable()
        return found
    }

    companion object {
        const val SOURCE = "https://openrouter.ai/api/v1/models"
        const val NOTE = "Public OpenRouter model list (no API key needed). Prices are USD per token."

        fun load(path: Path): PricingSnapshot =
            PrettyJson.decodeFromString(serializer(), Files.readString(path))

        fun fromModelList(json: String, ids: List<String>, retrievedOn: String): PricingSnapshot {
            val data = CompactJson.parseToJsonElement(json).jsonObject.getValue("data").jsonArray
            val byId = data.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
            val models = ids.map { id ->
                val m = byId[id] ?: error("OpenRouter does not list model id '$id'")
                val pricing = m.getValue("pricing").jsonObject
                SnapshotModel(
                    id = id,
                    canonicalSlug = m["canonical_slug"]?.jsonPrimitive?.contentOrNull,
                    name = m.getValue("name").jsonPrimitive.content,
                    promptUsdPerToken = pricing.getValue("prompt").jsonPrimitive.content,
                    completionUsdPerToken = pricing.getValue("completion").jsonPrimitive.content,
                    internalReasoningUsdPerToken = pricing["internal_reasoning"]?.jsonPrimitive?.contentOrNull,
                    contextLength = m["context_length"]?.jsonPrimitive?.intOrNull,
                    supportedParameters = (m["supported_parameters"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty().sorted(),
                )
            }
            return PricingSnapshot(retrievedOn, SOURCE, NOTE, models)
        }

        fun fetchModelList(): String {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            val response = client.send(HttpRequest.newBuilder(URI.create(SOURCE)).GET().build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "OpenRouter model list: HTTP ${response.statusCode()}" }
            return response.body()
        }
    }
}
