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
import jakarta.servlet.ServletResponse
import jakarta.servlet.ServletResponseWrapper
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
import org.springframework.web.util.ContentCachingResponseWrapper
import org.springframework.web.util.WebUtils
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
 * Every header is set before this returns but `Content-Encoding`, which waits for the stream's
 * first write so that a failure before it can still be answered with a status. One such answer
 * that Tomcat closes the connection after, a `400`, `413`, `500` or `503` among others, carries
 * both the stream's `Connection: keep-alive` and Tomcat's `Connection: close`; Tomcat closes it.
 *
 * @param streamlord The configured instance, typically a Spring bean. Defaults to [Streamlord.Default].
 * @param request The request being answered, when you have it: `Connection: keep-alive` is an
 *   HTTP/1.1 header that HTTP/2 forbids, and it is set only for a request known to be HTTP/1.1.
 *   Without this one, the request Spring MVC is handling for this response stands in, which a
 *   `suspend` controller no longer finds once it has suspended. With
 *   [Streamlord.compress] on, the stream is gzipped when this request's `Accept-Encoding` takes
 *   it; without the request, it is not.
 * @param timeout How long Spring lets this stream run before it ends it, in place of the
 *   application's `spring.mvc.async.request-timeout`, whose own default is the container's 30
 *   seconds. Tomcat and Jetty then end the response cleanly, as if the stream had finished, so
 *   Datastar's client does not reconnect. [Duration.INFINITE] lifts the limit, which is safe only with a heartbeat, on by
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
    val answered = requestOf(request)
    if (timeout != null) setAsyncTimeout(timeout, answered, request != null)
    if (executor != null) setAsyncExecutor(executor, answered, request != null)
    // The stream writes to the output stream, which a response refuses once its writer is taken.
    // Asked of the container's own response: a wrapper, such as the one ShallowEtagHeaderFilter
    // adds, makes its stream once, and Spring has yet to tell it not to cache this one.
    try {
        generateSequence<ServletResponse>(this) { (it as? ServletResponseWrapper)?.response }.last().outputStream
    } catch (e: IllegalStateException) {
        throw IllegalStateException("The response's writer is already in use, so it cannot carry an event stream", e)
    }
    // What something wrote before the stream would sit in front of a gzip member and break it.
    val untouched = prepareHeaders(this)
    // Without the request nothing is gzipped, so there is nothing for Accept-Encoding to vary.
    val varies = streamlord.compress && untouched && request != null
    val gzip = varies && DatastarProtocol.acceptsGzip(request.getHeader("Accept-Encoding"))
    // Added, not set: a CORS filter may already vary the response on Origin.
    if (varies && getHeaders("Vary").none { it.contains("Accept-Encoding", ignoreCase = true) }) {
        addHeader("Vary", "Accept-Encoding")
    }
    // Here, on the controller's thread, not with the first write: the body runs on another thread,
    // and a test reading the headers of the response MockMvc hands back would race it.
    if (wantsKeepAlive(answered)) setHeader("Connection", "keep-alive")
    return StreamingResponseBody { output ->
        val sink = StreamSink(output, this@datastarStream, gzip, untouched)
        try {
            streamlord.streamBlocking(sink, authorisation, heartbeat, block)
            sink.finish()
        } finally {
            sink.release()
        }
    }
}

/**
 * The stream's sink, which starts the response with its first write: `Content-Encoding` and the
 * gzip member. A failure before it leaves the response as an error answer can use it, since a
 * header once set cannot be taken off, and an error answer's plain body under `gzip` would be
 * noise. So that one header is set on the stream's own thread, where a MockMvc test that reads
 * the headers of the first result races it; see the testing page.
 */
private class StreamSink(
    private val output: OutputStream,
    response: HttpServletResponse,
    private val gzip: Boolean,
    private val untouched: Boolean,
) : SseSink {
    private val response = flushable(response)
    private var writer: Writer? = null
    private var body: SyncGzipOutputStream? = null

    private fun start(): Writer {
        writer?.let { return it }
        if (gzip) response.setHeader("Content-Encoding", "gzip")
        // Bytes something wrote before the stream may end in the middle of a line, which would
        // swallow the first event's first field; a blank line closes whatever was open.
        if (!untouched) output.write("\n\n".toByteArray())
        // Sync flush: each event leaves the compressor whole, the moment the sink flushes.
        val target = if (gzip) SyncGzipOutputStream(output).also { body = it } else output
        return OutputStreamWriter(target, StandardCharsets.UTF_8).also { writer = it }
    }

    override suspend fun write(text: String) {
        start().write(text)
    }

    override suspend fun flush() {
        start().flush()
        response.flushBuffer()
    }

    /** Only after a stream that ended well; one cut short is left for the client to see fail. */
    fun finish() {
        writer?.flush()
        body?.finish()
    }

    fun release() {
        body?.release()
    }
}

/*
 * The response to flush. A ContentCachingResponseWrapper, as ShallowEtagHeaderFilter puts in front
 * of every response, keeps a flush from the container, so the stream goes past it.
 */
private fun flushable(response: HttpServletResponse): HttpServletResponse =
    (WebUtils.getNativeResponse(response, ContentCachingResponseWrapper::class.java)?.response as? HttpServletResponse) ?: response

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
    given: Boolean,
) {
    require(timeout.isPositive()) { "timeout must be positive, Duration.INFINITE for none, or null for Spring's own" }
    // -1 is no limit to Spring and the container alike, and 0 is too, so a positive timeout is at least 1 ms.
    val millis = if (timeout.isInfinite()) -1L else timeout.inWholeMilliseconds.coerceAtLeast(1)
    onStreamCycle(asyncManager(request, given, "A timeout"), "timeout") { asyncRequest, _ -> asyncRequest.setTimeout(millis) }
}

