package com.sandeepvedavyas.llmgateway.domain.ratelimit

import com.sandeepvedavyas.llmgateway.support.MutableClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TokenBucketRateLimiterTest {
    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val limiter = TokenBucketRateLimiter(clock)

    @Test
    fun `allows a burst up to the per-minute capacity and then rejects`() {
        val decisions = (1..4).map { limiter.tryAcquire("local", 3) }

        assertThat(decisions.take(3)).allMatch { it.allowed }
        assertThat(decisions[2].remaining).isZero()
        assertThat(decisions[3].allowed).isFalse()
        assertThat(decisions[3].retryAfter).isEqualTo(Duration.ofSeconds(20))
    }

    @Test
    fun `refills one token after the steady-rate interval`() {
        repeat(3) { limiter.tryAcquire("local", 3) }

        clock.advance(Duration.ofSeconds(20))
        val refilled = limiter.tryAcquire("local", 3)

        assertThat(refilled.allowed).isTrue()
        assertThat(refilled.remaining).isZero()
    }

    @Test
    fun `buckets are independent per key`() {
        repeat(2) { limiter.tryAcquire("a", 2) }

        val other = limiter.tryAcquire("b", 2)

        assertThat(other.allowed).isTrue()
        assertThat(other.remaining).isEqualTo(1)
    }

    @Test
    fun `concurrent callers cannot exceed capacity`() {
        val threads = 32
        val capacity = 5
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val allowed = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.submit {
                start.await()
                if (limiter.tryAcquire("shared", capacity).allowed) {
                    allowed.incrementAndGet()
                }
                done.countDown()
            }
        }

        start.countDown()
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()

        assertThat(allowed.get()).isEqualTo(capacity)
    }
}
