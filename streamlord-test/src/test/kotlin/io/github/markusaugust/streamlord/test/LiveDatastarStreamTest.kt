package io.github.markusaugust.streamlord.test

import com.sun.net.httpserver.HttpServer
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import kotlinx.coroutines.test.runTest
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Against a real server on a real socket, which is what the class is for. */
class LiveDatastarStreamTest {
    private lateinit var server: HttpServer

    /** What the open stream writes next; an empty string ends it. */
    private val outbox = LinkedBlockingQueue<String>()
    private val readerLeft = CountDownLatch(1)

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feed") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val body: OutputStream = exchange.responseBody
            try {
                while (true) {
                    val frame = outbox.poll(5, TimeUnit.SECONDS) ?: break
                    if (frame.isEmpty()) break
                    body.write(frame.toByteArray())
                    body.flush()
                }
            } catch (_: java.io.IOException) {
                readerLeft.countDown()
            } finally {
                exchange.close()
            }
        }
        server.createContext("/page") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        outbox.put("")
        server.stop(0)
    }

    private fun feed() = LiveDatastarStream.open(URI("http://127.0.0.1:${server.address.port}/feed"))

    private fun signals(json: String) = "event: datastar-patch-signals\ndata: signals $json\n\n"

    @Test
    fun `a signal changed after the stream opened is seen on the same stream`() =
        runTest {
            feed().use { stream ->
                outbox.put(signals("""{"count":0}"""))
                stream.awaitSignal("count", 0)

                outbox.put(signals("""{"count":1}"""))
                stream.awaitSignal("count", 1)
            }
        }

    @Test
    fun `an element patch is awaited past the ones that do not match`() =
        runTest {
            feed().use { stream ->
                outbox.put("event: datastar-patch-elements\ndata: elements <div id=\"a\">1</div>\n\n")
                outbox.put(": keep-alive\n\n")
                outbox.put("event: datastar-patch-elements\ndata: selector #feed\ndata: mode append\ndata: elements <li>2</li>\n\n")

                val patch = stream.awaitPatchElements(selector = "#feed", mode = ElementPatchMode.APPEND)

                assertEquals("<li>2</li>", patch.elements)
                assertEquals(listOf("keep-alive"), stream.received.messages.flatMap { it.comments })
            }
        }

    @Test
    fun `an await does not count an event an earlier await moved past`() =
        runTest {
            feed().use { stream ->
                outbox.put("event: datastar-patch-elements\ndata: elements <div id=\"a\">1</div>\n\n")
                stream.awaitPatchElements(containing = "1")

                val failure =
                    assertFailsWith<AssertionError> { stream.awaitPatchElements(containing = "1", timeout = 300.milliseconds) }

                assertTrue("Waited 300ms" in failure.message!!, failure.message)
            }
        }

    @Test
    fun `a wait that comes up empty prints what the stream carried`() =
        runTest {
            feed().use { stream ->
                outbox.put(signals("""{"count":0}"""))

                val failure = assertFailsWith<AssertionError> { stream.awaitSignal("count", 1, timeout = 300.milliseconds) }

                assertTrue("Signal 'count' is 0, expected 1" in failure.message!!, failure.message)
                assertTrue("data: signals {\"count\":0}" in failure.message!!, failure.message)
            }
        }

    @Test
    fun `a stream that ends first fails at once, and next says so with null`() =
        runTest {
            feed().use { stream ->
                outbox.put(signals("""{"count":0}"""))
                outbox.put("")

                assertEquals(PatchSignals("""{"count":0}"""), stream.next())
                assertNull(stream.next())
                val failure = assertFailsWith<AssertionError> { stream.awaitSignal("count", 1) }
                assertTrue("The stream ended first" in failure.message!!, failure.message)
            }
        }

    @Test
    fun `closing lets the server see the reader leave`() =
        runTest {
            val stream = feed()
            outbox.put(signals("""{"count":0}"""))
            stream.awaitSignal("count", 0)

            stream.close()
            // A write fails only once the socket has noticed, so keep writing until it does.
            var noticed = false
            repeat(100) {
                if (noticed) return@repeat
                outbox.put(": keep-alive\n\n")
                noticed = readerLeft.await(50, TimeUnit.MILLISECONDS)
            }

            assertTrue(noticed, "the server never saw the reader go")
        }

    @Test
    fun `a response that is not a stream fails the open`() {
        val failure =
            assertFailsWith<AssertionError> { LiveDatastarStream.open(URI("http://127.0.0.1:${server.address.port}/page")) }

        assertTrue("text/event-stream" in failure.message!!, failure.message)
    }
}
