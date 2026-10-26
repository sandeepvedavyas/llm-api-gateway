package com.sandeepvedavyas.llmgateway.api

import com.sandeepvedavyas.llmgateway.auth.ApiKeyRegistry
import com.sandeepvedavyas.llmgateway.auth.GatewayAttributes
import com.sandeepvedavyas.llmgateway.domain.UnauthorizedException
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class ApiKeyAuthWebFilter(
    private val apiKeyRegistry: ApiKeyRegistry,
) : WebFilter, Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 10

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        if (isPublic(path)) {
            return chain.filter(exchange)
        }
        val token = bearerToken(exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION))
            ?: return Mono.error(UnauthorizedException())
        val apiKey = apiKeyRegistry.authenticate(token) ?: return Mono.error(UnauthorizedException())
        exchange.attributes[GatewayAttributes.CALLER] = apiKey
        return chain.filter(exchange)
    }

    private fun bearerToken(header: String?): String? {
        if (header.isNullOrBlank()) {
            return null
        }
        val parts = header.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) {
            return null
        }
        return parts[1].takeIf { it.isNotBlank() }
    }

    companion object {
        fun isPublic(path: String): Boolean {
            return path == "/" ||
                path == "/favicon.ico" ||
                path == "/actuator/health" ||
                path.startsWith("/actuator/health/") ||
                path == "/actuator/info" ||
                path == "/actuator/prometheus" ||
                path == "/actuator/metrics" ||
                path.startsWith("/actuator/metrics/") ||
                path == "/v3/api-docs" ||
                path.startsWith("/v3/api-docs/") ||
                path == "/swagger-ui.html" ||
                path.startsWith("/swagger-ui/") ||
                path.startsWith("/webjars/")
        }
    }
}
