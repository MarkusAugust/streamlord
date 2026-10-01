package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.CspNonce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CspNonceTest {
    @Test
    fun `a generated nonce is base64 and never repeats`() {
        val nonces = List(1000) { CspNonce.generate() }

        assertEquals(1000, nonces.toSet().size, "a nonce that repeats is a constant")
        for (nonce in nonces) {
            assertEquals(nonce, CspNonce.checked(nonce))
            assertEquals(24, nonce.length, "16 bytes, base64, padded")
        }
    }

    @Test
    fun `the starter policy carries the nonce where script-src looks for it`() {
        val policy = CspNonce.starterPolicy("abc123+/=")

        assertTrue(policy.contains("script-src 'self' 'nonce-abc123+/='"), policy)
        assertTrue(policy.contains("default-src 'self'"), policy)
    }

    @Test
    fun `an empty nonce is refused here rather than in the browser`() {
        val failure = assertFailsWith<IllegalArgumentException> { CspNonce.checked("") }

        assertTrue(failure.message!!.contains("NonceRequired"), failure.message!!)
    }

    @Test
    fun `a nonce that would break the header or the attribute is refused`() {
        // A space ends the source expression in the policy; a quote ends the HTML attribute;
        // a line break forges a second header.
        for (bad in listOf("two words", "quote\"d", "line\nbreak", "semi;colon", "tab\there")) {
            assertFailsWith<IllegalArgumentException>(bad) { CspNonce.checked(bad) }
        }
    }

    @Test
    fun `a nonce from another generator passes when it is a base64 value`() {
        for (good in listOf("deadbeef", "a-b_c", "QUJD", "QQ==")) {
            assertEquals(good, CspNonce.checked(good))
        }
    }
}
