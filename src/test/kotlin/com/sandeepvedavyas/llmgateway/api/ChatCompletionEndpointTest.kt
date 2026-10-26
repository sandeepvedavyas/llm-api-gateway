package com.sandeepvedavyas.llmgateway.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sandeepvedavyas.llmgateway.domain.usage.CostCalculator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class ChatCompletionEndpointTest @Autowired constructor(
    private val client: WebTestClient,
    private val costCalculator: CostCalculator,
    private val objectMapper: ObjectMapper,
) {
    @Test
    fun `health, docs, and the service index do not require a caller key`() {
        client.get().uri("/actuator/health").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
            .jsonPath("$.components.upstreamProvider.details.upstream").isEqualTo("configured")

        client.get().uri("/").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.service").isEqualTo("llm-api-gateway")

        client.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
    }

    @Test
    fun `chat requires a gateway key and records usage for that key only`() {
        client.post().uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(chatBody(stream = false))
            .exchange()
            .expectStatus().isUnauthorized
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("invalid_api_key")

        client.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer wrong-key")
            .header("X-Request-Id", "trace-123")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(chatBody(stream = false))
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().valueEquals("X-Request-Id", "trace-123")

        val before = usage()
        client.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer test-key")
            .header("X-Request-Id", "trace-ok")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(chatBody(stream = false))
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-Request-Id", "trace-ok")
            .expectHeader().exists("X-RateLimit-Limit-Requests")
            .expectBody()
            .jsonPath("$.choices[0].message.content").isEqualTo("hello")
            .jsonPath("$.usage.total_tokens").isEqualTo(18)

        val after = usage()
        assertThat(after.path("api_key_id").asText()).isEqualTo("test")
        assertThat(longField(after, "request_count") - longField(before, "request_count")).isEqualTo(1)
        assertThat(longField(after, "prompt_tokens") - longField(before, "prompt_tokens")).isEqualTo(11)
        assertThat(longField(after, "completion_tokens") - longField(before, "completion_tokens")).isEqualTo(7)
        assertThat(after.findValuesAsText("model")).contains("gpt-4o-mini")
        assertThat(after.path("estimated_cost_usd").asText()).isNotBlank()
    }

    @Test
    fun `streaming responses are server sent events and count toward usage`() {
        val before = usage()
        client.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer test-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(chatBody(stream = true))
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String::class.java)
            .consumeWith { result ->
                val body = result.responseBody.orEmpty()
                assertThat(body).contains("data: ")
                assertThat(body).contains("hello")
                assertThat(body).contains("data: [DONE]")
            }

        val after = usage()
        assertThat(longField(after, "prompt_tokens") - longField(before, "prompt_tokens")).isEqualTo(4)
        assertThat(longField(after, "completion_tokens") - longField(before, "completion_tokens")).isEqualTo(1)
    }

    @Test
    fun `invalid chat bodies are a 400 and unknown routes with a key are a 404`() {
        client.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer test-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"","messages":[]}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error.type").isEqualTo("invalid_request_error")
            .jsonPath("$.error.request_id").isNotEmpty

        client.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer test-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{")
            .exchange()
            .expectStatus().isBadRequest

        client.get().uri("/v1/does-not-exist")
            .header("Authorization", "Bearer test-key")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.error.message").isEqualTo("No such route")
    }

    @Test
    fun `model list uses the configured price table including dotted ids`() {
        assertThat(costCalculator.priceFor("gpt-4.1").inputPerMillion.toPlainString()).isEqualTo("2.00")
        assertThat(costCalculator.priceFor("gpt-4o-mini-2024-07-18").inputPerMillion.toPlainString()).isEqualTo("0.15")

        client.get().uri("/v1/models")
            .header("Authorization", "Bearer test-key")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.object").isEqualTo("list")
            .jsonPath("$.data[?(@.id == 'gpt-4.1')].owned_by").isEqualTo("upstream")
            .jsonPath("$.data[?(@.id == 'gpt-4o-mini')].id").isEqualTo("gpt-4o-mini")
    }

    private fun usage(): JsonNode {
        val body = client.get().uri("/v1/usage")
            .header("Authorization", "Bearer test-key")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
        return objectMapper.readTree(body)
    }

    private fun longField(node: JsonNode, field: String): Long = node.path(field).asLong()

    private fun chatBody(stream: Boolean) = """
        {
          "model": "gpt-4o-mini",
          "stream": $stream,
          "messages": [{"role": "user", "content": "hi"}]
        }
    """.trimIndent()
}
