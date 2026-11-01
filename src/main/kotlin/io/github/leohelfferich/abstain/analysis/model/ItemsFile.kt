package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/** All item views for a run. The item explorer loads this file. */
@Serializable
data class ItemsFile(val runId: String, val items: List<ItemView>)
