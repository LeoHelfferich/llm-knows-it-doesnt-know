package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/** The review queue written next to a run. */
@Serializable
data class ReviewFile(val runId: String, val howToUse: String, val candidates: List<ReviewCandidate>)
