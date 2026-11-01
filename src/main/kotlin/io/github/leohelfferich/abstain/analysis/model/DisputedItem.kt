package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/** An item a reviewer has decided should not be in the primary numbers. */
@Serializable
data class DisputedItem(val id: String, val reason: String = "", val reviewer: String? = null)
