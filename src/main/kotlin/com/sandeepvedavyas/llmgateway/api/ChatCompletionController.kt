package com.sandeepvedavyas.llmgateway.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sandeepvedavyas.llmgateway.application.CallContext
import com.sandeepvedavyas.llmgateway.application.ChatGateway
import com.sandeepvedavyas.llmgateway.auth.HeaderValues
import com.sandeepvedavyas.llmgateway.auth.caller
import com.sandeepvedavyas.llmgateway.auth.requestId
import com.sandeepvedavyas.llmgateway.domain.ChatRequestValidator
import com.sandeepvedavyas.llmgateway.provider.UpstreamEvent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/v1")
@Tag(name = "Chat")
class ChatCompletionController(
    private val chatGateway: ChatGateway,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping("/chat/completions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Create a chat completion",
        description = "OpenAI-compatible. Non-streaming responses are JSON. stream=true returns Server-Sent Events and ends with data: [DONE].",
    )
    fun create(@RequestBody body: JsonNode, exchange: ServerWebExchange): Mono<Void> {
        val request = ChatRequestValidator.validate(body)
        val payload = (body as ObjectNode).deepCopy()
        val call = CallContext(
            apiKey = exchange.caller(),
            requestId = exchange.requestId(),
            idempotencyKey = HeaderValues.safeToken(exchange.request.headers.getFirst("Idempotency-Key")),
        )
        return if (request.stream) {
            writeStream(exchange, chatGateway.stream(call, request, payload))
        } else {
            chatGateway.complete(call, request, payload).flatMap { json -> writeJson(exchange, json) }
        }
    }

    private fun writeJson(exchange: ServerWebExchange, json: JsonNode): Mono<Void> {
        exchange.response.statusCode = HttpStatus.OK
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val bytes = objectMapper.writeValueAsBytes(json)
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
    }

    private fun writeStream(exchange: ServerWebExchange, events: Flux<UpstreamEvent>): Mono<Void> {
        val buffers = events.switchOnFirst { signal, rest ->
            if (signal.hasError()) {
                Flux.error(signal.throwable ?: IllegalStateException("upstream stream failed"))
            } else {
                exchange.response.statusCode = HttpStatus.OK
                exchange.response.headers.contentType = MediaType.TEXT_EVENT_STREAM
                exchange.response.headers.set(HttpHeaders.CACHE_CONTROL, "no-cache")
                exchange.response.headers.set("X-Accel-Buffering", "no")
                rest.map { event -> frame(exchange, event) }
                    .onErrorResume { error ->
                        if (!exchange.response.isCommitted) {
                            Flux.error(error)
                        } else {
                            val payload = objectMapper.writeValueAsString(
                                mapOf(
                                    "error" to mapOf(
                                        "message" to (error.message ?: "stream failed"),
                                        "type" to "upstream_error",
                                        "code" to "stream_failed",
                                    ),
                                ),
                            )
                            Flux.just(bytes(exchange, "data: $payload\n\n"), bytes(exchange, "data: [DONE]\n\n"))
                        }
                    }
            }
        }
        return exchange.response.writeWith(buffers)
    }

    private fun frame(exchange: ServerWebExchange, event: UpstreamEvent): DataBuffer =
        bytes(exchange, "data: ${event.data}\n\n")

    private fun bytes(exchange: ServerWebExchange, text: String): DataBuffer =
        exchange.response.bufferFactory().wrap(text.toByteArray(Charsets.UTF_8))
}
