package com.sandeepvedavyas.llmgateway.domain

import java.time.Duration

open class GatewayException(message: String) : RuntimeException(message)

class InvalidRequestException(message: String) : GatewayException(message)

class UnauthorizedException(
    message: String = "Missing or invalid API key",
) : GatewayException(message)

class ProviderNotConfiguredException(message: String) : GatewayException(message)

class RateLimitExceededException(
    val limit: Int,
    val retryAfter: Duration,
    message: String = "Rate limit exceeded",
) : GatewayException(message)
