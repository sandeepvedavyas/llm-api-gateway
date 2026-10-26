package com.sandeepvedavyas.llmgateway.provider

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

class SseFrameDecoderTest {
    private val decoder = SseFrameDecoder(ObjectMapper())

    @Test
    fun `reassembles frames that arrive split across chunks`() {
        val chunks = Flux.just(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n",
            "\n",
            "data: {\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}\r\n\r\n",
            "data: [DONE]\n\n",
        )

        val events = decoder.decode(chunks).collectList().block()

        assertThat(events).hasSize(3)
        assertThat(events!![0].promptTokens).isNull()
        assertThat(events[0].data).contains("Hi")
        assertThat(events[1].promptTokens).isEqualTo(3)
        assertThat(events[1].completionTokens).isEqualTo(1)
        assertThat(events[2].done).isTrue()
    }
}
