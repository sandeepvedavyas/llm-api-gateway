package com.sandeepvedavyas.llmgateway.api

import com.sandeepvedavyas.llmgateway.auth.caller
import com.sandeepvedavyas.llmgateway.domain.usage.CostCalculator
import com.sandeepvedavyas.llmgateway.domain.usage.UsageTracker
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v1")
@Tag(name = "Usage")
class UsageController(
    private val usageTracker: UsageTracker,
) {
    @GetMapping("/usage")
    @Operation(summary = "Usage for the calling API key")
    fun usage(exchange: ServerWebExchange): UsageResponse {
        val summary = usageTracker.summary(exchange.caller().id)
        return UsageResponse(
            apiKeyId = summary.apiKeyId,
            requestCount = summary.requestCount,
            promptTokens = summary.promptTokens,
            completionTokens = summary.completionTokens,
            totalTokens = summary.promptTokens + summary.completionTokens,
            estimatedCostUsd = CostCalculator.formatUsd(summary.estimatedCostUsd),
            byModel = summary.byModel.map { model ->
                ModelUsageResponse(
                    model = model.model,
                    requestCount = model.requestCount,
                    promptTokens = model.promptTokens,
                    completionTokens = model.completionTokens,
                    totalTokens = model.promptTokens + model.completionTokens,
                    estimatedCostUsd = CostCalculator.formatUsd(model.estimatedCostUsd),
                )
            },
        )
    }
}

data class UsageResponse(
    val apiKeyId: String,
    val requestCount: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val estimatedCostUsd: String,
    val byModel: List<ModelUsageResponse>,
)

data class ModelUsageResponse(
    val model: String,
    val requestCount: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val estimatedCostUsd: String,
)
