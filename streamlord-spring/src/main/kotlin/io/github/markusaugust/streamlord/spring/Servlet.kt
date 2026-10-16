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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.context.request.async.AsyncWebRequest
import org.springframework.web.context.request.async.CallableProcessingInterceptor
import org.springframework.web.context.request.async.WebAsyncManager
import org.springframework.web.context.request.async.WebAsyncUtils
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.zip.GZIPOutputStream
import kotlin.reflect.typeOf
import kotlin.time.Duration

/*
 * Spring WebMVC (servlet) integration.
 *
 * A controller returns a StreamingResponseBody. Spring frees the container thread and runs the
 * body on a thread from its MVC async executor, or the stream's own executor when it passes one,
 * and the stream holds that thread until the block returns.
 * The block runs inside runBlocking on that thread; the heartbeat on Dispatchers.IO.
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
 * The JVM name stays plain through `@JvmName`: a `Duration` parameter would otherwise add a
 * hash of the signature to it, which changes with every parameter added.
 *
 * @param streamlord The configured instance, typically a Spring bean. Defaults to [Streamlord.Default].
 * @param request The request being answered, when you have it: `Connection: keep-alive` is an
 *   HTTP/1.1 header, and with the request at hand it is only set for HTTP/1.1. With
 *   [Streamlord.compress] on, the stream is gzipped when this request's `Accept-Encoding` takes
 *   it; without the request, it is not.
 * @param timeout How long Spring lets this stream run before it cuts it, in place of the
 *   application's `spring.mvc.async.request-timeout`, whose own default is the container's 30
 *   seconds. [Duration.INFINITE] lifts the limit, which is safe only with a heartbeat, on by
 *   default: without one, a client that vanished without closing its connection holds the stream's
 *   thread for good. `null`, the default, leaves Spring's setting alone. The container checks for
 *   timeouts about once a second, so one lands up to a second late. One that is not positive throws
 *   [IllegalArgumentException]. It needs a request that Spring MVC is handling, found through
 *   [request] or Spring's `RequestContextHolder`, and throws [IllegalStateException] without one
 *   rather than being ignored. Works from a `suspend` controller too, which has to pass [request]:
 *   the holder does not follow it across a suspension. When one request asks for several
 *   streams, the last timeout given applies.
 * @param executor The executor that runs this stream, in place of Spring MVC's. A stream holds
 *   its thread for as long as it is open, and Spring Boot's own executor has eight threads and an
 *   unbounded queue, so a ninth stream waits for one of them while its timeout counts. `null`,
 *   the default, leaves Spring's. Like [timeout], it needs a request that Spring MVC is handling,
 *   works from a `suspend` controller that passes [request], and the last one given for a request
 *   applies.
 * @param heartbeat A keep-alive comment after this long without a frame, for this stream alone,
 *   or `null` for none. Defaults to the bean's [Streamlord.heartbeat]. One that is not positive
 *   throws here, while the controller can still answer with a status.
 */
@JvmName("datastarStream")
public fun HttpServletResponse.datastarStream(
    streamlord: Streamlord = Streamlord.Default,
    request: HttpServletRequest? = null,
    authorisation: StreamAuthorisation? = null,
    timeout: Duration? = null,
    executor: AsyncTaskExecutor? = null,
    heartbeat: Duration? = streamlord.heartbeat,
    block: suspend DatastarStream.() -> Unit,
): StreamingResponseBody {
    require(heartbeat == null || heartbeat.isPositive()) { "heartbeat must be positive, or null for none" }
    // Its headers have gone out, and with them a content type the stream cannot change.
    check(!isCommitted) { "The response is already committed, so it cannot become an event stream" }
    if (timeout != null) setAsyncTimeout(timeout, request)
    if (executor != null) setAsyncExecutor(executor, request)
    // What something wrote before the stream would sit in front of a gzip member and break it.
    val untouched = prepareStream(request)
    // Without the request nothing is gzipped, so there is nothing for Accept-Encoding to vary.
    val varies = streamlord.compress && untouched && request != null
    val gzip = varies && DatastarProtocol.acceptsGzip(request.getHeader("Accept-Encoding"))
    // Added, not set: a CORS filter may already vary the response on Origin.
    if (varies && getHeaders("Vary").none { it.contains("Accept-Encoding", ignoreCase = true) }) {
        addHeader("Vary", "Accept-Encoding")
    }
    if (gzip) setHeader("Content-Encoding", "gzip")
    return StreamingResponseBody { output ->
        if (!gzip) {
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            streamlord.streamBlocking(ServletSseSink(writer, this@datastarStream), authorisation, heartbeat, block)
            writer.flush()
            return@StreamingResponseBody
        }
        // Sync flush: each event leaves the compressor whole, the moment the sink flushes.
        val body = SyncGzipOutputStream(output)
        try {
            val writer = OutputStreamWriter(body, StandardCharsets.UTF_8)
            streamlord.streamBlocking(ServletSseSink(writer, this@datastarStream), authorisation, heartbeat, block)
            writer.flush()
            // Only after a stream that ended well; one cut short is left for the client to see fail.
            body.finish()
        } finally {
            body.release()
        }
    }
}

/** Open a Datastar SSE stream that drains [events] until the flow completes. */
@JvmName("datastarStream")
public fun HttpServletResponse.datastarStream(
    events: Flow<DatastarEvent>,
    streamlord: Streamlord = Streamlord.Default,
    request: HttpServletRequest? = null,
    authorisation: StreamAuthorisation? = null,
    timeout: Duration? = null,
    executor: AsyncTaskExecutor? = null,
    heartbeat: Duration? = streamlord.heartbeat,
): StreamingResponseBody = datastarStream(streamlord, request, authorisation, timeout, executor, heartbeat) { sendAll(events) }

