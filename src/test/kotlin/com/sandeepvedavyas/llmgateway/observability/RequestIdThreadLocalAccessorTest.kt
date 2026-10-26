package com.sandeepvedavyas.llmgateway.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class RequestIdThreadLocalAccessorTest {
    private val accessor = RequestIdThreadLocalAccessor()

    @Test
    fun `copies the request id into the slf4j mdc and clears it`() {
        accessor.setValue("req-123")
        assertThat(MDC.get("requestId")).isEqualTo("req-123")
        assertThat(accessor.getValue()).isEqualTo("req-123")

        accessor.setValue()
        assertThat(MDC.get("requestId")).isNull()
    }
}
