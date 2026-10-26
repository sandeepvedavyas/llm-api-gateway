package com.sandeepvedavyas.llmgateway.domain.usage

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

data class UsageEvent(
    val apiKeyId: String,
    val model: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val costUsd: BigDecimal,
)

data class ModelUsageSummary(
    val model: String,
    val requestCount: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedCostUsd: BigDecimal,
)

data class UsageSummary(
    val apiKeyId: String,
    val requestCount: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedCostUsd: BigDecimal,
    val byModel: List<ModelUsageSummary>,
)

interface UsageTracker {
    fun record(event: UsageEvent)
    fun summary(apiKeyId: String): UsageSummary
}

@Component
class InMemoryUsageTracker : UsageTracker {
    private val byKey = ConcurrentHashMap<String, KeyUsage>()

    override fun record(event: UsageEvent) {
        val usage = byKey.computeIfAbsent(event.apiKeyId) { KeyUsage() }
        synchronized(usage) {
            usage.requestCount += 1
            usage.promptTokens += event.promptTokens
            usage.completionTokens += event.completionTokens
            usage.costUsd = usage.costUsd.add(event.costUsd).setScale(CostCalculator.SCALE, RoundingMode.HALF_UP)
            val model = usage.models.computeIfAbsent(event.model) { ModelUsage() }
            model.requestCount += 1
            model.promptTokens += event.promptTokens
            model.completionTokens += event.completionTokens
            model.costUsd = model.costUsd.add(event.costUsd).setScale(CostCalculator.SCALE, RoundingMode.HALF_UP)
        }
    }

    override fun summary(apiKeyId: String): UsageSummary {
        val usage = byKey[apiKeyId]
        if (usage == null) {
            return emptySummary(apiKeyId)
        }
        synchronized(usage) {
            return UsageSummary(
                apiKeyId = apiKeyId,
                requestCount = usage.requestCount,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                estimatedCostUsd = usage.costUsd,
                byModel = usage.models.entries
                    .sortedBy { it.key }
                    .map { (model, totals) ->
                        ModelUsageSummary(
                            model = model,
                            requestCount = totals.requestCount,
                            promptTokens = totals.promptTokens,
                            completionTokens = totals.completionTokens,
                            estimatedCostUsd = totals.costUsd,
                        )
                    },
            )
        }
    }

    private fun emptySummary(apiKeyId: String) = UsageSummary(
        apiKeyId = apiKeyId,
        requestCount = 0,
        promptTokens = 0,
        completionTokens = 0,
        estimatedCostUsd = BigDecimal.ZERO.setScale(CostCalculator.SCALE, RoundingMode.HALF_UP),
        byModel = emptyList(),
    )

    private class KeyUsage {
        var requestCount: Long = 0
        var promptTokens: Long = 0
        var completionTokens: Long = 0
        var costUsd: BigDecimal = BigDecimal.ZERO.setScale(CostCalculator.SCALE, RoundingMode.HALF_UP)
        val models: MutableMap<String, ModelUsage> = HashMap()
    }

    private class ModelUsage {
        var requestCount: Long = 0
        var promptTokens: Long = 0
        var completionTokens: Long = 0
        var costUsd: BigDecimal = BigDecimal.ZERO.setScale(CostCalculator.SCALE, RoundingMode.HALF_UP)
    }
}
