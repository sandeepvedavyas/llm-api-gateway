package com.sandeepvedavyas.llmgateway.auth

object ApiKeyParser {
    fun parse(raw: String): List<ApiKey> {
        if (raw.isBlank()) {
            return emptyList()
        }
        return raw.split(',').map { entry -> parseEntry(entry.trim()) }
    }

    private fun parseEntry(entry: String): ApiKey {
        val parts = entry.split('|')
        if (parts.size != 3) {
            throw IllegalArgumentException(
                "GATEWAY_API_KEYS entries must look like id|secret|requestsPerMinute",
            )
        }
        val rpm = parts[2].trim().toIntOrNull()
            ?: throw IllegalArgumentException("GATEWAY_API_KEYS requestsPerMinute must be an integer")
        return ApiKey(
            id = parts[0].trim(),
            secret = parts[1].trim(),
            requestsPerMinute = rpm,
        )
    }
}
