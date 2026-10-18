package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Streams that write nothing for a while, on real engines over real sockets. */
class IdleStreamTest {
    private fun <E : ApplicationEngine, C : ApplicationEngine.Configuration> serve(
        engine: ApplicationEngineFactory<E, C>,
        configure: C.() -> Unit = {},
        module: Application.() -> Unit,
    ): EmbeddedServer<E, C> =
        embeddedServer(
            engine,
            configure = {
                connector { port = 0 }
                configure()
            },
            module = module,
        ).start(wait = false)

    private val EmbeddedServer<*, *>.port: Int get() = runBlocking { engine.resolvedConnectors().first().port }

    /** What a socket read, and when, from its connect, the first byte came and the server closed it. */
    private class Heard(
        val text: String,
        val firstByte: Duration?,
        val closedAt: Duration?,
    ) {
        val closed: Boolean get() = closedAt != null

        override fun toString() = "first byte at ${firstByte ?: "none"}, ${closedAt?.let { "closed at $it" } ?: "still open"}"
    }

    /** What a socket reads in [window]. A connection that fails counts as closed. */
    private fun listen(
        port: Int,
        window: Duration,
    ): Heard {
        val connected = TimeSource.Monotonic.markNow()
        val text = StringBuilder()
        var firstByte: Duration? = null
        try {
            Socket("localhost", port).use { socket ->
                socket.getOutputStream().write("GET /idle HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                socket.soTimeout = 250
                val buffer = ByteArray(4096)
                while (connected.elapsedNow() < window) {
                    val read =
                        try {
                            socket.getInputStream().read(buffer)
                        } catch (_: SocketTimeoutException) {
                            0
                        }
                    if (read < 0) return Heard(text.toString(), firstByte, connected.elapsedNow())
                    if (read > 0 && firstByte == null) firstByte = connected.elapsedNow()
                    text.append(String(buffer, 0, read))
                }
            }
        } catch (_: IOException) {
            return Heard(text.toString(), firstByte, connected.elapsedNow())
        }
        return Heard(text.toString(), firstByte, null)
    }

    // Netty sends the response headers with the first byte of the body and gives up on a response
    // that has written nothing for ten seconds, set here so that a change of Ktor's default cannot
    // move it. The default heartbeat's first comment comes first, five seconds in. Both are real
    // time, so a machine that stops this JVM for seconds on end, as one that swaps does, can let
    // Netty's single timer win over the comment's several hops. A stream that closed is tried once
    // more on the same server only when the JVM lost a second or more to stops during it: the fault
    // this looks for closes every stream, stopped or not, and a stop seldom comes twice in a row.
    // With four seconds lost in all the test cannot judge the margin, and says so rather than fail.
    @Test
    fun `an idle stream on Netty outlives the engine's write timeout`() {
        val server =
            serve(Netty, { responseWriteTimeoutSeconds = 10 }) {
                routing { get("/idle") { call.respondDatastar { awaitCancellation() } } }
            }
        try {
            val tries = mutableListOf<Heard>()
            val stall = StallWatch()
            stall.use {
                do {
                    val lostBefore = stall.lost
                    tries += listen(server.port, 12.seconds)
                    val lostDuring = stall.lost - lostBefore
                    println("Idle stream on Netty, try ${tries.size}: ${tries.last()}, $lostDuring lost to stops during it")
                } while (tries.size < 2 && tries.last().closed && lostDuring >= 1.seconds)
            }
            val heard = tries.last()
            val report = tries.withIndex().joinToString("\n") { (i, it) -> "try ${i + 1}: $it:\n${it.text}" }

            assumeFalse(heard.closed && stall.lost >= 4.seconds, "this JVM stood still for ${stall.lost} in all\n$report")
            val how = if (tries.size == 1) "with no stop to explain it" else "on both tries"
            assertEquals(false, heard.closed, "Netty closed the stream $how, ${stall.lost} lost to stops in all\n$report")
            assertTrue(": keep-alive" in heard.text, "no keep-alive comment, with ${stall.lost} lost to stops\n$report")
        } finally {
            server.stop(100, 500)
        }
    }

    /**
     * How long, in all, this JVM kept a thread that asked to wake every ten milliseconds waiting past
     * that, counting only stops of a tenth of a second or more, not the jitter of a busy machine.
     */
    private class StallWatch : AutoCloseable {
        @Volatile var lost: Duration = Duration.ZERO
            private set

        @Volatile private var running = true

        private val thread =
            thread(isDaemon = true, name = "stall-watch") {
                while (running) {
                    val asleep = TimeSource.Monotonic.markNow()
                    Thread.sleep(10)
                    val stop = asleep.elapsedNow() - 10.milliseconds
                    if (stop >= 100.milliseconds) lost += stop
                }
            }

        override fun close() {
            running = false
            thread.join()
        }
    }

    @Test
    fun `the plugin's heartbeat is the default`() {
        assertEquals(Streamlord.DEFAULT_HEARTBEAT, StreamlordPluginConfig().heartbeat)
    }
}
