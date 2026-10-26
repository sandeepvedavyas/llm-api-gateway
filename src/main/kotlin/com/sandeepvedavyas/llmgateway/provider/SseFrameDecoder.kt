package com.sandeepvedavyas.llmgateway.provider

import com.fasterxml.jackson.databind.ObjectMapper
import reactor.core.publisher.Flux

/**
 * Turns an upstream `text/event-stream` body into logical events. Chunk
 * boundaries are not frame boundaries, so the decoder keeps a buffer until
 * it sees a blank line.
 */
class SseFrameDecoder(
    private val objectMapper: ObjectMapper,
) {
    fun decode(chunks: Flux<String>): Flux<UpstreamEvent> {
        return Flux.defer {
            val pending = StringBuilder()
            chunks.concatMap { chunk ->
                pending.append(chunk)
                Flux.fromIterable(drain(pending))
            }.concatWith(Flux.defer {
                val rest = pending.toString()
                pending.setLength(0)
                val event = if (rest.isBlank()) null else parseFrame(rest, objectMapper)
                if (event == null) Flux.empty() else Flux.just(event)
            })
        }
    }

    private fun drain(pending: StringBuilder): List<UpstreamEvent> {
        val events = mutableListOf<UpstreamEvent>()
        while (true) {
            val separator = indexOfSeparator(pending) ?: break
            val raw = pending.substring(0, separator.index)
            pending.delete(0, separator.index + separator.length)
            parseFrame(raw, objectMapper)?.let { events.add(it) }
        }
        return events
    }

    companion object {
        internal fun parseFrame(raw: String, objectMapper: ObjectMapper): UpstreamEvent? {
            val data = raw.lineSequence()
                .map { line -> line.trimEnd('\r') }
                .filter { line -> line.startsWith("data:") }
                .map { line -> line.removePrefix("data:").trimStart() }
                .joinToString("\n")
            if (data.isEmpty()) {
                return null
            }
            if (data == "[DONE]") {
                return UpstreamEvent(data = data, done = true)
            }
            val usage = usageOf(data, objectMapper)
            return UpstreamEvent(
                data = data,
                promptTokens = usage?.first,
                completionTokens = usage?.second,
            )
        }

        private fun usageOf(data: String, objectMapper: ObjectMapper): Pair<Long, Long>? {
            if (!data.trimStart().startsWith("{")) {
                return null
            }
            val tree = try {
                objectMapper.readTree(data)
            } catch (_: Exception) {
                return null
            }
            val usage = tree.get("usage") ?: return null
            if (!usage.isObject) {
                return null
            }
            return usage.path("prompt_tokens").asLong(0) to usage.path("completion_tokens").asLong(0)
        }

        private fun indexOfSeparator(pending: StringBuilder): Separator? {
            val lf = pending.indexOf("\n\n")
            val crlf = pending.indexOf("\r\n\r\n")
            return when {
                lf < 0 && crlf < 0 -> null
                lf < 0 -> Separator(crlf, 4)
                crlf < 0 -> Separator(lf, 2)
                crlf < lf -> Separator(crlf, 4)
                else -> Separator(lf, 2)
            }
        }
    }

    private data class Separator(val index: Int, val length: Int)
}
