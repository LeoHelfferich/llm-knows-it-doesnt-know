package io.github.leohelfferich.abstain.analysis.model

import kotlinx.serialization.Serializable

/** A point estimate and its bootstrap percentile interval. Missing bounds mean the interval was not computed. */
@Serializable
data class Interval(val value: Double?, val lo: Double?, val hi: Double?)
