package io.github.markusaugust.streamlord.spring

import org.springframework.context.annotation.Configuration
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * On macOS a server on every address may share its port with one another process binds to the
 * loopback address, and a client of the loopback address then reaches that one and waits out its
 * timeout. The Tomcats the tests start are on the loopback address alone, so on a port taken there
 * they fail to start rather than serve another's client.
 */
class LoopbackTest {
    @Configuration
    class Empty

    @Test
    fun `a test's client reaches its own Tomcat, though another holds the port on the loopback address`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { other ->
            other.soTimeout = 200
            runCatching { RealTomcat(Empty::class.java, listenOn = other.localPort).use { Socket(LOOPBACK, it.port).close() } }
            val reached = runCatching { other.accept().close() }.isSuccess
            assertTrue(!reached, "the test's client reached the other server on port ${other.localPort}")
        }
    }
}
