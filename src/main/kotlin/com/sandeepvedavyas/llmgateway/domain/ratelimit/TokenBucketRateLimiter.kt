package com.sandeepvedavyas.llmgateway.domain.ratelimit

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

data class RateLimitDecision(
    val allowed: Boolean,
    val limit: Int,
    val remaining: Int,
    val retryAfter: Duration,
)

/**
 * In-process token bucket. Capacity and refill rate are both the per-key
 * requests-per-minute budget, so a quiet caller can burst up to one minute
 * of traffic and then settle at a steady rate. One gateway instance only —
 * a second replica has its own buckets.
 */
@Component
class TokenBucketRateLimiter(
    private val clock: Clock,
) {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(keyId: String, permitsPerMinute: Int): RateLimitDecision {
        require(permitsPerMinute in 1..MAX_PERMITS_PER_MINUTE) {
            "permitsPerMinute must be between 1 and $MAX_PERMITS_PER_MINUTE"
        }
        val nowNanos = nanos(clock)
        val captured = arrayOfNulls<RateLimitDecision>(1)
        buckets.compute(keyId) { _, existing ->
            val bucket = existing ?: Bucket(
                tokensMicros = permitsPerMinute * MICROS_PER_TOKEN,
                lastRefillEpochNanos = nowNanos,
            )
            refill(bucket, permitsPerMinute, nowNanos)
            captured[0] = if (bucket.tokensMicros >= MICROS_PER_TOKEN) {
                bucket.tokensMicros -= MICROS_PER_TOKEN
                RateLimitDecision(
                    allowed = true,
                    limit = permitsPerMinute,
                    remaining = (bucket.tokensMicros / MICROS_PER_TOKEN).toInt(),
                    retryAfter = Duration.ZERO,
                )
            } else {
                RateLimitDecision(
                    allowed = false,
                    limit = permitsPerMinute,
                    remaining = 0,
                    retryAfter = retryAfter(bucket.tokensMicros, permitsPerMinute),
                )
            }
            bucket
        }
        return checkNotNull(captured[0])
    }

    private fun refill(bucket: Bucket, permitsPerMinute: Int, nowNanos: Long) {
        val elapsed = nowNanos - bucket.lastRefillEpochNanos
        if (elapsed <= 0) {
            return
        }
        if (elapsed >= FULL_REFILL_WINDOW_NANOS) {
            bucket.tokensMicros = permitsPerMinute * MICROS_PER_TOKEN
            bucket.lastRefillEpochNanos = nowNanos
            return
        }
        val added = elapsed * permitsPerMinute / NANOS_PER_MICRO_TOKEN
        if (added <= 0) {
            return
        }
        val capacity = permitsPerMinute * MICROS_PER_TOKEN
        bucket.tokensMicros = minOf(capacity, bucket.tokensMicros + added)
        bucket.lastRefillEpochNanos = nowNanos
    }

    private fun retryAfter(tokensMicros: Long, permitsPerMinute: Int): Duration {
        val deficit = MICROS_PER_TOKEN - tokensMicros
        val nanos = deficit * NANOS_PER_MICRO_TOKEN / permitsPerMinute
        val seconds = maxOf(1L, (nanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND)
        return Duration.ofSeconds(seconds)
    }

    private class Bucket(
        var tokensMicros: Long,
        var lastRefillEpochNanos: Long,
    )

    companion object {
        const val MAX_PERMITS_PER_MINUTE = 100_000
        private const val MICROS_PER_TOKEN = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L

        // addedMicros = elapsedNanos * permitsPerMinute / NANOS_PER_MICRO_TOKEN
        // 1e9 nanos * 60 permits / 60_000 = 1e6 micros = 1 token. See tests.
        private const val NANOS_PER_MICRO_TOKEN = 60_000L
        private const val FULL_REFILL_WINDOW_NANOS = 120L * NANOS_PER_SECOND

        fun nanos(clock: Clock): Long {
            val instant = clock.instant()
            return instant.epochSecond * NANOS_PER_SECOND + instant.nano
        }
    }
}
