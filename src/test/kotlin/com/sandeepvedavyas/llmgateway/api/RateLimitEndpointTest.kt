package com.sandeepvedavyas.llmgateway.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test", "ratelimit")
class RateLimitEndpointTest @Autowired constructor(
    private val client: WebTestClient,
) {
    @Test
    fun `the second request inside the same minute is rejected`() {
        val body = """
            {"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}
        """.trimIndent()

        client.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer test-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk

        client.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer test-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectHeader().valueEquals("X-RateLimit-Remaining-Requests", "0")
            .expectHeader().exists("Retry-After")
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("rate_limit_exceeded")
    }
}
