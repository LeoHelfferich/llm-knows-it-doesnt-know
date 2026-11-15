package io.github.leohelfferich.abstain.analysis

import io.github.leohelfferich.abstain.analysis.model.ServingProvider
import io.github.leohelfferich.abstain.execution.CallLog
import io.github.leohelfferich.abstain.execution.model.CallRecord

/** Facts the result pages quote, counted from the call log. */
object RunFacts {
    private val httpStatus = Regex("""HTTP\s+(\d{3})""")

    /**
     * Successful calls per model and serving provider, in model order, then by count.
     * A failed attempt has no provider and is not a served call.
     */
    fun servingProviders(calls: List<CallRecord>, modelOrder: List<String>): List<ServingProvider> {
        val rank = modelOrder.withIndex().associate { it.value to it.index }
        return CallLog.okByKey(calls).values
            .groupBy { it.model to (it.provider ?: "unreported") }
            .map { (key, rows) -> ServingProvider(key.first, key.second, rows.size) }
            .sortedWith(compareBy<ServingProvider> { rank[it.model] ?: Int.MAX_VALUE }.thenByDescending { it.calls }.thenBy { it.provider })
    }

    /** Failed attempts grouped by HTTP status. Key `other` is an attempt whose error named no status. */
    fun failedAttemptCounts(calls: List<CallRecord>): Map<String, Int> {
        val counts = sortedMapOf<String, Int>()
        for (call in calls) {
            if (call.status == "ok") continue
            val code = httpStatus.find(call.error.orEmpty())?.groupValues?.get(1) ?: "other"
            counts[code] = (counts[code] ?: 0) + 1
        }
        return counts
    }
}
