package com.sandeepvedavyas.llmgateway.config

import java.math.BigDecimal
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "gateway")
class GatewayProperties {
    var auth: Auth = Auth()
    var provider: Provider = Provider()
    var pricing: Pricing = Pricing()

    class Auth {
        var keys: List<Key> = emptyList()

        /** Compact form `id|secret|rpm,id|secret|rpm`. When non-blank, replaces [keys]. */
        var keysEnv: String = ""
    }

    class Key {
        var id: String = ""
        var secret: String = ""
        var requestsPerMinute: Int = 60
    }

    class Provider {
        var baseUrl: String = "https://api.openai.com"
        var apiKey: String = ""
        var connectTimeout: Duration = Duration.ofSeconds(5)
        var responseTimeout: Duration = Duration.ofSeconds(60)
        var maxRetries: Long = 2
        var retryInitialBackoff: Duration = Duration.ofMillis(200)
    }

    class Pricing {
        var defaultInputPerMillion: BigDecimal = BigDecimal("1.00")
        var defaultOutputPerMillion: BigDecimal = BigDecimal("3.00")
        var models: Map<String, ModelPrice> = emptyMap()
    }

    class ModelPrice {
        var inputPerMillion: BigDecimal = BigDecimal.ZERO
        var outputPerMillion: BigDecimal = BigDecimal.ZERO
    }
}
