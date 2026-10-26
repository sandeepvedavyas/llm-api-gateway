package com.sandeepvedavyas.llmgateway.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sandeepvedavyas.llmgateway.auth.ApiKey
import com.sandeepvedavyas.llmgateway.domain.ProviderNotConfiguredException
import com.sandeepvedavyas.llmgateway.domain.ValidatedChatRequest
import com.sandeepvedavyas.llmgateway.domain.unwrapGateway
import com.sandeepvedavyas.llmgateway.domain.usage.CostCalculator
import com.sandeepvedavyas.llmgateway.domain.usage.UsageEvent
import com.sandeepvedavyas.llmgateway.domain.usage.UsageTracker
import com.sandeepvedavyas.llmgateway.observability.GatewayMetrics
import com.sandeepvedavyas.llmgateway.provider.LlmProvider
import com.sandeepvedavyas.llmgateway.provider.UpstreamContext
import com.sandeepvedavyas.llmgateway.provider.UpstreamEvent
import com.sandeepvedavyas.llmgateway.provider.UpstreamException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

data class CallContext(
    val apiKey: ApiKey,
    val requestId: String,
    val idempotencyKey: String? = null,
)

@Service
class ChatGateway(
    private val provider: LlmProvider,
    private val usageTracker: UsageTracker,
    private val costCalculator: CostCalculator,
    private val metrics: GatewayMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun complete(call: CallContext, request: ValidatedChatRequest, payload: ObjectNode): Mono<JsonNode> {
        val started = System.nanoTime()
        return provider.complete(payload, call.toUpstream())
            .doOnNext { completion ->
                val model = completion.model.ifBlank { request.model }
                recordSuccess(
                    call = call,
                    requestedModel = model,
                    stream = false,
                    promptTokens = completion.promptTokens,
                    completionTokens = completion.completionTokens,
                    started = started,
                    usageMissing = !completion.body.path("usage").isObject,
                )
            }
            .map { it.body }
            .doOnError { error -> recordFailure(call, request, stream = false, started = started, error = error) }
    }

    fun stream(call: CallContext, request: ValidatedChatRequest, payload: ObjectNode): Flux<UpstreamEvent> {
        val started = System.nanoTime()
        val prompt = AtomicLong(MISSING)
        val completion = AtomicLong(MISSING)
        val recorded = AtomicBoolean(false)
        return provider.stream(payload, call.toUpstream())
            .doOnNext { event ->
                if (event.promptTokens != null || event.completionTokens != null) {
                    prompt.set(event.promptTokens ?: 0)
                    completion.set(event.completionTokens ?: 0)
                }
            }
            .doFinally { signal ->
                if (signal == reactor.core.publisher.SignalType.ON_COMPLETE && recorded.compareAndSet(false, true)) {
                    val promptTokens = prompt.get().let { if (it < 0) 0 else it }
                    val completionTokens = completion.get().let { if (it < 0) 0 else it }
                    recordSuccess(
                        call = call,
                        requestedModel = request.model,
                        stream = true,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        started = started,
                        usageMissing = prompt.get() < 0 && completion.get() < 0,
                    )
                }
            }
            .doOnError { error ->
                if (recorded.compareAndSet(false, true)) {
                    recordFailure(call, request, stream = true, started = started, error = error)
                }
            }
            .doOnCancel {
                if (recorded.compareAndSet(false, true)) {
                    metrics.recordRequest(
                        keyId = call.apiKey.id,
                        model = request.model,
                        stream = true,
                        outcome = "cancelled",
                        latency = elapsed(started),
                    )
                    log.info(
                        "chat cancelled requestId={} keyId={} model={} stream=true",
                        call.requestId,
                        call.apiKey.id,
                        request.model,
                    )
                }
            }
    }

    private fun recordSuccess(
        call: CallContext,
        requestedModel: String,
        stream: Boolean,
        promptTokens: Long,
        completionTokens: Long,
        started: Long,
        usageMissing: Boolean,
    ) {
        val cost = costCalculator.estimate(requestedModel, promptTokens, completionTokens)
        usageTracker.record(
            UsageEvent(
                apiKeyId = call.apiKey.id,
                model = requestedModel,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                costUsd = cost,
            ),
        )
        val latency = elapsed(started)
        metrics.recordRequest(call.apiKey.id, requestedModel, stream, "success", latency)
        metrics.recordUsage(requestedModel, promptTokens, completionTokens, cost)
        if (usageMissing) {
            log.warn(
                "upstream omitted token usage requestId={} keyId={} model={}",
                call.requestId,
                call.apiKey.id,
                requestedModel,
            )
        }
        log.info(
            "chat completed requestId={} keyId={} model={} stream={} promptTokens={} completionTokens={} estimatedCostUsd={} latencyMs={}",
            call.requestId,
            call.apiKey.id,
            requestedModel,
            stream,
            promptTokens,
            completionTokens,
            CostCalculator.formatUsd(cost),
            latency.toMillis(),
        )
    }

    private fun recordFailure(
        call: CallContext,
        request: ValidatedChatRequest,
        stream: Boolean,
        started: Long,
        error: Throwable,
    ) {
        val outcome = outcomeOf(error)
        metrics.recordRequest(call.apiKey.id, request.model, stream, outcome, elapsed(started))
        log.warn(
            "chat failed requestId={} keyId={} model={} stream={} outcome={} reason={}",
            call.requestId,
            call.apiKey.id,
            request.model,
            stream,
            outcome,
            unwrapGateway(error).message,
        )
    }

    private fun outcomeOf(error: Throwable): String {
        return when (val root = unwrapGateway(error)) {
            is ProviderNotConfiguredException -> "provider_not_configured"
            is UpstreamException -> "upstream_${root.status?.value() ?: "error"}"
            else -> "error"
        }
    }

    private fun elapsed(started: Long): Duration = Duration.ofNanos(System.nanoTime() - started)

    private fun CallContext.toUpstream() = UpstreamContext(requestId, idempotencyKey)

    companion object {
        private const val MISSING = -1L
    }
}
