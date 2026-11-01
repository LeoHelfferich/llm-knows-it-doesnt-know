package io.github.leohelfferich.abstain.execution

import io.github.leohelfferich.abstain.prompt.model.PromptVariant

/** Identity of a planned call. A successful record with this key is never paid for again. */
fun callKey(itemId: String, model: String, prompt: PromptVariant, rep: Int) = "$itemId|$model|${prompt.key}|$rep"
