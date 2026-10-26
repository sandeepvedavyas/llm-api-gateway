package com.sandeepvedavyas.llmgateway.observability

import io.micrometer.context.ThreadLocalAccessor
import org.slf4j.MDC

/**
 * Copies the request id from the Reactor context onto the SLF4J MDC of
 * whatever thread runs the operator, so log lines keep a correlation id
 * without logging the prompt.
 */
class RequestIdThreadLocalAccessor : ThreadLocalAccessor<String> {
    override fun key(): Any = KEY

    override fun getValue(): String? = MDC.get(MDC_KEY)

    override fun setValue(value: String) {
        MDC.put(MDC_KEY, value)
    }

    override fun setValue() {
        MDC.remove(MDC_KEY)
    }

    @Deprecated("Use setValue() with no arguments.")
    override fun reset() {
        setValue()
    }

    companion object {
        const val KEY = "requestId"
        const val MDC_KEY = "requestId"
    }
}
