package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Streams that write nothing for a while, on real engines over real sockets. */
class IdleStreamTest {
    private fun <E : ApplicationEngine, C : ApplicationEngine.Configuration> serve(
        engine: ApplicationEngineFactory<E, C>,
        module: Application.() -> Unit,
    ): EmbeddedServer<E, C> = embeddedServer(engine, port = 0, module = module).start(wait = false)

    private val EmbeddedServer<*, *>.port: Int get() = runBlocking { engine.resolvedConnectors().first().port }

    /** What a socket reads in [seconds], and whether the server closed it before then. */
    private fun listen(
        port: Int,
        seconds: Int,
    ): Pair<String, Boolean> =
        Socket("localhost", port).use { socket ->
            socket.getOutputStream().write("GET /idle HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            socket.soTimeout = 250
            val text = StringBuilder()
            val until = System.nanoTime() + seconds * 1_000_000_000L
            val buffer = ByteArray(4096)
            while (System.nanoTime() < until) {
                val read =
                    try {
                        socket.getInputStream().read(buffer)
                    } catch (_: SocketTimeoutException) {
                        0
                    }
                if (read < 0) return text.toString() to true
                text.append(String(buffer, 0, read))
            }
            text.toString() to false
        }

    // Netty sends the response headers with the first byte of the body and gives up on a response
    // that has written nothing for ten seconds. The default heartbeat's first comment comes first.
    @Test
    fun `an idle stream on Netty outlives the engine's write timeout`() {
        val server = serve(Netty) { routing { get("/idle") { call.respondDatastar { awaitCancellation() } } } }
        try {
            val (text, closed) = listen(server.port, 12)

            assertEquals(false, closed, "Netty closed the stream:\n$text")
            assertTrue(": keep-alive" in text, "no keep-alive comment:\n$text")
        } finally {
            server.stop(100, 500)
        }
    }

    @Test
    fun `the plugin's heartbeat is the default`() {
        assertEquals(Streamlord.DEFAULT_HEARTBEAT, StreamlordPluginConfig().heartbeat)
    }
}
