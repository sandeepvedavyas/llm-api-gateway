package com.sandeepvedavyas.llmgateway.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SecretEqualsTest {
    @Test
    fun `matches equal secrets and rejects different lengths`() {
        assertThat(SecretEquals.equals("local-dev-key", "local-dev-key")).isTrue()
        assertThat(SecretEquals.equals("local-dev-key", "local-dev-key-extra")).isFalse()
        assertThat(SecretEquals.equals("local-dev-key", "local-dev-kez")).isFalse()
    }
}
