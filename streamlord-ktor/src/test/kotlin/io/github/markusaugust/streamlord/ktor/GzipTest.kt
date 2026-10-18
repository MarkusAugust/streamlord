package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.net.Socket
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GzipTest {
    private val wire =
        "event: datastar-patch-signals\ndata: signals {\"n\":1}\n\n" +
            "event: datastar-patch-signals\ndata: signals {\"n\":2}\n\n"

    private fun gunzip(bytes: ByteArray): String = GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().decodeToString()

    @Test
    fun `a client that takes gzip gets the stream gzipped`() =
        testApplication {
            install(StreamlordPlugin) { compress = true }
            routing {
                get("/feed") {
                    call.respondDatastar {
                        patchSignals("n" to 1)
                        patchSignals("n" to 2)
                    }
                }
            }

            val response = client.get("/feed") { header(HttpHeaders.AcceptEncoding, "gzip, br") }

            assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
            assertEquals(HttpHeaders.AcceptEncoding, response.headers[HttpHeaders.Vary])
            assertEquals(wire, gunzip(response.bodyAsBytes()))
        }

    // Ktor's own Compression plugin sees the encoding already named, and leaves the body alone.
    @Test
    fun `Ktor's Compression plugin does not compress it twice`() =
        testApplication {
            install(StreamlordPlugin) { compress = true }
            install(Compression) {
                gzip { matchContentType(ContentType.Text.EventStream) }
            }
            routing {
                get("/feed") {
                    call.respondDatastar {
                        patchSignals("n" to 1)
                        patchSignals("n" to 2)
                    }
                }
            }

            val response = client.get("/feed") { header(HttpHeaders.AcceptEncoding, "gzip") }

            assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
            assertEquals(wire, gunzip(response.bodyAsBytes()))
        }

    @Test
    fun `a client that does not, or a plugin that is not asked to, gets it plain`() =
        testApplication {
            install(StreamlordPlugin) { compress = true }
            routing { get("/feed") { call.respondDatastar { patchSignals("n" to 1) } } }

            val response = client.get("/feed")

            assertNull(response.headers[HttpHeaders.ContentEncoding])
            assertEquals(HttpHeaders.AcceptEncoding, response.headers[HttpHeaders.Vary])
            assertEquals("event: datastar-patch-signals\ndata: signals {\"n\":1}\n\n", response.bodyAsText())
        }

    // The heartbeat's comments go through the same compressor, between the events, in one member.
    // The event waits until the client has read a comment out of the member, so on a real server:
    // the test host hands the client nothing before the stream ends. A comment may also follow
    // the event before the stream ends.
    @Test
    fun `heartbeats on a gzipped stream arrive inside the gzip member`() {
        val heard = CompletableDeferred<Unit>()
        val server =
            embeddedServer(CIO, port = 0) {
                install(StreamlordPlugin) {
                    compress = true
                    heartbeat = 50.milliseconds
                }
                routing {
                    get("/feed") {
                        call.respondDatastar {
                            withTimeout(30.seconds) { heard.await() }
                            patchSignals("n" to 1)
                        }
                    }
                }
            }.start(wait = false)
        try {
            val port =
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            val body =
                Socket("localhost", port).use { socket ->
                    socket.soTimeout = 30_000
                    socket.getOutputStream().write("GET /feed HTTP/1.1\r\nHost: x\r\nAccept-Encoding: gzip\r\n\r\n".toByteArray())
                    val read = StringBuilder()
                    val buffer = ByteArray(4096)
                    while (!read.endsWith("\r\n0\r\n\r\n")) {
                        val count = socket.getInputStream().read(buffer)
                        if (count < 0) break
                        read.append(String(buffer, 0, count, Charsets.ISO_8859_1))
                        if (": keep-alive\n\n" in inflated(dechunked(read))) heard.complete(Unit)
                    }
                    dechunked(read)
                }
            val text = gunzip(body)

            assertTrue(text.startsWith(": keep-alive\n\n"), text)
            assertEquals("event: datastar-patch-signals\ndata: signals {\"n\":1}\n\n", text.replace(": keep-alive\n\n", ""))
        } finally {
            server.stop(100, 500)
        }
    }

    /** The body of a chunked answer, as far as its chunks have arrived whole. */
    private fun dechunked(answer: CharSequence): ByteArray {
        val text = answer.toString()
        var at = text.indexOf("\r\n\r\n").takeIf { it >= 0 }?.plus(4) ?: return ByteArray(0)
        val body = StringBuilder()
        while (true) {
            val line = text.indexOf("\r\n", at).takeIf { it >= 0 } ?: break
            val size = text.substring(at, line).toInt(16)
            if (size == 0 || line + 2 + size > text.length) break
            body.append(text, line + 2, line + 2 + size)
            at = line + 2 + size + 2
        }
        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** What a gzip member so far inflates to, past its ten bytes of header. */
    private fun inflated(member: ByteArray): String {
        if (member.size <= 10) return ""
        val inflater = Inflater(true)
        inflater.setInput(member, 10, member.size - 10)
        val out = StringBuilder()
        val buffer = ByteArray(4096)
        while (true) {
            val count = inflater.inflate(buffer)
            if (count == 0) break
            out.append(String(buffer, 0, count, Charsets.UTF_8))
        }
        inflater.end()
        return out.toString()
    }

    @Test
    fun `a heartbeat for one stream reaches a gzipped stream`() =
        testApplication {
            install(StreamlordPlugin) { compress = true }
            routing {
                get("/feed") {
                    call.respondDatastar(heartbeat = 50.milliseconds) {
                        delay(180.milliseconds)
                        patchSignals("n" to 1)
                    }
                }
            }

            val text = gunzip(client.get("/feed") { header(HttpHeaders.AcceptEncoding, "gzip") }.bodyAsBytes())

            assertTrue(text.startsWith(": keep-alive"), text)
        }

    // A refusal ends the stream cleanly, so the gzip member is whole too.
    @Test
    fun `a refused stream still ends as a whole gzip member`() =
        testApplication {
            install(StreamlordPlugin) { compress = true }
            routing {
                get("/feed") {
                    call.respondDatastar(
                        authorisation = StreamAuthorisation(onRefused = { patchSignals("gone" to true) }) { false },
                    ) { patchSignals("n" to 1) }
                }
            }

            val body = client.get("/feed") { header(HttpHeaders.AcceptEncoding, "gzip") }.bodyAsBytes()

            assertEquals("event: datastar-patch-signals\ndata: signals {\"gone\":true}\n\n", gunzip(body))
        }

    // The sizes on the Operations page: a 200-row table, then the same table with one row changed.
    @Test
    fun `a render that repeats the last costs a fraction of the first`() =
        runTest {
            val rows =
                (0 until 200).joinToString("") {
                    "<tr id=\"row-$it\"><td class=\"name\">Item $it</td><td class=\"qty\">${it * 7 % 13}</td></tr>"
                }
            val first =
                "event: datastar-patch-elements\ndata: selector #view\ndata: mode inner\n" +
                    "data: elements <table id=\"t\">$rows</table>\n\n"
            val second = first.replace("Item 17<", "Item 17 (sold)<")
            val channel = ByteChannel()
            val sink = GzipChannelSseSink(channel)
            val buffer = ByteArray(65_536)

            suspend fun sent(text: String): Int {
                sink.write(text)
                sink.flush()
                return channel.readAvailable(buffer)
            }

            val sizes = listOf(sent(first), sent(second), sent(": keep-alive\n\n"))
            println("gzip sizes: raw ${first.length}, first ${sizes[0]}, second ${sizes[1]}, heartbeat ${sizes[2]}")

            assertEquals(listOf(1476, 251, 20), sizes)
            sink.release()
        }

    // Every flush leaves a whole event decodable: nothing waits for the end of the stream.
    @Test
    fun `each event can be read before the stream ends`() =
        runTest {
            val channel = ByteChannel()
            val sink = GzipChannelSseSink(channel)
            val inflater = Inflater(true)
            val bytes = ArrayList<Byte>()
            val decoded = StringBuilder()

            suspend fun readOut() {
                val buffer = ByteArray(4096)
                val count = channel.readAvailable(buffer)
                if (count > 0) bytes += buffer.copyOf(count).toList()
                // Past the ten bytes of gzip header, the rest is raw deflate.
                if (bytes.size > 10) {
                    inflater.setInput(bytes.drop(10).toByteArray())
                    bytes.subList(10, bytes.size).clear()
                    val out = ByteArray(4096)
                    while (true) {
                        val n = inflater.inflate(out)
                        if (n == 0) break
                        decoded.append(out.copyOf(n).decodeToString())
                    }
                }
            }

            sink.write("event: datastar-patch-signals\ndata: signals {\"n\":1}\n\n")
            sink.flush()
            readOut()
            assertEquals("event: datastar-patch-signals\ndata: signals {\"n\":1}\n\n", decoded.toString())

            sink.write("event: datastar-patch-signals\ndata: signals {\"n\":2}\n\n")
            sink.flush()
            readOut()
            assertEquals(wire, decoded.toString())
        }
}
