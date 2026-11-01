package io.github.leohelfferich.abstain.cost

import kotlin.math.ceil

import io.github.leohelfferich.abstain.config.HARD_CAP_EUR
import io.github.leohelfferich.abstain.pricing.model.SnapshotModel

/**
 * Enforces the hard cap while calls are in flight.
 *
 * Before every call the worst case for that call is reserved. The call is refused when spent plus
 * reserved plus that worst case would exceed the usable budget (cap minus the safety margin).
 * Thread-safe.
 */
class CostGuard(capEur: Double, safetyMarginEur: Double, private val eurPerUsd: Double, alreadySpentEur: Double) {
    private val budgetUsd = (capEur - safetyMarginEur) / eurPerUsd
    private var spentUsd = alreadySpentEur / eurPerUsd
    private var reservedUsd = 0.0

    init {
        require(capEur <= HARD_CAP_EUR) { "cap above the hard cap of EUR ${HARD_CAP_EUR}" }
    }

    @Synchronized
    fun tryReserve(worstCaseUsd: Double): Boolean {
        if (spentUsd + reservedUsd + worstCaseUsd > budgetUsd) return false
        reservedUsd += worstCaseUsd
        return true
    }

    @Synchronized
    fun settle(reservedWorstCaseUsd: Double, actualUsd: Double) {
        reservedUsd -= reservedWorstCaseUsd
        spentUsd += actualUsd
    }

    @Synchronized
    fun spentEur(): Double = spentUsd * eurPerUsd

    @Synchronized
    fun remainingEur(): Double = (budgetUsd - spentUsd - reservedUsd) * eurPerUsd

    companion object {
        fun worstCaseUsd(prompt: String, maxTokens: Int, price: SnapshotModel): Double =
            ceil(prompt.length / 2.0) * price.promptPrice + maxTokens * price.completionPrice
    }
}
