package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol.acceptsGzip
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcceptsGzipTest {
    @Test
    fun `gzip by name or by star, without q=0`() {
        assertTrue(acceptsGzip("gzip, deflate, br, zstd"))
        assertTrue(acceptsGzip("GZIP"))
        assertTrue(acceptsGzip("br;q=1.0, gzip;q=0.8"))
        assertTrue(acceptsGzip("*"))
        assertTrue(acceptsGzip("x-gzip"))
    }

    @Test
    fun `not when absent, refused, or refused by name despite a star`() {
        assertFalse(acceptsGzip(null))
        assertFalse(acceptsGzip(""))
        assertFalse(acceptsGzip("br, deflate"))
        assertFalse(acceptsGzip("gzip;q=0"))
        assertFalse(acceptsGzip("*, gzip;q=0"))
        assertFalse(acceptsGzip("*;q=0"))
        assertFalse(acceptsGzip("identity"))
    }
}
