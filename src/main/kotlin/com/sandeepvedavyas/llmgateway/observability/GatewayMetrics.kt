package com.sandeepvedavyas.llmgateway.observability

import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.time.Duration
import org.springframework.stereotype.Component

@Component
class GatewayMetrics(private val registry: MeterRegistry) {
    fun recordRequest(keyId: String, model: String, stream: Boolean, outcome: String, latency: Duration) {
        val modelTag = sanitizeModel(model)
        val streamTag = stream.toString()
        registry.counter(
            "gateway.chat.requests",
            "key_id", keyId,
            "model", modelTag,
            "stream", streamTag,
            "outcome", outcome,
        ).increment()
        registry.timer(
            "gateway.chat.latency",
            "model", modelTag,
            "stream", streamTag,
            "outcome", outcome,
        ).record(latency)
    }

    fun recordUsage(model: String, promptTokens: Long, completionTokens: Long, costUsd: BigDecimal) {
        val modelTag = sanitizeModel(model)
        if (promptTokens > 0) {
            registry.counter("gateway.tokens", "model", modelTag, "direction", "prompt")
                .increment(promptTokens.toDouble())
        }
        if (completionTokens > 0) {
            registry.counter("gateway.tokens", "model", modelTag, "direction", "completion")
                .increment(completionTokens.toDouble())
        }
        if (costUsd.signum() > 0) {
            registry.counter("gateway.estimated.cost.usd", "model", modelTag).increment(costUsd.toDouble())
        }
    }

    fun recordRejection(outcome: String) {
        registry.counter("gateway.chat.rejected", "outcome", outcome).increment()
    }

    private fun sanitizeModel(model: String): String {
        val trimmed = model.trim().ifEmpty { "unknown" }
        return trimmed.take(MAX_MODEL_TAG)
    }

    companion object {
        private const val MAX_MODEL_TAG = 64
    }
}
