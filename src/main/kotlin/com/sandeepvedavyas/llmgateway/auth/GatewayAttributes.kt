package com.sandeepvedavyas.llmgateway.auth

import com.sandeepvedavyas.llmgateway.domain.UnauthorizedException
import org.springframework.web.server.ServerWebExchange

object GatewayAttributes {
    const val CALLER = "gateway.caller"
    const val REQUEST_ID = "gateway.requestId"
}

object HeaderValues {
    private val SAFE = Regex("^[A-Za-z0-9._-]{1,128}$")

    fun safeToken(value: String?): String? = value?.trim()?.takeIf { SAFE.matches(it) }
}

fun ServerWebExchange.caller(): ApiKey =
    attributes[GatewayAttributes.CALLER] as? ApiKey ?: throw UnauthorizedException()

fun ServerWebExchange.requestId(): String =
    attributes[GatewayAttributes.REQUEST_ID] as? String ?: "unknown"
