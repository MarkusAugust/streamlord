package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.tomcat.jakarta.Tomcat
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How a stream on a real server answers a closing request, a failure, and a call already answered. */
class StreamEdgesTest {
    private fun <T> serving(
        module: Application.() -> Unit,
        test: (port: Int) -> T,
    ): T = serving(CIO, module, test)

    private fun <T, E : ApplicationEngine, C : ApplicationEngine.Configuration> serving(
        engine: ApplicationEngineFactory<E, C>,
        module: Application.() -> Unit,
        test: (port: Int) -> T,
    ): T {
        val server = embeddedServer(engine, port = 0, module = module).start(wait = false)
        try {
            return test(
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                },
            )
        } finally {
            server.stop(100, 500)
        }
    }

    /** The raw bytes of a GET until the server closes the connection or goes quiet. */
    private fun raw(
        port: Int,
        path: String,
        headers: String = "",
    ): String =
        Socket("localhost", port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n$headers\r\n".toByteArray())
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val n = socket.getInputStream().read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            } catch (_: java.io.IOException) {
            }
            out.toString(Charsets.ISO_8859_1)
        }

    // Ktor closes the channel with the failure, and the engine then dropped what it had not yet
    // sent: CIO the last frame, or the whole response, and Netty often ended it as if it had
    // finished. Without a heartbeat nothing gave the engine the time.
    @Test
    fun `a failure after a frame delivers it and cuts the stream, on CIO`() = failsCleanly(CIO)

    @Test
    fun `a failure after a frame delivers it and cuts the stream, on Netty`() = failsCleanly(Netty)

    // Both end a failed body with its last chunk, as if it had finished, unless the connection is
    // cut under them; see EngineCut.kt. On the oldest Ktor, Jetty is Jetty 11 behind a servlet.
    @Test
    fun `a failure after a frame delivers it and cuts the stream, on Tomcat`() = failsCleanly(Tomcat)

    @Test
    fun `a failure after a frame delivers it and cuts the stream, on Jetty`() = failsCleanly(Jetty)

    private fun <E : ApplicationEngine, C : ApplicationEngine.Configuration> failsCleanly(engine: ApplicationEngineFactory<E, C>) =
        serving(engine, {
            // Gzipped only for a request that accepts it.
            install(StreamlordPlugin) { compress = true }
            routing {
                get("/after") {
                    call.respondDatastar(heartbeat = null) {
                        patchSignals("n" to 1)
                        error("after the first frame")
                    }
                }
                get("/before") { call.respondDatastar(heartbeat = null) { error("before anything was written") } }
                get("/ok") { call.respondDatastar(heartbeat = null) { patchSignals("n" to 1) } }
            }
        }) { port ->
            val ok = raw(port, "/ok")
            assertTrue(ok.endsWith("0\r\n\r\n"), "a stream that ended well did not end as finished:\n$ok")
            // A browser takes a gzip member left unterminated for a whole one when the body ends.
            val gzipped = raw(port, "/after", "Accept-Encoding: gzip\r\n")
            assertTrue("content-encoding: gzip" in gzipped.lowercase(), gzipped)
            assertFalse(gzipped.endsWith("0\r\n\r\n"), "the gzipped stream ended as if it had finished")
            repeat(50) {
                val after = raw(port, "/after")
                assertTrue("datastar-patch-signals" in after, "run $it lost the frame:\n$after")
                assertFalse(after.endsWith("0\r\n\r\n"), "run $it ended as if it had finished:\n$after")
                // Cut or closed, never ended as if finished nor left open: Netty on Ktor 3.0 sends
                // nothing before the first byte of the body, and closes the connection instead.
                val started = System.nanoTime()
                val before = raw(port, "/before")
                assertTrue((System.nanoTime() - started) / 1_000_000 < 2000, "run $it left the connection open:\n$before")
                assertFalse(before.endsWith("0\r\n\r\n"), "run $it ended as if it had finished:\n$before")
            }
        }

    @Test
    fun `a request that asks to close is not told keep-alive`() =
        serving({ routing { get("/s") { call.respondDatastar(heartbeat = null) { patchSignals("n" to 1) } } } }) { port ->
            val response = raw(port, "/s", "Connection: close\r\n")
            assertTrue("datastar-patch-signals" in response, response)
            assertFalse("connection: keep-alive" in response.lowercase(), response)
        }

    @Test
    fun `a call already answered cannot become a stream`() {
        val failure = CompletableFuture<Throwable?>()
        serving({
            routing {
                get("/twice") {
                    call.respond(HttpStatusCode.NoContent)
                    failure.complete(runCatching { call.respondDatastar { patchSignals("n" to 1) } }.exceptionOrNull())
                }
            }
        }) { port -> raw(port, "/twice") }
        assertTrue(failure.get(5, TimeUnit.SECONDS) is IllegalStateException, "${failure.get()}")
    }
}
