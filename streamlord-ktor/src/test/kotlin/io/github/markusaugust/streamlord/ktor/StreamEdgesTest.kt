package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How a stream on a real CIO server answers a closing request, a failure, and a call already answered. */
class StreamEdgesTest {
    private fun <T> serving(
        module: Application.() -> Unit,
        test: (port: Int) -> T,
    ): T {
        val server = embeddedServer(CIO, port = 0, module = module).start(wait = false)
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

    // Ktor closes the channel with the failure, and CIO then dropped what was written and not yet
    // sent. Without a heartbeat nothing gave it the time to go out.
    @Test
    fun `a frame sent before a failure reaches the client, and the stream is then cut`() =
        serving({
            routing {
                get("/fail") {
                    call.respondDatastar(heartbeat = null) {
                        patchSignals("n" to 1)
                        error("after the first frame")
                    }
                }
            }
        }) { port ->
            repeat(10) {
                val response = raw(port, "/fail")
                assertTrue("datastar-patch-signals" in response, "run $it lost the frame:\n$response")
                assertFalse(response.endsWith("0\r\n\r\n"), "run $it ended as if it had finished:\n$response")
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
