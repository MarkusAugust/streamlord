package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.CspNonce
import kotlin.test.Test
import kotlin.test.assertEquals

class CspNonceJvmTest {
    @Test
    fun `a generated nonce is base64 and never repeats`() {
        val nonces = List(1000) { CspNonce.generate() }

        assertEquals(1000, nonces.toSet().size, "a nonce that repeats is a constant")
        for (nonce in nonces) {
            assertEquals(nonce, CspNonce.checked(nonce))
            assertEquals(24, nonce.length, "16 bytes, base64, padded")
        }
    }
}
