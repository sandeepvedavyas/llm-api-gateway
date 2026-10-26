package com.sandeepvedavyas.llmgateway.auth

import com.sandeepvedavyas.llmgateway.config.GatewayProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ApiKeyRegistry(properties: GatewayProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val loadedFromEnv = ApiKeyParser.parse(properties.auth.keysEnv).isNotEmpty()
    val keys: List<ApiKey> = load(properties)

    init {
        log.info(
            "loaded {} gateway api key(s) from {}",
            keys.size,
            if (loadedFromEnv) "GATEWAY_API_KEYS" else "configuration",
        )
    }

    fun ids(): List<String> = keys.map { it.id }

    fun authenticate(presented: String): ApiKey? {
        if (presented.isEmpty()) {
            return null
        }
        var match: ApiKey? = null
        for (key in keys) {
            if (SecretEquals.equals(presented, key.secret)) {
                match = key
            }
        }
        return match
    }

    private fun load(properties: GatewayProperties): List<ApiKey> {
        val fromEnv = ApiKeyParser.parse(properties.auth.keysEnv)
        val resolved = if (fromEnv.isNotEmpty()) {
            fromEnv
        } else {
            properties.auth.keys.map { key ->
                ApiKey(
                    id = key.id.trim(),
                    secret = key.secret.trim(),
                    requestsPerMinute = key.requestsPerMinute,
                )
            }
        }
        validate(resolved)
        return resolved
    }

    private fun validate(resolved: List<ApiKey>) {
        require(resolved.isNotEmpty()) {
            "No gateway API keys configured. Set GATEWAY_API_KEY or GATEWAY_API_KEYS."
        }
        resolved.forEach { key ->
            require(key.id.isNotBlank()) { "Gateway API key id must not be blank" }
            require(key.secret.isNotBlank()) { "Gateway API key secret must not be blank for id=${key.id}" }
            require(key.requestsPerMinute in 1..MAX_REQUESTS_PER_MINUTE) {
                "requests-per-minute for ${key.id} must be between 1 and $MAX_REQUESTS_PER_MINUTE"
            }
        }
        val duplicateIds = resolved.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) { "Duplicate gateway API key id: ${duplicateIds.joinToString()}" }
        val duplicateSecrets = resolved.groupBy { it.secret }.filterValues { it.size > 1 }
        require(duplicateSecrets.isEmpty()) { "Two gateway API keys share the same secret" }
    }

    companion object {
        const val MAX_REQUESTS_PER_MINUTE = 100_000
    }
}
