package com.sandeepvedavyas.llmgateway.domain.usage

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryUsageTrackerTest {
    private val tracker = InMemoryUsageTracker()

    @Test
    fun `summaries stay isolated per api key`() {
        tracker.record(UsageEvent("alpha", "gpt-4o-mini", 10, 4, BigDecimal("0.00001000")))
        tracker.record(UsageEvent("beta", "gpt-4o", 3, 1, BigDecimal("0.00002000")))

        val alpha = tracker.summary("alpha")
        val unknown = tracker.summary("nobody")

        assertThat(alpha.requestCount).isEqualTo(1)
        assertThat(alpha.promptTokens).isEqualTo(10)
        assertThat(alpha.byModel).hasSize(1)
        assertThat(alpha.byModel[0].model).isEqualTo("gpt-4o-mini")
        assertThat(unknown.requestCount).isZero()
        assertThat(unknown.estimatedCostUsd).isEqualByComparingTo("0.00000000")
    }
}
