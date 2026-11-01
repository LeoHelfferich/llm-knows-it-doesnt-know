package io.github.leohelfferich.abstain.cost

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

import io.github.leohelfferich.abstain.config.Workspace
import io.github.leohelfferich.abstain.config.model.validate
import io.github.leohelfferich.abstain.execution.model.CallRecord
import io.github.leohelfferich.abstain.execution.callKey
import io.github.leohelfferich.abstain.pricing.model.PricingSnapshot
import io.github.leohelfferich.abstain.prompt.model.PromptVariant
import io.github.leohelfferich.abstain.squad.model.Kind
import io.github.leohelfferich.abstain.testing.repoRoot
import io.github.leohelfferich.abstain.testing.testConfig
import io.github.leohelfferich.abstain.testing.testItems
import io.github.leohelfferich.abstain.testing.testSnapshot

class CostTest {
    @Test
    fun `should multiply calls by the snapshot prices`() {
        val config = testConfig(reps = 3).copy(maxTokens = 1000)
        val items = testItems(2)

        // ---
        val e = Estimator.estimate("t", items, 3, config, testSnapshot())
        // ---

        assertThat(e.models[0].calls).isEqualTo(4 * 2 * 3)
        assertThat(e.totalCalls).isEqualTo(4 * 2 * 3 * 2)
        val alpha = e.models[0]
        // alpha: 10 answer tokens + 100 assumed reasoning tokens
        assertThat(alpha.expectedOutputTokensPerCall).isEqualTo(110.0)
        assertThat(alpha.expectedUsd).isCloseTo(alpha.calls * (alpha.inputTokensPerCall * 1e-6 + 110 * 2e-6), within(1e-12))
        assertThat(alpha.pessimisticOutputTokensPerCall).isEqualTo(330.0)
        assertThat(e.ceilingUsd > e.pessimisticUsd && e.pessimisticUsd > e.expectedUsd).isTrue()
        assertThat(e.expectedUsd).isCloseTo(e.models.sumOf { it.expectedUsd }, within(1e-12))
    }

    @Test
    fun `should cover 6000 calls and fit the cap in the expected case`() {
        val ws = Workspace(repoRoot)
        val config = ws.loadConfig()

        // ---
        val e = Estimator.estimate("full", ws.loadSample().items, config.reps, config, PricingSnapshot.load(ws.snapshotFile))
        // ---

        assertThat(e.totalCalls).isEqualTo(6000)
        assertThat(e.models.size).isEqualTo(5)
        assertThat(e.expectedFitsCap).describedAs("expected cost ${e.expectedEur} must fit the cap").isTrue()
        assertThat(e.expectedEur > 0.5).isTrue()
    }

    @Test
    fun `should replace token assumptions with observed usage`() {
        val config = testConfig()
        val records = listOf(
            CallRecord("a0", Kind.ANSWERABLE, "alpha", "test/alpha", PromptVariant.NEUTRAL, 1, "ok", "t", 0.7, 400, "x", promptTokens = 100, completionTokens = 40, costUsd = 0.1),
            CallRecord("a1", Kind.ANSWERABLE, "alpha", "test/alpha", PromptVariant.STRICT, 1, "ok", "t", 0.7, 400, "x", promptTokens = 100, completionTokens = 60, costUsd = 0.1),
            CallRecord("a1", Kind.ANSWERABLE, "alpha", "test/alpha", PromptVariant.STRICT, 1, "error", "t", 0.7, 400, error = "boom"),
        )

        // ---
        val observed = Estimator.observedFrom(records)
        // ---

        assertThat(observed.keys).isEqualTo(setOf("alpha"))
        assertThat(observed.getValue("alpha").completionTokensPerCall).isEqualTo(50.0)
        assertThat(observed.getValue("alpha").promptTokensPerChar).isEqualTo(0.25)
        val e = Estimator.estimate("t", testItems(1), 1, config, testSnapshot(), observed)
        assertThat(e.models.first { it.alias == "alpha" }.expectedOutputTokensPerCall).isEqualTo(50.0)
        assertThat(e.models.first { it.alias == "beta" }.expectedOutputTokensPerCall).isEqualTo(10.0)
    }

