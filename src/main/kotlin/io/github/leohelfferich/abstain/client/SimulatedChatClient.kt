package io.github.leohelfferich.abstain.client

import java.util.Random

import io.github.leohelfferich.abstain.config.model.ModelConfig
import io.github.leohelfferich.abstain.utils.sha256Hex
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.prompt.Prompts
import io.github.leohelfferich.abstain.squad.model.Item
import io.github.leohelfferich.abstain.squad.model.Kind
import io.github.leohelfferich.abstain.client.model.ChatRequest
import io.github.leohelfferich.abstain.client.model.ChatResult

/**
 * Offline stand-in for the API (`run --mock`). It makes NO network calls and its output is synthetic:
 * results from it must never be committed or published. Behaviour is a deterministic function of
 * (item, model, prompt, rep) so runs are repeatable.
 */
class SimulatedChatClient(items: List<Item>, private val models: List<ModelConfig>) : ChatClient {
    private val byId = items.associateBy { it.id }

    private data class Profile(val inventNeutral: Double, val inventStrict: Double, val refuseNeutral: Double, val refuseStrict: Double, val skill: Double)

    private fun profile(alias: String): Profile {
        val i = models.indexOfFirst { it.alias == alias }.coerceAtLeast(0)
        return Profile(
            inventNeutral = 0.95 - 0.08 * i, inventStrict = 0.45 - 0.07 * i,
            refuseNeutral = 0.01 + 0.01 * i, refuseStrict = 0.04 + 0.03 * i, skill = 0.9 - 0.04 * i,
        )
    }

    override fun complete(request: ChatRequest): ChatResult {
        val item = byId.getValue(requireNotNull(request.itemId))
        val variant = requireNotNull(request.variant)
        val seed = sha256Hex("${item.id}|${request.model.alias}|${variant.key}|${request.rep}").take(15).toLong(16)
        val rnd = Random(seed)
        val behavior = profile(request.model.alias)
        val strict = variant == PromptVariant.STRICT
        val content = when (item.kind) {
            Kind.UNANSWERABLE ->
                if (rnd.nextDouble() < (if (strict) behavior.inventStrict else behavior.inventNeutral)) {
                    "ANSWER: " + (item.plausibleAnswers.firstOrNull()?.takeIf { rnd.nextBoolean() } ?: "a made-up answer ${rnd.nextInt(5)}")
                } else abstention(strict)
            Kind.ANSWERABLE ->
                if (rnd.nextDouble() < (if (strict) behavior.refuseStrict else behavior.refuseNeutral)) abstention(strict)
                else if (rnd.nextDouble() < behavior.skill) "ANSWER: " + item.answers.first()
                else "ANSWER: something else ${rnd.nextInt(5)}"
        }
        val promptTokens = (request.prompt.length / 4)
        return ChatResult(content, "stop", promptTokens, 8, 0, 0.0, "simulated", request.model.openRouterId, "sim-${seed}", 1)
    }

    private fun abstention(strict: Boolean) =
        if (strict) Prompts.SENTINEL else "ANSWER: The context does not mention this."
}