/** [datastarStream] as 0.12 had it, before `heartbeat`, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public fun HttpServletResponse.datastarStream(
    streamlord: Streamlord = Streamlord.Default,
    request: HttpServletRequest? = null,
    authorisation: StreamAuthorisation? = null,
    block: suspend DatastarStream.() -> Unit,
): StreamingResponseBody = datastarStream(streamlord, request, authorisation, null, null, streamlord.heartbeat, block)

/** The [Flow] variant of [datastarStream] as 0.12 had it, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public fun HttpServletResponse.datastarStream(
    events: Flow<DatastarEvent>,
    streamlord: Streamlord = Streamlord.Default,
    request: HttpServletRequest? = null,
    authorisation: StreamAuthorisation? = null,
): StreamingResponseBody = datastarStream(events, streamlord, request, authorisation, null, null, streamlord.heartbeat)

private fun setAsyncTimeout(
    timeout: Duration,
    request: HttpServletRequest?,
) {
    require(timeout.isPositive()) { "timeout must be positive, Duration.INFINITE for none, or null for Spring's own" }
    // -1 is no limit to Spring and the container alike, and 0 is too, so a positive timeout is at least 1 ms.
    val millis = if (timeout.isInfinite()) -1L else timeout.inWholeMilliseconds.coerceAtLeast(1)
    onStreamCycle(asyncManager(request, "A timeout"), "timeout") { asyncRequest, _ -> asyncRequest.setTimeout(millis) }
}

private fun setAsyncExecutor(
    executor: AsyncTaskExecutor,
    request: HttpServletRequest?,
) {
    onStreamCycle(asyncManager(request, "An executor"), "executor") { _, manager -> manager.setTaskExecutor(executor) }
}

/*
 * The request's manager, read from the attribute rather than through WebAsyncUtils.getAsyncManager,
 * which would make a new one outside MVC and leave the setting with nothing to act on.
 */
private fun asyncManager(
    request: HttpServletRequest?,
    what: String,
): WebAsyncManager {
    val servletRequest = request ?: (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
    val manager = servletRequest?.getAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE) as? WebAsyncManager
    checkNotNull(manager?.asyncWebRequest) {
        if (request == null) {
            "$what needs a request that Spring MVC is handling, and none was found: pass the request. " +
                "Spring's RequestContextHolder does not follow a suspend controller across a suspension."
        } else {
            "$what needs a request that Spring MVC is handling; this one is not, so it would have no effect"
        }
    }
    return manager
}

/*
 * Run [apply] on the async cycle that runs the stream, just before Spring starts it and hands it to
 * its executor. Not on the current cycle: a suspend controller runs in a cycle of its own, and the
 * stream in a second one that Spring sets up with its defaults again. The interceptor stays on the
 * request's manager across both, and the fixed key means the last setting given for a request is
 * the one that applies.
 */
private fun onStreamCycle(
    manager: WebAsyncManager,
    setting: String,
    apply: (AsyncWebRequest, WebAsyncManager) -> Unit,
) {
    manager.registerCallableInterceptor(
        "io.github.markusaugust.streamlord.spring.$setting",
        object : CallableProcessingInterceptor {
            override fun <T : Any> beforeConcurrentHandling(
                request: NativeWebRequest,
                task: Callable<T>,
            ) {
                apply(request as AsyncWebRequest, manager)
            }
        },
    )
}

/**
 * Set the headers an SSE response needs and disable buffering. Called by [datastarStream].
 *
 * `Connection: keep-alive` belongs to HTTP/1.1 only; HTTP/2 forbids connection-specific
 * headers. With [request] given it is set only when the request came over HTTP/1.1; without
 * it, the header is set, as almost every servlet deployment still speaks HTTP/1.1 and the
 * HTTP/2 containers drop it themselves.
 */
public fun HttpServletResponse.prepareForSse(request: HttpServletRequest? = null) {
    prepareStream(request)
}

/**
 * [prepareForSse], and whether nothing had been written to the response before it. A buffer of
 * none is a request, not a need, since every event is flushed anyway. Tomcat, Jetty and Undertow
 * refuse it once anything is written, committed or not, and the refusal is that answer.
 */
internal fun HttpServletResponse.prepareStream(request: HttpServletRequest?): Boolean {
    contentType = DatastarProtocol.CONTENT_TYPE_EVENT_STREAM
    characterEncoding = StandardCharsets.UTF_8.name()
    for ((name, value) in DatastarProtocol.SSE_RESPONSE_HEADERS) setHeader(name, value)
    if (request == null || request.protocol.equals("HTTP/1.1", ignoreCase = true)) setHeader("Connection", "keep-alive")
    return try {
        bufferSize = 0
        true
    } catch (_: IllegalStateException) {
        false
    }
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

/*
 * The block runs on this thread, where Spring's request context is, and the heartbeat on
 * Dispatchers.IO, so a block that blocks the thread does not hold the comment back. Not a small
 * pool of its own: a write blocks its thread, and one slow reader would stall every heartbeat.
 * runBlocking waits for the heartbeat before it returns, also when Spring's timeout interrupts it.
 */
private fun Streamlord.streamBlocking(
    sink: SseSink,
    authorisation: StreamAuthorisation?,
    heartbeat: Duration?,
    block: suspend DatastarStream.() -> Unit,
) {
    runBlocking { stream(sink, authorisation, heartbeat, Dispatchers.IO, block) }
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
