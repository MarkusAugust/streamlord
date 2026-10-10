package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.DatastarResponse
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.ScriptResponse
import io.github.markusaugust.streamlord.core.domain.SignalsResponse
import io.github.markusaugust.streamlord.core.port.driven.SseSink
import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpVersion
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.Flow
import org.intellij.lang.annotations.Language
import kotlin.reflect.typeOf

/**
 * Respond with a Datastar SSE stream and run [block] against it.
 *
 * The response stays open for exactly as long as [block] runs. Return, and the stream closes
 * cleanly; collect a never-ending `Flow`, and it lives until the client disconnects, at which
 * point Ktor cancels the coroutine. Every event is flushed the moment it is sent.
 *
 * ```kotlin
 * get("/feed") {
 *     call.respondDatastar {
 *         patchElements("""<div id="feed">The gates are open</div>""")
 *         patchSignals("ready" to true)
 *     }
 * }
 * ```
 */
public suspend fun ApplicationCall.respondDatastar(
    status: HttpStatusCode = HttpStatusCode.OK,
    authorisation: StreamAuthorisation? = null,
    block: suspend DatastarStream.() -> Unit,
) {
    val streamlord = this.streamlord
    for ((name, value) in DatastarProtocol.SSE_RESPONSE_HEADERS) response.header(name, value)
    if (request.httpVersion == "HTTP/1.1") response.header("Connection", "keep-alive")
    val gzip = streamlord.compress && DatastarProtocol.acceptsGzip(request.headers[HttpHeaders.AcceptEncoding])
    respond(
        object : OutgoingContent.WriteChannelContent() {
            override val contentType: ContentType = ContentType.Text.EventStream
            override val status: HttpStatusCode = status

            // On the content rather than the response: this is where Ktor's Compression plugin
            // looks to see that a body is already encoded, and leaves it alone.
            override val headers: Headers =
                if (gzip) {
                    headersOf(
                        HttpHeaders.ContentEncoding to listOf("gzip"),
                        HttpHeaders.Vary to listOf(HttpHeaders.AcceptEncoding),
                    )
                } else {
                    Headers.Empty
                }

            override suspend fun writeTo(channel: ByteWriteChannel) {
                if (gzip) {
                    val sink = GzipChannelSseSink(channel)
                    streamlord.stream(sink, authorisation, block)
                    sink.finish()
                } else {
                    streamlord.stream(ChannelSseSink(channel), authorisation, block)
                }
            }
        },
    )
}

/** Respond with a Datastar SSE stream that drains [events] in order until the flow completes. */
public suspend fun ApplicationCall.respondDatastar(
    events: Flow<DatastarEvent>,
    status: HttpStatusCode = HttpStatusCode.OK,
    authorisation: StreamAuthorisation? = null,
) {
    respondDatastar(status, authorisation) { sendAll(events) }
}

/** Respond with a single non-SSE Datastar response: `text/html`, `application/json` or `text/javascript`. */
public suspend fun ApplicationCall.respondDatastar(
    response: DatastarResponse,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    streamlord.guard(response)
    for ((name, value) in response.headers) this.response.header(name, value)
    respondText(response.body, ContentType.parse(response.contentType), status)
}

/** Respond with HTML to be patched as elements, without opening a stream. */
public suspend fun ApplicationCall.respondElements(
    @Language("HTML") elements: String,
    selector: String? = null,
    mode: ElementPatchMode = ElementPatchMode.DEFAULT,
    namespace: ElementNamespace = ElementNamespace.DEFAULT,
    useViewTransition: Boolean = false,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondDatastar(ElementsResponse(elements, selector, mode, namespace, useViewTransition), status)
}

/** Respond with a JSON object to be patched as signals, without opening a stream. */
public suspend fun ApplicationCall.respondSignals(
    @Language("JSON") signals: String,
    onlyIfMissing: Boolean = false,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondDatastar(SignalsResponse(signals, onlyIfMissing), status)
}

/** Respond with any value the codec can encode, to be patched as signals. */
public suspend inline fun <reified T> ApplicationCall.respondSignals(
    value: T,
    onlyIfMissing: Boolean = false,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondSignals(streamlord.codec.encode(value, typeOf<T>()), onlyIfMissing, status)
}

/** Respond with JavaScript to execute, without opening a stream. */
public suspend fun ApplicationCall.respondScript(
    @Language("JavaScript") script: String,
    attributes: Map<String, String> = emptyMap(),
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondDatastar(ScriptResponse(script, attributes), status)
}

/** An [SseSink] over Ktor's response channel. */
/**
 * Gzip over the response channel. Each flush ends a deflate block with a sync flush and hands the
 * bytes to the channel, so an event is decodable the moment it arrives, while the compressor keeps
 * its window across events and a render that repeats the last costs few bytes.
 */
internal class GzipChannelSseSink(
    private val channel: ByteWriteChannel,
) : SseSink {
    private val buffer = ByteArrayOutputStream()
    private val gzip = GZIPOutputStream(buffer, true)

    override suspend fun write(text: String) {
        gzip.write(text.toByteArray(Charsets.UTF_8))
    }

    override suspend fun flush() {
        gzip.flush()
        drain()
    }

    /** End the gzip member, so the client's decoder sees a whole stream. */
    suspend fun finish() {
        gzip.finish()
        drain()
    }

    private suspend fun drain() {
        channel.writeFully(buffer.toByteArray())
        buffer.reset()
        channel.flush()
    }
}

internal class ChannelSseSink(
    private val channel: ByteWriteChannel,
) : SseSink {
    override suspend fun write(text: String) = channel.writeStringUtf8(text)

    override suspend fun flush() = channel.flush()
}
