package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.github.markusaugust.streamlord.test.LiveDatastarStream
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The live path end to end: a stream held open while a command arrives on another request. The
 * test host cannot serve this, because it hands a streamed response over only once it has ended.
 */
class LiveStreamTest {
    private val count = MutableStateFlow(0)
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0
    private val http = HttpClient.newHttpClient()
    private val left = CountDownLatch(1)

    @BeforeTest
    fun start() {
        server =
            embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                routing {
                    get("/count") {
                        try {
                            call.respondDatastar {
                                count.collect { n ->
                                    patchSignals("count" to n)
                                    patchElements("<b>$n</b>", selector = "#count", mode = ElementPatchMode.INNER)
                                }
                            }
                        } finally {
                            left.countDown()
                        }
                    }
                    post("/increment") {
                        count.value++
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }.start()
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stop() {
        server.stop(0, 0)
    }

    private fun post(path: String) {
        http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding(),
        )
    }

    @Test
    fun `a command reaches the stream that was already open`() =
        runTest {
            LiveDatastarStream.open(URI("http://127.0.0.1:$port/count")).use { page ->
                page.awaitSignal("count", 0)

                post("/increment")
                page.awaitSignal("count", 1)
                page.awaitPatchElements(selector = "#count", containing = "<b>1</b>")

                post("/increment")
                page.awaitSignal("count", 2)
            }
        }

    // Ktor finds the reader gone on its next write, and cancels the handler.
    @Test
    fun `a reader who closes the stream ends the handler`() =
        runTest {
            val page = LiveDatastarStream.open(URI("http://127.0.0.1:$port/count"))
            page.awaitSignal("count", 0)

            page.close()
            var ended = false
            repeat(100) {
                if (ended) return@repeat
                post("/increment")
                ended = left.await(50, TimeUnit.MILLISECONDS)
            }

            assertTrue(ended, "the handler went on collecting after the reader left")
        }
}
