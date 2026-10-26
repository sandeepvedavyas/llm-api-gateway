package com.sandeepvedavyas.llmgateway.config

import com.sandeepvedavyas.llmgateway.auth.ApiKeyRegistry
import com.sandeepvedavyas.llmgateway.observability.RequestIdThreadLocalAccessor
import com.sandeepvedavyas.llmgateway.provider.openai.chatCompletionsPath
import io.micrometer.context.ContextRegistry
import io.netty.channel.ChannelOption
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

@Configuration
class GatewayConfiguration(
    private val properties: GatewayProperties,
    private val apiKeyRegistry: ApiKeyRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun requestIdThreadLocalAccessor(): RequestIdThreadLocalAccessor {
        val accessor = RequestIdThreadLocalAccessor()
        ContextRegistry.getInstance().registerThreadLocalAccessor(accessor)
        return accessor
    }

    @Bean
    fun openAiWebClient(builder: WebClient.Builder): WebClient {
        val provider = properties.provider
        val connectMillis = provider.connectTimeout.toMillis().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMillis)
            .responseTimeout(provider.responseTimeout)
        return builder
            .baseUrl(provider.baseUrl.trim().trimEnd('/'))
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .defaultHeader(HttpHeaders.USER_AGENT, "llm-api-gateway/0.1.0")
            .codecs { codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES) }
            .build()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun logReady() {
        val provider = properties.provider
        log.info(
            "gateway ready keys={} providerConfigured={} baseUrl={} chatPath={}",
            apiKeyRegistry.ids(),
            provider.apiKey.isNotBlank(),
            provider.baseUrl.trim().trimEnd('/'),
            chatCompletionsPath(provider.baseUrl),
        )
    }

    companion object {
        const val MAX_IN_MEMORY_BYTES = 2 * 1024 * 1024
    }
}
