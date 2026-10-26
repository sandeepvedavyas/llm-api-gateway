package com.sandeepvedavyas.llmgateway.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class RootController {
    @GetMapping("/")
    fun index(): Map<String, String> = mapOf(
        "service" to "llm-api-gateway",
        "health" to "/actuator/health",
        "openapi" to "/v3/api-docs",
        "docs" to "/swagger-ui.html",
        "chat" to "POST /v1/chat/completions",
        "models" to "GET /v1/models",
        "usage" to "GET /v1/usage",
    )
}
