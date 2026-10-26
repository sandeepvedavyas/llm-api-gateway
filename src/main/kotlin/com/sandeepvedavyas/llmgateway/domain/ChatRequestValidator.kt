package com.sandeepvedavyas.llmgateway.domain

import com.fasterxml.jackson.databind.JsonNode

data class ValidatedChatRequest(
    val model: String,
    val stream: Boolean,
    val messageCount: Int,
)

object ChatRequestValidator {
    private val roles = setOf("system", "user", "assistant", "tool", "developer")

    fun validate(body: JsonNode): ValidatedChatRequest {
        if (!body.isObject) {
            throw InvalidRequestException("request body must be a JSON object")
        }
        val model = body.path("model").asText("").trim()
        if (model.isEmpty()) {
            throw InvalidRequestException("model is required")
        }
        if (model.length > MAX_MODEL_LENGTH) {
            throw InvalidRequestException("model must be at most $MAX_MODEL_LENGTH characters")
        }
        val messages = body.get("messages")
            ?: throw InvalidRequestException("messages is required")
        if (!messages.isArray || messages.isEmpty) {
            throw InvalidRequestException("messages must be a non-empty array")
        }
        if (messages.size() > MAX_MESSAGES) {
            throw InvalidRequestException("messages exceeds the maximum of $MAX_MESSAGES")
        }
        messages.forEach { message ->
            if (!message.isObject) {
                throw InvalidRequestException("each message must be an object")
            }
            val role = message.path("role").asText("")
            if (role !in roles) {
                throw InvalidRequestException("unsupported message role '$role'")
            }
            val hasContent = message.hasNonNull("content")
            val hasToolCalls = message.hasNonNull("tool_calls")
            if (!hasContent && !hasToolCalls) {
                throw InvalidRequestException("each message needs content or tool_calls")
            }
        }
        return ValidatedChatRequest(
            model = model,
            stream = readStream(body),
            messageCount = messages.size(),
        )
    }

    private fun readStream(body: JsonNode): Boolean {
        val stream = body.get("stream") ?: return false
        if (stream.isNull) {
            return false
        }
        if (!stream.isBoolean) {
            throw InvalidRequestException("stream must be a boolean")
        }
        return stream.booleanValue()
    }

    const val MAX_MODEL_LENGTH = 128
    const val MAX_MESSAGES = 500
}
