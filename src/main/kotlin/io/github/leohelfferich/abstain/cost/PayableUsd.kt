package io.github.leohelfferich.abstain.cost

/**
 * Resolves the USD amount that counts against the cap, and the label stored as `costSource`.
 *
 * A negative or non-finite reported cost would lower the spent total and defeat the cap, so it is
 * ignored. The reported cost is used when it is usable, otherwise the cost computed from token
 * prices, otherwise the reserved worst case.
 *
 * ```
 * reported finite and >= 0 --> (reported, "reported")
 * else computed usable     --> (computed, "computed")
 * else                     --> (worst coerced to >= 0, "worst-case")
 * ```
 */
fun payableUsd(reported: Double?, computed: Double?, worst: Double): Pair<Double, String> {
    val safeWorst = worst.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val reportedOk = usableCost(reported)
    val computedOk = usableCost(computed)
    return when {
        reportedOk != null -> reportedOk to "reported"
        computedOk != null -> computedOk to "computed"
        else -> safeWorst to "worst-case"
    }
}

/** A negative or non-finite amount would shrink the spent total and defeat the cap. */
private fun usableCost(value: Double?) = value?.takeIf { it.isFinite() && it >= 0.0 }
