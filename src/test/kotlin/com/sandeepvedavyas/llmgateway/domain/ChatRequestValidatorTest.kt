package com.sandeepvedavyas.llmgateway.domain

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChatRequestValidatorTest {
    private val mapper = ObjectMapper()

    @Test
    fun `accepts a minimal chat request`() {
        val body = mapper.readTree(
            """{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}""",
        )

        val request = ChatRequestValidator.validate(body)

        assertThat(request.model).isEqualTo("gpt-4o-mini")
        assertThat(request.stream).isFalse()
        assertThat(request.messageCount).isEqualTo(1)
    }

    @Test
    fun `rejects a missing model and a bad role`() {
        val missingModel = mapper.readTree("""{"messages":[{"role":"user","content":"hi"}]}""")
        val badRole = mapper.readTree(
            """{"model":"gpt-4o-mini","messages":[{"role":"function","content":"hi"}]}""",
        )

        assertThatThrownBy { ChatRequestValidator.validate(missingModel) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessageContaining("model")
        assertThatThrownBy { ChatRequestValidator.validate(badRole) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessageContaining("role")
    }

    @Test
    fun `requires stream to be boolean`() {
        val body = mapper.readTree(
            """{"model":"gpt-4o-mini","stream":"true","messages":[{"role":"user","content":"hi"}]}""",
        )

        assertThatThrownBy { ChatRequestValidator.validate(body) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessageContaining("stream")
    }
}
