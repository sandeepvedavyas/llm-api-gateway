package com.sandeepvedavyas.llmgateway.config

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class GatewayStartupValidator(private val properties: GatewayProperties) {
    @PostConstruct
    fun validate() {
        val provider = properties.provider
        val baseUrl = provider.baseUrl.trim()
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "gateway.provider.base-url must be an absolute http(s) URL"
        }
        require(!provider.connectTimeout.isNegative && !provider.connectTimeout.isZero) {
            "gateway.provider.connect-timeout must be positive"
        }
        require(!provider.responseTimeout.isNegative && !provider.responseTimeout.isZero) {
            "gateway.provider.response-timeout must be positive"
        }
        require(provider.maxRetries in 0..MAX_RETRIES) {
            "gateway.provider.max-retries must be between 0 and $MAX_RETRIES"
        }
        require(!provider.retryInitialBackoff.isNegative) {
            "gateway.provider.retry-initial-backoff must not be negative"
        }
        require(properties.pricing.defaultInputPerMillion.signum() >= 0) {
            "gateway.pricing.default-input-per-million must not be negative"
        }
        require(properties.pricing.defaultOutputPerMillion.signum() >= 0) {
            "gateway.pricing.default-output-per-million must not be negative"
        }
    }

    companion object {
        const val MAX_RETRIES = 5L
    }
}
