package com.sandeepvedavyas.llmgateway.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI {
        val scheme = SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("API key")
            .description("Gateway caller key. This is not the upstream provider key.")
        return OpenAPI()
            .info(
                Info()
                    .title("LLM API Gateway")
                    .version("0.1.0")
                    .description(
                        "OpenAI-compatible chat gateway. Callers authenticate with a gateway API key. " +
                            "The process holds the upstream provider key and records estimated token cost.",
                    ),
            )
            .components(Components().addSecuritySchemes("gatewayApiKey", scheme))
            .addSecurityItem(SecurityRequirement().addList("gatewayApiKey"))
    }
}
