package com.sandeepvedavyas.llmgateway.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ApiKeyParserTest {
    @Test
    fun `parses a compact multi-key value`() {
        val keys = ApiKeyParser.parse("local|local-secret|60, batch|batch-secret|10")

        assertThat(keys).containsExactly(
            ApiKey("local", "local-secret", 60),
            ApiKey("batch", "batch-secret", 10),
        )
    }

    @Test
    fun `blank input is an empty list`() {
        assertThat(ApiKeyParser.parse("  ")).isEmpty()
    }

    @Test
    fun `rejects a malformed entry`() {
        assertThatThrownBy { ApiKeyParser.parse("only-an-id") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("id|secret|requestsPerMinute")
    }
}
