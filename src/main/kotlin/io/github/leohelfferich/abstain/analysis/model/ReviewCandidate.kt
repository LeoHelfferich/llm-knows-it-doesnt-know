package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

import io.github.leohelfferich.abstain.squad.model.Kind

/** An item the heuristics want a person to look at. Nothing is excluded automatically. */
@Serializable
data class ReviewCandidate(val id: String, val kind: Kind, val question: String, val reasons: List<String>)
