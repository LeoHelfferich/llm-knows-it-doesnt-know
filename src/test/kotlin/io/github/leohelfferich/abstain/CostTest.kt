package io.github.leohelfferich.abstain

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CostTest {
    @Test
    fun `estimate multiplies calls and prices`() {
        val config = testConfig(reps = 3).copy(maxTokens = 1000)
        val items = testItems(2)
        val e = Estimator.estimate("t", items, 3, config, testSnapshot())
        assertEquals(4 * 2 * 3, e.models[0].calls)
        assertEquals(4 * 2 * 3 * 2, e.totalCalls)
        val alpha = e.models[0]
        // alpha: 10 answer tokens + 100 assumed reasoning tokens
        assertEquals(110.0, alpha.expectedOutputTokensPerCall)
        assertEquals(alpha.calls * (alpha.inputTokensPerCall * 1e-6 + 110 * 2e-6), alpha.expectedUsd, 1e-12)
        assertEquals(330.0, alpha.pessimisticOutputTokensPerCall)
        assertTrue(e.ceilingUsd > e.pessimisticUsd && e.pessimisticUsd > e.expectedUsd)
        assertEquals(e.models.sumOf { it.expectedUsd }, e.expectedUsd, 1e-12)
    }

    @Test
    fun `estimate for the real config covers 6000 calls and stays under the cap in the expected case`() {
        val ws = Workspace(repoRoot)
        val config = ws.loadConfig()
        val e = Estimator.estimate("full", ws.loadSample().items, config.reps, config, PricingSnapshot.load(ws.snapshotFile))
        assertEquals(6000, e.totalCalls)
        assertEquals(5, e.models.size)
        assertTrue(e.expectedFitsCap, "expected cost ${e.expectedEur} must fit the cap")
        assertTrue(e.expectedEur > 0.5)
    }

    @Test
    fun `observed usage replaces the assumptions`() {
        val config = testConfig()
        val records = listOf(
            CallRecord("a0", Kind.ANSWERABLE, "alpha", "test/alpha", PromptVariant.NEUTRAL, 1, "ok", "t", 0.7, 400, "x", promptTokens = 100, completionTokens = 40, costUsd = 0.1),
            CallRecord("a1", Kind.ANSWERABLE, "alpha", "test/alpha", PromptVariant.STRICT, 1, "ok", "t", 0.7, 400, "x", promptTokens = 100, completionTokens = 60, costUsd = 0.1),
            CallRecord("a1", Kind.ANSWERABLE, "alpha", "test/alpha", PromptVariant.STRICT, 1, "error", "t", 0.7, 400, error = "boom"),
        )
        val observed = Estimator.observedFrom(records)
        assertEquals(setOf("alpha"), observed.keys)
        assertEquals(50.0, observed.getValue("alpha").completionTokensPerCall)
        assertEquals(0.25, observed.getValue("alpha").promptTokensPerChar)
        val e = Estimator.estimate("t", testItems(1), 1, config, testSnapshot(), observed)
        assertEquals(50.0, e.models.first { it.alias == "alpha" }.expectedOutputTokensPerCall)
        assertEquals(10.0, e.models.first { it.alias == "beta" }.expectedOutputTokensPerCall)
    }

    @Test
    fun `guard never lets reservations plus spend exceed the budget, even under contention`() {
        val guard = CostGuard(capEur = 1.0, safetyMarginEur = 0.0, eurPerUsd = 1.0, alreadySpentEur = 0.0)
        val granted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.submit {
                repeat(100) {
                    if (guard.tryReserve(0.01)) {
                        granted.incrementAndGet()
                        guard.settle(0.01, 0.01)
                    }
                }
                done.countDown()
            }
        }
        done.await()
        pool.shutdown()
        assertTrue(granted.get() <= 100, "granted ${granted.get()}")
        assertTrue(guard.spentEur() <= 1.0 + 1e-9)
    }

    @Test
    fun `guard accounts for money already spent and for the safety margin`() {
        val guard = CostGuard(capEur = 1.0, safetyMarginEur = 0.25, eurPerUsd = 1.0, alreadySpentEur = 0.7)
        assertFalse(guard.tryReserve(0.1))
        assertTrue(guard.tryReserve(0.05))
        assertEquals(0.0, guard.remainingEur(), 1e-9)
    }

    @Test
    fun `guard converts currencies`() {
        val guard = CostGuard(capEur = 1.0, safetyMarginEur = 0.0, eurPerUsd = 0.5, alreadySpentEur = 0.0)
        assertTrue(guard.tryReserve(2.0))
        assertFalse(guard.tryReserve(0.01))
    }

    @Test
    fun `no cap above the hard cap is ever accepted`() {
        assertFailsWith<IllegalArgumentException> { CostGuard(10.01, 0.0, 1.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { testConfig(capEur = 12.0).validate() }
    }

    @Test
    fun `worst case reserves max output tokens and a pessimistic prompt size`() {
        val price = testSnapshot().model("test/alpha")
        val worst = CostGuard.worstCaseUsd("x".repeat(200), 100, price)
        assertEquals(100 * 1e-6 + 100 * 2e-6, worst, 1e-12)
    }
}
