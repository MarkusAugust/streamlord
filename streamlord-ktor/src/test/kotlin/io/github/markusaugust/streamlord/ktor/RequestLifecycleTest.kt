package io.github.markusaugust.streamlord.ktor

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.netty.Netty
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ktor's HttpRequestLifecycle, which came in Ktor 3.4.0, on real CIO and Netty servers. Kept apart from
 * the other tests so that testOldestKtor can leave it out: its class does not load on an older
 * Ktor.
 */
class RequestLifecycleTest {
    // Ktor's own plugin, on the route: what the Ktor page tells a reader to install.
    @Test
    fun `HttpRequestLifecycle on the route cancels a stream whose client left`() = cancels(CIO)

    @Test
    fun `HttpRequestLifecycle cancels a stream on Netty too`() = cancels(Netty)

    private fun <E : ApplicationEngine, C : ApplicationEngine.Configuration> cancels(engine: ApplicationEngineFactory<E, C>) {
        val waiting = CompletableFuture<Unit>()
        val ended = CompletableFuture<Throwable>()
        val server =
            embeddedServer(engine, port = 0) {
                routing {
                    route("/idle") {
                        install(HttpRequestLifecycle) { cancelCallOnClose = true }
                        get {
                            call.respondDatastar(heartbeat = null) {
                                try {
                                    waiting.complete(Unit)
                                    awaitCancellation()
                                } catch (e: Throwable) {
                                    ended.complete(e)
                                    throw e
                                }
                            }
                        }
                    }
                }
            }.start(wait = false)
        val port =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
        try {
            Socket("localhost", port).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().write("GET /idle HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                // Left only once the block waits: a cancel that came before it would never be seen here.
                // Not the headers: Netty sends none before the first byte of the body.
                waiting.get(10, TimeUnit.SECONDS)
            }

            // About 200 ms on a desktop; the bound is for a busy CI runner, not a measure of speed.
            assertTrue(ended.get(10, TimeUnit.SECONDS) is CancellationException)
        } finally {
            server.stop(100, 500)
        }
    }
}
