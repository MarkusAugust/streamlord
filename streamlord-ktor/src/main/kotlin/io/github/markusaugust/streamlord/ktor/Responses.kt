package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.application.Streamlord
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpVersion
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.intellij.lang.annotations.Language
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Respond with a Datastar SSE stream and run [block] against it.
 *
 * The response stays open for exactly as long as [block] runs. Return, and the stream closes
 * cleanly. Every event is flushed the moment it is sent.
 *
 * Ktor does not cancel the handler when the client disconnects. A block that keeps writing ends
 * when a write fails with an `IOException`, usually a few writes after the client left; a block
 * that waits without writing waits on. Install
 * Ktor's `HttpRequestLifecycle` (Ktor 3.4 and newer) with `cancelCallOnClose = true` on the
 * route, and the handler is cancelled as the connection closes, on the CIO and Netty engines.
 *
 * ```kotlin
 * get("/feed") {
 *     call.respondDatastar {
 *         patchElements("""<div id="feed">The gates are open</div>""")
 *         patchSignals("ready" to true)
 *     }
 * }
 * ```
 *
 * The JVM name stays plain through `@JvmName`: a `Duration` parameter would otherwise add a
 * hash of the signature to it, which changes with every parameter added.
 *
 * @param heartbeat A keep-alive comment after this long without a frame, for this stream alone,
 *   or `null` for none. Defaults to the configured [Streamlord.heartbeat]. One that is not
 *   positive throws here, before the response starts. On Netty, `null` is for a stream that
 *   writes at once: Netty sends no headers before the first byte of the body, and closes a
 *   response that has written nothing for ten seconds, while the handler waits on.
 *
 * A block that throws ends the stream after a moment for the frames it sent to go out, which is
 * as much as Ktor allows a writer, and the connection is then cut, which Datastar's client
 * retries.
 */
@JvmName("respondDatastar")
public suspend fun ApplicationCall.respondDatastar(
    authorisation: StreamAuthorisation? = null,
    heartbeat: Duration? = streamlord.heartbeat,
    block: suspend DatastarStream.() -> Unit,
) {
    respondStream(HttpStatusCode.OK, authorisation, heartbeat, block)
}

