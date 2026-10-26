package com.sandeepvedavyas.llmgateway.provider.openai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sandeepvedavyas.llmgateway.config.GatewayProperties
import com.sandeepvedavyas.llmgateway.domain.ProviderNotConfiguredException
import com.sandeepvedavyas.llmgateway.provider.UpstreamContext
import com.sandeepvedavyas.llmgateway.provider.UpstreamException
import java.time.Duration
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.test.StepVerifier

class OpenAiChatProviderTest {
    private val mapper = ObjectMapper()
    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `forwards the upstream key and reads token usage`() {
        server.enqueue(json(success("gpt-4o-mini")))

        val result = provider().complete(request(), context()).block(Duration.ofSeconds(5))

        assertThat(result!!.promptTokens).isEqualTo(9)
        assertThat(result.completionTokens).isEqualTo(4)
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-upstream")
        assertThat(recorded.getHeader("X-Request-Id")).isEqualTo("req-1")
        assertThat(recorded.path).isEqualTo("/v1/chat/completions")
        val sent = mapper.readTree(recorded.body.readUtf8())
        assertThat(sent.path("stream").booleanValue()).isFalse()
        assertThat(sent.path("temperature").asDouble()).isEqualTo(0.2)
    }

    @Test
    fun `retries a 503 and then returns the successful response`() {
        server.enqueue(error(503, "unavailable"))
        server.enqueue(json(success("gpt-4o-mini")))

        val result = provider(maxRetries = 1).complete(request(), context()).block(Duration.ofSeconds(5))

        assertThat(result).isNotNull()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `stops after the configured number of retries`() {
        repeat(3) { server.enqueue(error(503, "down")) }

        StepVerifier.create(provider(maxRetries = 2).complete(request(), context()))
            .expectError(UpstreamException::class.java)
            .verify(Duration.ofSeconds(5))

        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `does not retry a caller error`() {
        server.enqueue(error(400, "bad model"))

        StepVerifier.create(provider(maxRetries = 2).complete(request(), context()))
            .expectErrorSatisfies { error ->
                val upstream = error as UpstreamException
                assertThat(upstream.status?.value()).isEqualTo(400)
                assertThat(upstream.message).contains("bad model")
                assertThat(upstream.retryable).isFalse()
            }
            .verify(Duration.ofSeconds(5))

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `does not retry a read timeout`() {
        server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("{}"))

        StepVerifier.create(
            provider(maxRetries = 2, responseTimeout = Duration.ofMillis(200)).complete(request(), context()),
        )
            .expectErrorSatisfies { error ->
                val upstream = error as UpstreamException
                assertThat(upstream.retryable).isFalse()
                assertThat(upstream.status?.value()).isEqualTo(504)
            }
            .verify(Duration.ofSeconds(5))

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `missing provider key fails before any http call`() {
        StepVerifier.create(provider(apiKey = " ").complete(request(), context()))
            .expectError(ProviderNotConfiguredException::class.java)
            .verify(Duration.ofSeconds(5))

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `streaming injects include_usage and forwards an idempotency key`() {
        val body = """
            data: {"choices":[{"delta":{"content":"Hi"}}]}

            data: {"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}

            data: [DONE]

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )

        val events = provider().stream(request(), context("idem-1")).collectList().block(Duration.ofSeconds(5))

        assertThat(events).hasSize(3)
        assertThat(events!![1].promptTokens).isEqualTo(3)
        assertThat(events[2].done).isTrue()
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Idempotency-Key")).isEqualTo("idem-1")
        val sent = mapper.readTree(recorded.body.readUtf8())
        assertThat(sent.path("stream").booleanValue()).isTrue()
        assertThat(sent.path("stream_options").path("include_usage").booleanValue()).isTrue()
    }

    @Test
    fun `v1 base urls are not doubled`() {
        assertThat(chatCompletionsPath("http://localhost:11434/v1")).isEqualTo("/chat/completions")
        assertThat(chatCompletionsPath("https://api.openai.com")).isEqualTo("/v1/chat/completions")
        assertThat(chatCompletionsPath("https://api.openai.com/")).isEqualTo("/v1/chat/completions")
    }

    private fun provider(
        apiKey: String = "sk-upstream",
        maxRetries: Long = 2,
        responseTimeout: Duration = Duration.ofSeconds(5),
    ): OpenAiChatProvider {
        val properties = GatewayProperties().apply {
            provider.baseUrl = server.url("/").toString().trimEnd('/')
            provider.apiKey = apiKey
            provider.maxRetries = maxRetries
            provider.retryInitialBackoff = Duration.ofMillis(5)
            provider.responseTimeout = responseTimeout
            provider.connectTimeout = Duration.ofSeconds(1)
        }
        val httpClient = HttpClient.create().responseTimeout(responseTimeout)
        val client = WebClient.builder()
            .baseUrl(properties.provider.baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
        return OpenAiChatProvider(properties, client, mapper)
    }

    private fun request(): ObjectNode {
        return mapper.readTree(
            """
            {
              "model": "gpt-4o-mini",
              "temperature": 0.2,
              "messages": [{"role": "user", "content": "hi"}]
            }
            """.trimIndent(),
        ) as ObjectNode
    }

    private fun context(idempotencyKey: String? = null) = UpstreamContext("req-1", idempotencyKey)

    private fun success(model: String): String {
        return """
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "model": "$model",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "hello"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 9, "completion_tokens": 4, "total_tokens": 13}
            }
        """.trimIndent()
    }

    private fun json(body: String): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private fun error(status: Int, message: String): MockResponse {
        return MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"error":{"message":"$message"}}""")
    }
}
