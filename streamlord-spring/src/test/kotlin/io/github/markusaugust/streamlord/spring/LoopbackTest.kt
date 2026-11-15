package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.wire.answers
import io.github.markusaugust.streamlord.wire.otherAddresses
import org.springframework.context.annotation.Configuration
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Tomcats the tests start listen on the loopback address alone: Loopback.kt in conformance/wire has why. */
class LoopbackTest {
    @Configuration
    class Empty

    @Test
    fun `a test's Tomcat answers at no other address of this machine`() {
        val others = otherAddresses()
        assertTrue(others.isNotEmpty(), "no address but the loopback one answers a server on every address, so this proves nothing")
        RealTomcat(Empty::class.java).use { tomcat ->
            assertTrue(others.none { answers(it, tomcat.port) }, "reached at one of $others")
        }
    }
}
