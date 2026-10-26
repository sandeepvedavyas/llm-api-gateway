package com.sandeepvedavyas.llmgateway.observability

import com.sandeepvedavyas.llmgateway.config.GatewayProperties
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.ReactiveHealthIndicator
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component("upstreamProvider")
class UpstreamProviderHealthIndicator(
    private val properties: GatewayProperties,
) : ReactiveHealthIndicator {
    override fun health(): Mono<Health> {
        val configured = properties.provider.apiKey.isNotBlank()
        val health = Health.up()
            .withDetail("upstream", if (configured) "configured" else "missing_api_key")
            .withDetail("baseUrl", properties.provider.baseUrl.trim().trimEnd('/'))
            .build()
        return Mono.just(health)
    }
}