private fun setAsyncExecutor(
    executor: AsyncTaskExecutor,
    request: HttpServletRequest?,
    given: Boolean,
) {
    onStreamCycle(asyncManager(request, given, "An executor"), "executor") { _, manager -> manager.setTaskExecutor(executor) }
}

/*
 * The request this response answers: [given], or the one Spring MVC holds for the current thread
 * when that one's response is this. A suspend controller can resume on a thread that holds some
 * other request, so the holder's request counts only for its own response.
 */
internal fun HttpServletResponse.requestOf(given: HttpServletRequest?): HttpServletRequest? {
    if (given != null) return given
    val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return null
    val held = attributes.response ?: return null
    return attributes.request.takeIf { sameResponse(held, this) }
}

// Spring hands a controller the response wrapped for its async lifecycle, and filters wrap it
// too, so two responses are the same when one is found unwrapping the other.
private fun sameResponse(
    a: ServletResponse,
    b: ServletResponse,
): Boolean {
    fun chain(response: ServletResponse) = generateSequence(response) { (it as? ServletResponseWrapper)?.response }
    return chain(a).any { it === b } || chain(b).any { it === a }
}

/*
 * The request's manager, read from the attribute rather than through WebAsyncUtils.getAsyncManager,
 * which would make a new one outside MVC and leave the setting with nothing to act on.
 */
private fun asyncManager(
    request: HttpServletRequest?,
    given: Boolean,
    what: String,
): WebAsyncManager {
    val manager = request?.getAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE) as? WebAsyncManager
    checkNotNull(manager?.asyncWebRequest) {
        if (!given) {
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
 * Set the content type and the headers an SSE response needs; the buffer keeps the size it has.
 *
 * `Connection: keep-alive` belongs to HTTP/1.1 only; HTTP/2 forbids connection-specific
 * headers, and curl and Node refuse a response that carries one, which Tomcat passes through. It
 * is set only for a request known to be HTTP/1.1: [request], or else the one Spring MVC is
 * handling for this response. With neither, it is left out.
 */
public fun HttpServletResponse.prepareForSse(request: HttpServletRequest? = null) {
    prepareStream(request)
}

/** [prepareForSse], and whether nothing had been written to the response before it; see [prepareHeaders]. */
internal fun HttpServletResponse.prepareStream(request: HttpServletRequest?): Boolean {
    val untouched = prepareHeaders(this)
    if (wantsKeepAlive(requestOf(request))) setHeader("Connection", "keep-alive")
    return untouched
}

/**
 * Whether the stream may say `Connection: keep-alive`: an HTTP/1.1 request that did not ask to
 * close. HTTP/2 has no such header, and a client that sent `Connection: close` is not contradicted.
 * One that asked for keep-alive itself gets Tomcat's answer, which comes on top of a header set
 * here: `Connection: keep-alive, keep-alive`.
 *
 * Tomcat may still close the connection on its own, after the header is set and out of sight of
 * the servlet API: on the request that reaches `maxKeepAliveRequests`, the 100th on a connection
 * by default, it adds `Connection: close`, and the response carries both; Tomcat closes the
 * connection after it all the same. Jetty drops the header from every response.
 */
private fun wantsKeepAlive(request: HttpServletRequest?): Boolean {
    if (request == null || !request.protocol.equals("HTTP/1.1", ignoreCase = true)) return false
    val tokens =
        request
            .getHeaders("Connection")
            .toList()
            .flatMap { it.split(',') }
            .map { it.trim() }
    return tokens.none { it.equals("close", ignoreCase = true) || it.equals("keep-alive", ignoreCase = true) }
}

/**
 * The content type and the headers every stream carries, and whether nothing had been written to
 * the response before. The buffer is set to the size it has: Tomcat, Jetty and Undertow refuse
 * that once anything is written, committed or not, which is the answer, and it shrinks nothing.
 * A buffer of none would be no better, since every event is flushed, and Jetty reads it as one
 * byte, which sends every byte in a chunk of its own.
 */
private fun prepareHeaders(response: HttpServletResponse): Boolean {
    response.contentType = DatastarProtocol.CONTENT_TYPE_EVENT_STREAM
    response.characterEncoding = StandardCharsets.UTF_8.name()
    for ((name, value) in DatastarProtocol.SSE_RESPONSE_HEADERS) response.setHeader(name, value)
    val buffered = flushable(response)
    return try {
        buffered.bufferSize = buffered.bufferSize
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
        flushable(response).flushBuffer()
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
        val declared = request.contentLengthLong.takeIf { it >= 0 }
        rejectDeclaredLengthAbove(maxBytes, declared)
        val bytes = request.inputStream.readNBytes(if (maxBytes < Int.MAX_VALUE) maxBytes + 1 else maxBytes)
        if (bytes.size > maxBytes) throw SignalsTooLargeException(bytes.size.toLong(), maxBytes)
        // A body that was declared and is gone was read before: by a @RequestBody, a filter that
        // parsed a form or a multipart, or a second read. It is not a request without signals.
        check(bytes.isNotEmpty() || declared == null || declared == 0L) {
            "The request body was already read, by a @RequestBody, a filter or an earlier read, so the signals in it are gone"
        }
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
