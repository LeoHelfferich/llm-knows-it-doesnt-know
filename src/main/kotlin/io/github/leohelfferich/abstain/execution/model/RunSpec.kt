package io.github.leohelfferich.abstain.execution.model

import java.nio.file.Path

import io.github.leohelfferich.abstain.squad.model.Item

/** What one invocation of the runner should execute. */
data class RunSpec(
    val runId: String,
    val kind: String,
    val items: List<Item>,
    val reps: Int,
    val dir: Path,
    val mock: Boolean,
)
