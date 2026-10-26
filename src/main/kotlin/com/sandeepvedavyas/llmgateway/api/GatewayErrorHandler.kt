package com.sandeepvedavyas.llmgateway.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.sandeepvedavyas.llmgateway.auth.requestId
import com.sandeepvedavyas.llmgateway.domain.InvalidRequestException
import com.sandeepvedavyas.llmgateway.domain.ProviderNotConfiguredException
import com.sandeepvedavyas.llmgateway.domain.RateLimitExceededException
import com.sandeepvedavyas.llmgateway.domain.UnauthorizedException
import com.sandeepvedavyas.llmgateway.domain.unwrapGateway
import com.sandeepvedavyas.llmgateway.observability.GatewayMetrics
import com.sandeepvedavyas.llmgateway.observability.RequestIdWebFilter
import com.sandeepvedavyas.llmgateway.provider.UpstreamException
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler
import reactor.core.publisher.Mono

@Component
@Order(-2)
class GatewayErrorHandler(
    private val objectMapper: ObjectMapper,
    private val metrics: GatewayMetrics,
) : ErrorWebExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> {
        if (exchange.response.isCommitted) {
            log.warn("response already committed requestId={} reason={}", exchange.requestId(), ex.message)
            return Mono.error(ex)
        }
        val root = unwrapGateway(ex)
        val mapped = map(root)
        if (mapped.status.is5xxServerError && root !is ProviderNotConfiguredException && root !is UpstreamException) {
            log.error("request failed requestId={} status={}", exchange.requestId(), mapped.status.value(), root)
        } else {
            log.info(
                "request rejected requestId={} status={} code={}",
                exchange.requestId(),
                mapped.status.value(),
                mapped.code,
            )
        }
        if (root is UnauthorizedException) {
            metrics.recordRejection("unauthorized")
            exchange.response.headers.set(org.springframework.http.HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        }
        if (root is RateLimitExceededException) {
            metrics.recordRejection("rate_limited")
            exchange.response.headers.set("Retry-After", root.retryAfter.seconds.coerceAtLeast(1).toString())
            exchange.response.headers.set("X-RateLimit-Limit-Requests", root.limit.toString())
            exchange.response.headers.set("X-RateLimit-Remaining-Requests", "0")
        }
        exchange.response.statusCode = mapped.status
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        exchange.response.headers[RequestIdWebFilter.HEADER] = exchange.requestId()
        val body = mapOf(
            "error" to mapOf(
                "message" to mapped.message,
                "type" to mapped.type,
                "code" to mapped.code,
                "request_id" to exchange.requestId(),
            ),
        )
        val bytes = try {
            objectMapper.writeValueAsBytes(body)
        } catch (_: Exception) {
            FALLBACK.toByteArray(Charsets.UTF_8)
        }
        val buffer: DataBuffer = exchange.response.bufferFactory().wrap(bytes)
        return exchange.response.writeWith(Mono.just(buffer))
    }

    private fun map(error: Throwable): MappedError {
        return when (error) {
            is InvalidRequestException -> MappedError(
                HttpStatus.BAD_REQUEST,
                error.message ?: "Invalid request",
                "invalid_request_error",
                "invalid_request",
            )
            is UnauthorizedException -> MappedError(
                HttpStatus.UNAUTHORIZED,
                error.message ?: "Missing or invalid API key",
                "authentication_error",
                "invalid_api_key",
            )
            is RateLimitExceededException -> MappedError(
                HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded for this API key",
                "rate_limit_error",
                "rate_limit_exceeded",
            )
            is ProviderNotConfiguredException -> MappedError(
                HttpStatus.SERVICE_UNAVAILABLE,
                error.message ?: "Upstream provider is not configured",
                "provider_error",
                "provider_not_configured",
            )
            is UpstreamException -> mapUpstream(error)
            is ServerWebInputException, is WebExchangeBindException -> MappedError(
                HttpStatus.BAD_REQUEST,
                "Request body is not valid JSON",
                "invalid_request_error",
                "invalid_json",
            )
            is ResponseStatusException -> MappedError(
                status = error.statusCode,
                message = clientMessage(error),
                type = if (error.statusCode.is4xxClientError) "invalid_request_error" else "api_error",
                code = error.statusCode.value().toString(),
            )
            else -> MappedError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal gateway error",
                "api_error",
                "internal_error",
            )
        }
    }

    private fun mapUpstream(error: UpstreamException): MappedError {
        val upstream = error.status?.value()
        val status = when (upstream) {
            400, 404, 409, 422 -> HttpStatus.valueOf(upstream)
            408 -> HttpStatus.GATEWAY_TIMEOUT
            429 -> HttpStatus.SERVICE_UNAVAILABLE
            else -> if (error.status?.is5xxServerError == true || error.status == null) {
                HttpStatus.BAD_GATEWAY
            } else {
                HttpStatus.BAD_GATEWAY
            }
        }
        val code = when (upstream) {
            429 -> "upstream_rate_limited"
            408 -> "upstream_timeout"
            401, 403 -> "upstream_auth"
            else -> "upstream_error"
        }
        val type = if (status.is4xxClientError) "invalid_request_error" else "upstream_error"
        return MappedError(status, sanitize(error.message ?: "Upstream request failed"), type, code)
    }

    private fun clientMessage(error: ResponseStatusException): String {
        return when (error.statusCode.value()) {
            404 -> "No such route"
            405 -> "Method not allowed"
            415 -> "Content-Type must be application/json"
            else -> sanitize(error.reason ?: "Request failed")
        }
    }

    private fun sanitize(message: String): String =
        message.replace(WHITESPACE, " ").trim().take(MAX_MESSAGE).ifEmpty { "Request failed" }

    private data class MappedError(
        val status: HttpStatusCode,
        val message: String,
        val type: String,
        val code: String,
    )

    companion object {
        private const val MAX_MESSAGE = 500
        private val WHITESPACE = Regex("\\s+")
        private const val FALLBACK =
            """{"error":{"message":"Internal gateway error","type":"api_error","code":"internal_error"}}"""
    }
}
