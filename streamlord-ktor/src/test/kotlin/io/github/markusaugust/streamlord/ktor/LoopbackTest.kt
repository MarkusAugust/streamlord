package io.github.markusaugust.streamlord.ktor

import io.ktor.server.cio.CIO
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.netty.Netty
import io.ktor.server.tomcat.jakarta.Tomcat
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * On macOS a server on every address may share its port with one another process binds to the
 * loopback address, and a client of the loopback address then reaches that one and waits out its
 * timeout: the 20 s TomcatWriterTest once took. The servers the tests start are on the loopback
 * address alone, so on a port taken there they fail to start rather than serve another's client.
 */
class LoopbackTest {
    @Test
    fun `a test's client reaches its own server on every engine, though another holds the port on the loopback address`() {
        val reached =
            listOf(CIO, Netty, Jetty, Tomcat).filter { engine ->
                ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { other ->
                    other.soTimeout = 200
                    runCatching { serving(engine, {}, other.localPort) { port -> Socket(LOOPBACK, port).close() } }
                    runCatching { other.accept().close() }.isSuccess
                }
            }
        assertEquals(emptyList(), reached.map { it.javaClass.simpleName })
    }
}