private suspend fun ApplicationCall.respondStream(
    status: HttpStatusCode,
    authorisation: StreamAuthorisation?,
    heartbeat: Duration?,
    block: suspend DatastarStream.() -> Unit,
) {
    require(heartbeat == null || heartbeat.isPositive()) { "heartbeat must be positive, or null for none" }
    check(!response.isCommitted && !response.isSent) { "The response has already been sent, so it cannot become an event stream" }
    val streamlord = this.streamlord
    for ((name, value) in DatastarProtocol.SSE_RESPONSE_HEADERS) response.header(name, value)
    // HTTP/2 forbids it, and a request that asked to close is not to be told otherwise.
    val closing =
        request.headers
            .getAll(HttpHeaders.Connection)
            .orEmpty()
            .flatMap { it.split(',') }
            .any { it.trim().equals("close", true) }
    if (request.httpVersion == "HTTP/1.1" && !closing) response.header("Connection", "keep-alive")
    val gzip = streamlord.compress && DatastarProtocol.acceptsGzip(request.headers[HttpHeaders.AcceptEncoding])
    respond(
        object : OutgoingContent.WriteChannelContent() {
            override val contentType: ContentType = ContentType.Text.EventStream
            override val status: HttpStatusCode = status

            // On the content rather than the response: this is where Ktor's Compression plugin
            // looks to see that a body is already encoded, and leaves it alone.
            override val headers: Headers =
                when {
                    gzip -> {
                        headersOf(
                            HttpHeaders.ContentEncoding to listOf("gzip"),
                            HttpHeaders.Vary to listOf(HttpHeaders.AcceptEncoding),
                        )
                    }

                    // The answer depends on Accept-Encoding whether or not this one was gzipped.
                    streamlord.compress -> {
                        headersOf(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
                    }

                    else -> {
                        Headers.Empty
                    }
                }

            override suspend fun writeTo(channel: ByteWriteChannel) {
                try {
                    if (gzip) {
                        streamlord.streamGzipped(channel, authorisation, heartbeat, block)
                    } else {
                        streamlord.stream(ChannelSseSink(channel), authorisation, heartbeat, block = block)
                    }
                } catch (e: Throwable) {
                    // Ktor closes the channel with the failure, and the engine then drops what it
                    // has not yet taken from it. ktor-io gives a writer no way to wait for that,
                    // so the engine is given a moment, bounded for a reader who stopped reading.
                    withContext(NonCancellable) {
                        withTimeoutOrNull(LAST_FRAMES_LIMIT) {
                            runCatching { channel.flush() }
                            yield()
                            delay(LAST_FRAMES_GRACE)
                        }
                    }
                    throw e
                }
            }
        },
        portableTypeInfo<OutgoingContent>(),
    )
}

/** Respond with a Datastar SSE stream that drains [events] in order until the flow completes. */
@JvmName("respondDatastar")
public suspend fun ApplicationCall.respondDatastar(
    events: Flow<DatastarEvent>,
    authorisation: StreamAuthorisation? = null,
    heartbeat: Duration? = streamlord.heartbeat,
) {
    respondStream(HttpStatusCode.OK, authorisation, heartbeat) { sendAll(events) }
}

/** [respondDatastar] as 0.12 had it, with a status and before `heartbeat`, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public suspend fun ApplicationCall.respondDatastar(
    status: HttpStatusCode = HttpStatusCode.OK,
    authorisation: StreamAuthorisation? = null,
    block: suspend DatastarStream.() -> Unit,
) {
    respondStream(status, authorisation, streamlord.heartbeat, block)
}

/** The [Flow] variant of [respondDatastar] as 0.12 had it, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public suspend fun ApplicationCall.respondDatastar(
    events: Flow<DatastarEvent>,
    status: HttpStatusCode = HttpStatusCode.OK,
    authorisation: StreamAuthorisation? = null,
) {
    respondStream(status, authorisation, streamlord.heartbeat) { sendAll(events) }
}

/**
 * Respond with a single non-SSE Datastar response: `text/html`, `application/json` or
 * `text/javascript`. Always `200 OK`: Datastar's client reads the body of no other status.
 */
public suspend fun ApplicationCall.respondDatastar(response: DatastarResponse) {
    respondResponse(response, HttpStatusCode.OK)
}

private suspend fun ApplicationCall.respondResponse(
    response: DatastarResponse,
    status: HttpStatusCode,
) {
    streamlord.guard(response)
    for ((name, value) in response.headers) this.response.header(name, value)
    respondText(response.body, ContentType.parse(response.contentType), status)
}

/** Respond with HTML to be patched as elements, without opening a stream. Always `200 OK`, as [respondDatastar]. */
public suspend fun ApplicationCall.respondElements(
    @Language("HTML") elements: String,
    selector: String? = null,
    mode: ElementPatchMode = ElementPatchMode.DEFAULT,
    namespace: ElementNamespace = ElementNamespace.DEFAULT,
    useViewTransition: Boolean = false,
) {
    respondDatastar(ElementsResponse(elements, selector, mode, namespace, useViewTransition))
}

/** Respond with a JSON object to be patched as signals, without opening a stream. Always `200 OK`. */
public suspend fun ApplicationCall.respondSignals(
    @Language("JSON") signals: String,
    onlyIfMissing: Boolean = false,
) {
    respondDatastar(SignalsResponse(signals, onlyIfMissing))
}

/** Respond with any value the codec can encode, to be patched as signals. Always `200 OK`. */
public suspend inline fun <reified T> ApplicationCall.respondSignals(
    value: T,
    onlyIfMissing: Boolean = false,
) {
    respondSignals(streamlord.codec.encode(value, typeOf<T>()), onlyIfMissing)
}

/** Respond with JavaScript to execute, without opening a stream. Always `200 OK`. */
public suspend fun ApplicationCall.respondScript(
    @Language("JavaScript") script: String,
    attributes: Map<String, String> = emptyMap(),
) {
    respondDatastar(ScriptResponse(script, attributes))
}

/*
 * The 0.12 signatures with a status, which Datastar's client ignored the body of for anything but
 * 200. Kept for code compiled against them; the inline respondSignals, inlined into such code,
 * calls the String one.
 */

/** [respondDatastar] for a [DatastarResponse] as 0.12 had it, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public suspend fun ApplicationCall.respondDatastar(
    response: DatastarResponse,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondResponse(response, status)
}

/** [respondElements] as 0.12 had it, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public suspend fun ApplicationCall.respondElements(
    elements: String,
    selector: String? = null,
    mode: ElementPatchMode = ElementPatchMode.DEFAULT,
    namespace: ElementNamespace = ElementNamespace.DEFAULT,
    useViewTransition: Boolean = false,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondResponse(ElementsResponse(elements, selector, mode, namespace, useViewTransition), status)
}

/** [respondSignals] as 0.12 had it, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public suspend fun ApplicationCall.respondSignals(
    signals: String,
    onlyIfMissing: Boolean = false,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondResponse(SignalsResponse(signals, onlyIfMissing), status)
}

/** [respondScript] as 0.12 had it, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public suspend fun ApplicationCall.respondScript(
    script: String,
    attributes: Map<String, String> = emptyMap(),
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondResponse(ScriptResponse(script, attributes), status)
}

/** How long a failed stream waits for the engine to take its last frames, and at most for a stalled reader. */
private val LAST_FRAMES_GRACE = 50.milliseconds
private val LAST_FRAMES_LIMIT = 1.seconds

/** The gzipped stream: [Streamlord.stream] over a [GzipChannelSseSink], ended as a whole member. */
internal suspend fun Streamlord.streamGzipped(
    channel: ByteWriteChannel,
    authorisation: StreamAuthorisation?,
    heartbeat: Duration?,
    block: suspend DatastarStream.() -> Unit,
) {
    val sink = GzipChannelSseSink(channel)
    try {
        if (stream(sink, authorisation, heartbeat, block = block)) {
            sink.finish()
        } else {
            // Refused: a reader that stalled the last words would stall the trailer too, so it
            // gets the same limit, and no trailer past it.
            withTimeoutOrNull(StreamAuthorisation.LAST_WORDS_LIMIT) { sink.finish() }
        }
    } finally {
        sink.release()
    }
}

/** Gzip with a sync flush per flush, and a way to free its native memory without closing anything. */
internal class SyncGzipOutputStream(
    out: java.io.OutputStream,
) : GZIPOutputStream(out, true) {
    fun release() = def.end()
}

/**
 * Gzip over the response channel. Each flush ends a deflate block with a sync flush and hands the
 * bytes to the channel, so an event is decodable the moment it arrives, while the compressor keeps
 * its window across events and a render that repeats the last costs few bytes.
 */
internal class GzipChannelSseSink(
    private val channel: ByteWriteChannel,
) : SseSink {
    private val buffer = ByteArrayOutputStream()
    private val gzip = SyncGzipOutputStream(buffer)

    override suspend fun write(text: String) {
        gzip.write(text.toByteArray(Charsets.UTF_8))
    }

    override suspend fun flush() {
        gzip.flush()
        drain()
    }

    /**
     * End the gzip member, so the client's decoder sees a whole stream. Only after a stream that
     * ended well: one cut short by an exception is left unterminated, so the client sees it fail.
     */
    suspend fun finish() {
        gzip.finish()
        drain()
    }

    /** Free the compressor's native memory, however the stream ended. */
    fun release() = gzip.release()

    private suspend fun drain() {
        // Taken before the write, so bytes a cancelled write left behind are never sent twice.
        val bytes = buffer.toByteArray()
        buffer.reset()
        channel.writeFully(bytes)
        channel.flush()
    }
}

/** An [SseSink] over Ktor's response channel. */
internal class ChannelSseSink(
    private val channel: ByteWriteChannel,
) : SseSink {
    override suspend fun write(text: String) = channel.writeStringUtf8(text)

    override suspend fun flush() = channel.flush()
}
