package com.sandeepvedavyas.llmgateway.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.http.HttpStatusCode
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

data class UpstreamContext(
    val requestId: String,
    val idempotencyKey: String? = null,
)

data class UpstreamCompletion(
    val body: JsonNode,
    val model: String,
    val promptTokens: Long,
    val completionTokens: Long,
)

data class UpstreamEvent(
    val data: String,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val done: Boolean = false,
)

interface LlmProvider {
    fun complete(payload: ObjectNode, context: UpstreamContext): Mono<UpstreamCompletion>
    fun stream(payload: ObjectNode, context: UpstreamContext): Flux<UpstreamEvent>
}

class UpstreamException(
    val status: HttpStatusCode?,
    message: String,
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message)
