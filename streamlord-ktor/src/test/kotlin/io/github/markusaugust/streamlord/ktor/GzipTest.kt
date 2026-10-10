package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.application.install
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
