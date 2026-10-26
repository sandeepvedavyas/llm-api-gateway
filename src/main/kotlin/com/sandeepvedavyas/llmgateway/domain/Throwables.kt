package com.sandeepvedavyas.llmgateway.domain

import reactor.core.Exceptions

fun unwrapGateway(error: Throwable): Throwable {
    val unwrapped = Exceptions.unwrap(error)
    if (Exceptions.isRetryExhausted(unwrapped)) {
        return unwrapped.cause ?: unwrapped
    }
    return unwrapped
}
