package io.github.leohelfferich.abstain.execution.model

import kotlinx.serialization.Serializable

/** Prompt version and template hash recorded in the manifest, so a later edit is visible. */
@Serializable
data class PromptInfo(val version: Int, val sha256: String)
