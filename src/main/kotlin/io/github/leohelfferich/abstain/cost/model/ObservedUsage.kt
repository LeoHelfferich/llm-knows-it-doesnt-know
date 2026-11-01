package io.github.leohelfferich.abstain.cost.model

/** Per-model token behaviour measured in an earlier (dry) run; replaces the built-in assumptions. */
data class ObservedUsage(val promptTokensPerChar: Double, val completionTokensPerCall: Double, val sampleCalls: Int)
