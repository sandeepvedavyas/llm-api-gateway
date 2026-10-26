package com.sandeepvedavyas.llmgateway.auth

import java.security.MessageDigest

/**
 * Compares API keys by SHA-256 digest so the check does not stop at the first
 * differing character of the raw secret. Digest length is fixed, which keeps
 * [MessageDigest.isEqual] from leaking the secret length.
 */
object SecretEquals {
    fun equals(left: String, right: String): Boolean {
        val leftDigest = sha256(left)
        val rightDigest = sha256(right)
        return MessageDigest.isEqual(leftDigest, rightDigest)
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
