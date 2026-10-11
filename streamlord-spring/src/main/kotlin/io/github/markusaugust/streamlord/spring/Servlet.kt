package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.application.Signals
import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.port.driven.IncomingRequest
import io.github.markusaugust.streamlord.core.port.driven.SseSink
import io.github.markusaugust.streamlord.core.port.driven.isDatastarRequest
import io.github.markusaugust.streamlord.core.port.driven.rejectDeclaredLengthAbove
import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream
import kotlin.reflect.typeOf

/*
 * Spring WebMVC (servlet) integration.
 *
 * A controller returns a StreamingResponseBody; Spring hands the servlet output stream to
 * Streamlord on a container thread, and the stream lives until the block returns. Coroutines run
 * inside runBlocking on that thread, which is exactly what a streaming servlet response is for.
 */

/**
 * Open a Datastar SSE stream over this response and run [block] against it.
 *
 * ```kotlin
 * @GetMapping("/feed")
 * fun feed(response: HttpServletResponse): StreamingResponseBody = response.datastarStream {
 *     patchElements("""<div id="feed">The gates are open</div>""")
 * }
 * ```
 *
 * @param streamlord The configured instance, typically a Spring bean. Defaults to [Streamlord.Default].
 * @param request The request being answered, when you have it: `Connection: keep-alive` is an
 *   HTTP/1.1 header, and with the request at hand it is only set for HTTP/1.1. With
 *   [Streamlord.compress] on, the stream is gzipped when this request's `Accept-Encoding` takes
 *   it; without the request, it is not.
 */
public fun HttpServletResponse.datastarStream(
    streamlord: Streamlord = Streamlord.Default,
    request: HttpServletRequest? = null,
    authorisation: StreamAuthorisation? = null,
    block: suspend DatastarStream.() -> Unit,
): StreamingResponseBody {
    prepareForSse(request)
    // A committed response has sent its headers, and a body gzipped without them is noise.
    val gzip =
        streamlord.compress && !isCommitted && request != null &&
            DatastarProtocol.acceptsGzip(request.getHeader("Accept-Encoding"))
    // Added, not set: a CORS filter may already vary the response on Origin.
    if (streamlord.compress && !isCommitted && getHeaders("Vary").none { it.contains("Accept-Encoding", ignoreCase = true) }) {
        addHeader("Vary", "Accept-Encoding")
    }
    if (gzip) setHeader("Content-Encoding", "gzip")
    return StreamingResponseBody { output ->
        if (!gzip) {
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            runBlocking { streamlord.stream(ServletSseSink(writer, this@datastarStream), authorisation, block) }
            writer.flush()
            return@StreamingResponseBody
        }
        // Sync flush: each event leaves the compressor whole, the moment the sink flushes.
        val body = SyncGzipOutputStream(output)
        try {
            val writer = OutputStreamWriter(body, StandardCharsets.UTF_8)
            runBlocking { streamlord.stream(ServletSseSink(writer, this@datastarStream), authorisation, block) }
            writer.flush()
            // Only after a stream that ended well; one cut short is left for the client to see fail.
            body.finish()
        } finally {
            body.release()
        }
    }
}

/** Open a Datastar SSE stream that drains [events] until the flow completes. */
public fun HttpServletResponse.datastarStream(
    events: Flow<DatastarEvent>,
    streamlord: Streamlord = Streamlord.Default,
    request: HttpServletRequest? = null,
    authorisation: StreamAuthorisation? = null,
): StreamingResponseBody = datastarStream(streamlord, request, authorisation) { sendAll(events) }

/**
 * Set the headers an SSE response needs and disable buffering. Called by [datastarStream].
 *
 * `Connection: keep-alive` belongs to HTTP/1.1 only; HTTP/2 forbids connection-specific
 * headers. With [request] given it is set only when the request came over HTTP/1.1; without
 * it, the header is set, as almost every servlet deployment still speaks HTTP/1.1 and the
 * HTTP/2 containers drop it themselves.
 */
