package io.github.markusaugust.streamlord.ktor

import io.ktor.server.cio.CIO
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.netty.Netty
import io.ktor.server.tomcat.jakarta.Tomcat
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * On macOS a server on every address may share its port with one another process binds to the
 * loopback address, and a client of the loopback address then reaches that one and waits out its
 * timeout: the 20 s TomcatWriterTest once took. The servers the tests start listen on the loopback
 * address alone, so this machine's other addresses reach none of them. (A server held to a port
 * another holds would show it too, but CIO reports the failed bind once more where a later test
 * of coroutines finds it.)
 */
class LoopbackTest {
    @Test
    fun `a test's server listens on the loopback address alone, on every engine`() {
        val other =
            NetworkInterface
                .networkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }
        assumeTrue(other != null, "this machine has no address but the loopback one")
        val reachable =
            listOf(CIO, Netty, Jetty, Tomcat).filter { engine ->
                serving(engine, {}) { port ->
                    runCatching { Socket().use { it.connect(InetSocketAddress(other, port), 2_000) } }.isSuccess
                }
            }
        assertEquals(emptyList(), reachable.map { it.javaClass.simpleName }, "reachable on $other")
    }
}
