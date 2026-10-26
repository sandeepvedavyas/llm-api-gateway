package com.sandeepvedavyas.llmgateway.api

import com.sandeepvedavyas.llmgateway.auth.ApiKey
import com.sandeepvedavyas.llmgateway.auth.GatewayAttributes
import com.sandeepvedavyas.llmgateway.domain.RateLimitExceededException
import com.sandeepvedavyas.llmgateway.domain.ratelimit.TokenBucketRateLimiter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class RateLimitWebFilter(
    private val rateLimiter: TokenBucketRateLimiter,
) : WebFilter, Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 20

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val caller = exchange.attributes[GatewayAttributes.CALLER] as? ApiKey
            ?: return chain.filter(exchange)
        val decision = rateLimiter.tryAcquire(caller.id, caller.requestsPerMinute)
        exchange.response.headers.set("X-RateLimit-Limit-Requests", decision.limit.toString())
        exchange.response.headers.set("X-RateLimit-Remaining-Requests", decision.remaining.toString())
        if (!decision.allowed) {
            return Mono.error(RateLimitExceededException(decision.limit, decision.retryAfter))
        }
        return chain.filter(exchange)
    }
}