public fun HttpServletResponse.prepareForSse(request: HttpServletRequest? = null) {
    contentType = DatastarProtocol.CONTENT_TYPE_EVENT_STREAM
    characterEncoding = StandardCharsets.UTF_8.name()
    for ((name, value) in DatastarProtocol.SSE_RESPONSE_HEADERS) setHeader(name, value)
    if (request == null || request.protocol.equals("HTTP/1.1", ignoreCase = true)) setHeader("Connection", "keep-alive")
    bufferSize = 0
}

/**
 * An [SseSink] over a servlet response. Flushing the writer alone is not enough: the container
 * keeps its own buffer (Tomcat's is 8 KiB by default) and small events would never leave the
 * server without `flushBuffer()`.
 */
public class ServletSseSink(
    private val writer: Writer,
    private val response: HttpServletResponse,
) : SseSink {
    public constructor(output: OutputStream, response: HttpServletResponse) :
        this(OutputStreamWriter(output, StandardCharsets.UTF_8), response)

    override suspend fun write(text: String) {
        writer.write(text)
    }

    override suspend fun flush() {
        writer.flush()
        response.flushBuffer()
    }
}

/** Gzip with a sync flush per flush, and a way to free its native memory without closing the response. */
internal class SyncGzipOutputStream(
    out: OutputStream,
) : GZIPOutputStream(out, true) {
    fun release() = def.end()
}

/**
 * The [IncomingRequest] port implemented over a servlet request.
 *
 * The body is read from the servlet input stream with a ceiling: a declared `Content-Length`
 * above the limit is rejected before a byte is read, and a chunked body is cut off one byte past
 * the limit. Nothing larger than the limit ever sits in memory.
 */
public class ServletIncomingRequest(
    private val request: HttpServletRequest,
) : IncomingRequest {
    override val method: String get() = request.method

    override fun queryParameter(name: String): String? = request.getParameter(name)

    override fun header(name: String): String? = request.getHeader(name)

    override suspend fun bodyText(maxBytes: Int): String {
        rejectDeclaredLengthAbove(maxBytes, request.contentLengthLong.takeIf { it >= 0 })
        val bytes = request.inputStream.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) throw SignalsTooLargeException(bytes.size.toLong(), maxBytes)
        val charset = request.characterEncoding?.let { runCatching { charset(it) }.getOrNull() } ?: StandardCharsets.UTF_8
        return String(bytes, charset)
    }
}

/** This request as the core's [IncomingRequest] port. */
public fun HttpServletRequest.asIncomingRequest(): IncomingRequest = ServletIncomingRequest(this)

/** `true` when the request came from a Datastar fetch action. */
public val HttpServletRequest.isDatastarRequest: Boolean get() = asIncomingRequest().isDatastarRequest

/** The raw JSON text of the signals, or `null` when the request carried none. */
public fun HttpServletRequest.readSignalsJson(streamlord: Streamlord = Streamlord.Default): String? =
    runBlocking { streamlord.readSignalsJson(asIncomingRequest()) }

/** The signals as a dependency-free [Signals] object; empty when the request carried none. */
public fun HttpServletRequest.readSignals(streamlord: Streamlord = Streamlord.Default): Signals =
    runBlocking { streamlord.readSignals(asIncomingRequest()) }

/** The signals decoded into [T] by the codec, or `null` when the request carried none. */
@JvmName("readSignalsTyped")
public inline fun <reified T : Any> HttpServletRequest.readSignals(streamlord: Streamlord = Streamlord.Default): T? =
    runBlocking { streamlord.readSignals<T>(asIncomingRequest(), typeOf<T>()) }

/** The signals decoded into [T], or [default] when the request carried none. */
public inline fun <reified T : Any> HttpServletRequest.readSignalsOr(
    default: T,
    streamlord: Streamlord = Streamlord.Default,
): T = readSignals<T>(streamlord) ?: default
