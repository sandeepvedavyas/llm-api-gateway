package com.sandeepvedavyas.llmgateway.provider.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sandeepvedavyas.llmgateway.config.GatewayProperties
import com.sandeepvedavyas.llmgateway.domain.ProviderNotConfiguredException
import com.sandeepvedavyas.llmgateway.observability.RequestIdWebFilter
import com.sandeepvedavyas.llmgateway.provider.LlmProvider
import com.sandeepvedavyas.llmgateway.provider.SseFrameDecoder
import com.sandeepvedavyas.llmgateway.provider.UpstreamCompletion
import com.sandeepvedavyas.llmgateway.provider.UpstreamContext
import com.sandeepvedavyas.llmgateway.provider.UpstreamEvent
import com.sandeepvedavyas.llmgateway.provider.UpstreamException
import io.netty.handler.timeout.ReadTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry

/**
 * Talks to any server that implements the OpenAI chat-completions HTTP shape
 * (OpenAI, Groq, Together, vLLM, Ollama's OpenAI endpoint). The caller's JSON
 * is forwarded, with `stream` and `stream_options.include_usage` set by the gateway.
 */
@Component
@Profile("!test")
class OpenAiChatProvider(
    private val properties: GatewayProperties,
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
) : LlmProvider {
    private val log = LoggerFactory.getLogger(javaClass)
    private val decoder = SseFrameDecoder(objectMapper)
    private val path = chatCompletionsPath(properties.provider.baseUrl)

    override fun complete(payload: ObjectNode, context: UpstreamContext): Mono<UpstreamCompletion> {
        val prepared = prepare(payload, stream = false)
        return Mono.defer {
            ensureConfigured()
            exchange(prepared, context).bodyToMono(JsonNode::class.java)
        }
            .map { body -> toCompletion(body, payload) }
            .onErrorMap(::mapTransport)
            .retryWhen(retrySpec())
    }

    override fun stream(payload: ObjectNode, context: UpstreamContext): Flux<UpstreamEvent> {
        val prepared = prepare(payload, stream = true)
        val emitted = AtomicBoolean(false)
        return Flux.defer {
            ensureConfigured()
            exchange(prepared, context)
                .bodyToFlux(DataBuffer::class.java)
                .map { buffer -> readUtf8(buffer) }
                .transform(decoder::decode)
        }
            .doOnNext { emitted.set(true) }
            .onErrorMap(::mapTransport)
            .retryWhen(retrySpec { !emitted.get() })
    }

    private fun exchange(payload: ObjectNode, context: UpstreamContext): WebClient.ResponseSpec {
        val request = webClient.post()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.provider.apiKey}")
            .header(RequestIdWebFilter.HEADER, context.requestId)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        val idempotencyKey = context.idempotencyKey
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey)
        }
        return request.bodyValue(payload)
            .retrieve()
            .onStatus({ status -> status.isError }) { response -> errorBody(response) }
    }

    private fun errorBody(response: ClientResponse): Mono<out Throwable> {
        val retryAfter = retryAfterSeconds(response.headers().asHttpHeaders())
        return response.bodyToMono(String::class.java)
            .defaultIfEmpty("")
            .map { body ->
                UpstreamException(
                    status = response.statusCode(),
                    message = "Upstream returned ${response.statusCode().value()}: ${extractMessage(body)}",
                    retryable = response.statusCode().value() in RETRYABLE_STATUS,
                    retryAfterSeconds = retryAfter,
                )
            }
    }

    private fun extractMessage(body: String): String {
        val compact = body.replace(WHITESPACE, " ").trim()
        if (compact.isEmpty()) {
            return "no response body"
        }
        val parsed = try {
            objectMapper.readTree(body).path("error").path("message").asText("")
        } catch (_: Exception) {
            ""
        }
        return parsed.ifBlank { compact }.take(MAX_MESSAGE)
    }

    private fun mapTransport(error: Throwable): Throwable {
        if (error is UpstreamException) {
            return error
        }
        if (isTimeout(error)) {
            return UpstreamException(HttpStatus.GATEWAY_TIMEOUT, "Upstream timed out", retryable = false)
        }
        if (error is WebClientRequestException && isConnectFailure(error)) {
            return UpstreamException(HttpStatus.BAD_GATEWAY, "Upstream connection failed", retryable = true)
        }
        return error
    }

    private fun toCompletion(body: JsonNode, request: ObjectNode): UpstreamCompletion {
        val usage = body.path("usage")
        val prompt = if (usage.isObject) usage.path("prompt_tokens").asLong(0) else 0
        val completion = if (usage.isObject) usage.path("completion_tokens").asLong(0) else 0
        val model = body.path("model").asText("").ifBlank { request.path("model").asText("") }
        return UpstreamCompletion(
            body = body,
            model = model,
            promptTokens = prompt,
            completionTokens = completion,
        )
    }

    private fun ensureConfigured() {
        if (properties.provider.apiKey.isBlank()) {
            throw ProviderNotConfiguredException(
                "Upstream provider is not configured. Set OPENAI_API_KEY.",
            )
        }
    }

    private fun retrySpec(extra: () -> Boolean = { true }): Retry {
        val maxRetries = properties.provider.maxRetries
        val initial = properties.provider.retryInitialBackoff
        return Retry.from { signals ->
            signals.concatMap { signal ->
                val failure = signal.failure()
                val attempt = signal.totalRetries()
                if (!extra() || attempt >= maxRetries || !isRetryable(failure)) {
                    Mono.error(failure)
                } else {
                    val delay = retryDelay(failure, attempt, initial)
                    log.warn(
                        "retrying upstream attempt={} delayMs={} reason={}",
                        attempt + 1,
                        delay.toMillis(),
                        failure.message,
                    )
                    Mono.delay(delay)
                }
            }
        }
    }

    companion object {
        internal fun prepare(payload: ObjectNode, stream: Boolean): ObjectNode {
            val copy = payload.deepCopy()
            copy.put("stream", stream)
            if (stream) {
                val options = copy.get("stream_options")
                val objectOptions = if (options is ObjectNode) {
                    options
                } else {
                    copy.putObject("stream_options")
                }
                objectOptions.put("include_usage", true)
            }
            return copy
        }

        internal fun isRetryable(failure: Throwable): Boolean {
            val upstream = generateSequence(failure) { it.cause }.firstOrNull { it is UpstreamException } as? UpstreamException
            if (upstream != null) {
                return upstream.retryable
            }
            return failure is WebClientRequestException && isConnectFailure(failure)
        }

        internal fun isTimeout(failure: Throwable): Boolean =
            generateSequence(failure) { it.cause }.any { candidate ->
                candidate is TimeoutException || candidate is ReadTimeoutException
            }

        internal fun retryDelay(failure: Throwable, attempt: Long, initial: Duration): Duration {
            val headerDelay = (failure as? UpstreamException)?.retryAfterSeconds
                ?: (failure.cause as? UpstreamException)?.retryAfterSeconds
            if (headerDelay != null) {
                return Duration.ofSeconds(headerDelay.coerceIn(0, MAX_BACKOFF_SECONDS))
                    .coerceAtLeast(Duration.ofMillis(1))
            }
            val shift = attempt.coerceIn(0, 4)
            var delay = initial
            repeat(shift.toInt()) {
                delay = delay.multipliedBy(2).coerceAtMost(MAX_BACKOFF)
            }
            val jittered = (delay.toMillis() * 0.8).toLong().coerceAtLeast(1)
            return Duration.ofMillis(jittered).coerceAtMost(MAX_BACKOFF)
        }

        private fun isConnectFailure(error: WebClientRequestException): Boolean =
            generateSequence<Throwable>(error) { it.cause }.any { candidate ->
                candidate is ConnectException || candidate is UnknownHostException
            }

        private fun retryAfterSeconds(headers: HttpHeaders): Long? =
            headers.getFirst(HttpHeaders.RETRY_AFTER)?.trim()?.toLongOrNull()?.coerceIn(0, 120)

        private fun readUtf8(buffer: DataBuffer): String {
            return try {
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                String(bytes, Charsets.UTF_8)
            } finally {
                DataBufferUtils.release(buffer)
            }
        }

        private val RETRYABLE_STATUS = setOf(408, 429, 500, 502, 503, 504)
        private val MAX_BACKOFF: Duration = Duration.ofSeconds(2)
        private const val MAX_BACKOFF_SECONDS = 2L
        private const val MAX_MESSAGE = 300
        private val WHITESPACE = Regex("\\s+")
    }
}

internal fun chatCompletionsPath(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/v1")) "/chat/completions" else "/v1/chat/completions"
}

private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (this < minimum) minimum else this

private fun Duration.coerceAtMost(maximum: Duration): Duration = if (this > maximum) maximum else this
