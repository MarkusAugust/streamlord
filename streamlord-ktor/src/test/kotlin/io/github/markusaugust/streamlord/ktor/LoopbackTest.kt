package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.wire.answers
import io.github.markusaugust.streamlord.wire.otherAddresses
import io.ktor.server.cio.CIO
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.netty.Netty
import io.ktor.server.tomcat.jakarta.Tomcat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The servers the tests start listen on the loopback address alone: Loopback.kt in conformance/wire has why. */
class LoopbackTest {
    @Test
    fun `a test's server answers at no other address of this machine, on every engine`() {
        val others = otherAddresses()
        assertTrue(others.isNotEmpty(), "no address but the loopback one answers a server on every address, so this proves nothing")
        val reached =
            listOf(CIO, Netty, Jetty, Tomcat).filter { engine ->
                serving(engine, {}) { port -> others.any { answers(it, port) } }
            }
        assertEquals(emptyList(), reached.map { it.javaClass.simpleName }, "reached at one of $others")
    }
}
