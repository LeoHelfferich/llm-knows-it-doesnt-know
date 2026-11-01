package io.github.leohelfferich.abstain.execution.model

/** Summary handed back to the CLI: status, why it stopped, and what was spent. */
data class RunSummary(val status: String, val stopReason: String?, val okCalls: Int, val errorCalls: Int, val spentEur: Double)
