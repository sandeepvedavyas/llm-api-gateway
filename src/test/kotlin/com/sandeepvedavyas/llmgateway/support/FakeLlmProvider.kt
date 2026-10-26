package com.sandeepvedavyas.llmgateway.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sandeepvedavyas.llmgateway.provider.LlmProvider
import com.sandeepvedavyas.llmgateway.provider.UpstreamCompletion
import com.sandeepvedavyas.llmgateway.provider.UpstreamContext
import com.sandeepvedavyas.llmgateway.provider.UpstreamEvent
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Profile("test")
@Component
class FakeLlmProvider(
    private val objectMapper: ObjectMapper,
) : LlmProvider {
    override fun complete(payload: ObjectNode, context: UpstreamContext): Mono<UpstreamCompletion> {
        val model = payload.path("model").asText()
        val body = objectMapper.createObjectNode().apply {
            put("id", "chatcmpl-test")
            put("object", "chat.completion")
            put("model", model)
            putArray("choices").addObject().apply {
                put("index", 0)
                putObject("message").apply {
                    put("role", "assistant")
                    put("content", "hello")
                }
                put("finish_reason", "stop")
            }
            putObject("usage").apply {
                put("prompt_tokens", 11)
                put("completion_tokens", 7)
                put("total_tokens", 18)
            }
        }
        return Mono.just(UpstreamCompletion(body, model, 11, 7))
    }

    override fun stream(payload: ObjectNode, context: UpstreamContext): Flux<UpstreamEvent> {
        val model = payload.path("model").asText()
        val chunk = objectMapper.createObjectNode().apply {
            put("id", "chatcmpl-test")
            put("object", "chat.completion.chunk")
            put("model", model)
            putArray("choices").addObject().apply {
                put("index", 0)
                putObject("delta").put("content", "hello")
                putNull("finish_reason")
            }
        }
        val usage = objectMapper.createObjectNode().apply {
            put("id", "chatcmpl-test")
            put("object", "chat.completion.chunk")
            put("model", model)
            putArray("choices")
            putObject("usage").apply {
                put("prompt_tokens", 4)
                put("completion_tokens", 1)
                put("total_tokens", 5)
            }
        }
        return Flux.just(
            UpstreamEvent(objectMapper.writeValueAsString(chunk)),
            UpstreamEvent(
                data = objectMapper.writeValueAsString(usage),
                promptTokens = 4,
                completionTokens = 1,
            ),
            UpstreamEvent(data = "[DONE]", done = true),
        )
    }
}
