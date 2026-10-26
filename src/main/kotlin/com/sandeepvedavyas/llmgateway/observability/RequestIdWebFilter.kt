package com.sandeepvedavyas.llmgateway.observability

import com.sandeepvedavyas.llmgateway.auth.GatewayAttributes
import com.sandeepvedavyas.llmgateway.auth.HeaderValues
import java.util.UUID
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class RequestIdWebFilter : WebFilter, Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val incoming = exchange.request.headers.getFirst(HEADER)
        val requestId = HeaderValues.safeToken(incoming) ?: UUID.randomUUID().toString()
        exchange.attributes[GatewayAttributes.REQUEST_ID] = requestId
        exchange.response.headers[HEADER] = requestId
        return chain.filter(exchange)
            .contextWrite { context -> context.put(RequestIdThreadLocalAccessor.KEY, requestId) }
    }

    companion object {
        const val HEADER = "X-Request-Id"
    }
}