    @Test
    fun `should keep reservations plus spend inside the budget under contention`() {
        val guard = CostGuard(capEur = 1.0, safetyMarginEur = 0.0, eurPerUsd = 1.0, alreadySpentEur = 0.0)
        val granted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)

        // ---
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
        // ---

        assertThat(granted.get() <= 100).describedAs("granted ${granted.get()}").isTrue()
        assertThat(guard.spentEur() <= 1.0 + 1e-9).isTrue()
    }

    @Test
    fun `should account for money already spent and for the safety margin`() {
        // ---
        val guard = CostGuard(capEur = 1.0, safetyMarginEur = 0.25, eurPerUsd = 1.0, alreadySpentEur = 0.7)
        // ---

        assertThat(guard.tryReserve(0.1)).isFalse()
        assertThat(guard.tryReserve(0.05)).isTrue()
        assertThat(guard.remainingEur()).isCloseTo(0.0, within(1e-9))
    }

    @Test
    fun `should convert the cap from euro to dollars`() {
        // ---
        val guard = CostGuard(capEur = 1.0, safetyMarginEur = 0.0, eurPerUsd = 0.5, alreadySpentEur = 0.0)
        // ---

        assertThat(guard.tryReserve(2.0)).isTrue()
        assertThat(guard.tryReserve(0.01)).isFalse()
    }

    @Test
    fun `should reject a cap above the hard cap`() {
        val aboveTheGuard = 10.01
        val aboveTheConfig = testConfig(capEur = 12.0)

        // ---
        val guardRejected = catchThrowable { CostGuard(aboveTheGuard, 0.0, 1.0, 0.0) }
        val configRejected = catchThrowable { aboveTheConfig.validate() }
        // ---

        assertThat(guardRejected).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(configRejected).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `should exclude calls that already succeeded from the remaining cost`() {
        val config = testConfig(reps = 3)
        val items = testItems(2)
        val estimate = Estimator.estimate("t", items, 3, config, testSnapshot())

        // ---
        val (allCalls, allUsd) = Estimator.remainingExpectedUsd(estimate, items, 3, emptySet())
        // ---

        assertThat(allCalls).isEqualTo(estimate.totalCalls)
        assertThat(allUsd).isCloseTo(estimate.expectedUsd, within(1e-9))
        val done = setOf(callKey(items.first().id, "alpha", PromptVariant.NEUTRAL, 1))
        val (left, usd) = Estimator.remainingExpectedUsd(estimate, items, 3, done)
        assertThat(left).isEqualTo(estimate.totalCalls - 1)
        assertThat(usd < estimate.expectedUsd).isTrue()
    }

    @Test
    fun `should ignore negative and non-finite costs`() {
        val reported = 1.5
        val negativeReported = -1.0
        val missing = Double.NaN

        // ---
        val fromReported = payableUsd(reported, 9.0, 4.0)
        val fromComputed = payableUsd(negativeReported, 2.0, 9.0)
        val fromWorstCase = payableUsd(missing, Double.NEGATIVE_INFINITY, 4.0)
        val fromZero = payableUsd(0.0, 3.0, 4.0)
        // ---

        assertThat(fromReported).isEqualTo(1.5 to "reported")
        assertThat(fromComputed).isEqualTo(2.0 to "computed")
        assertThat(fromWorstCase).isEqualTo(4.0 to "worst-case")
        assertThat(fromZero).isEqualTo(0.0 to "reported")
    }

    @Test
    fun `should reject a zero or negative price`() {
        val base = testSnapshot().models.first()
        val freeCompletion = testSnapshot().copy(models = listOf(base.copy(completionUsdPerToken = "0")))
        val negativePrompt = testSnapshot().copy(models = listOf(base.copy(promptUsdPerToken = "-0.1")))

        // ---
        val freeCompletionRejected = catchThrowable { freeCompletion.model(base.id) }
        val negativePromptRejected = catchThrowable { negativePrompt.model(base.id) }
        // ---

        assertThat(freeCompletionRejected).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(negativePromptRejected).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `should reserve the max output tokens and a pessimistic prompt size`() {
        val price = testSnapshot().model("test/alpha")

        // ---
        val worst = CostGuard.worstCaseUsd("x".repeat(200), 100, price)
        // ---

        assertThat(worst).isCloseTo(100 * 1e-6 + 100 * 2e-6, within(1e-12))
    }
}
