package io.github.markusaugust.streamlord.core.application

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.SignalsMediaTypeException
import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.StreamRefusedException
import io.github.markusaugust.streamlord.core.StreamlordException
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.DatastarResponse
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementsGuard
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.port.driven.BuiltInSignalsCodec
import io.github.markusaugust.streamlord.core.port.driven.IncomingRequest
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import io.github.markusaugust.streamlord.core.port.driven.SseSink
import io.github.markusaugust.streamlord.core.port.driven.carriesSignalsInQuery
import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The signals the browser sent, when no typed codec is in play: a [JsonObject].
 */
public typealias Signals = JsonObject

/**
 * The seat of power. One immutable, thread-safe instance configures how Streamlord behaves;
 * the framework adapters ask it for streams and for signals.
 *
 * Construct one with your codec of choice and hand it to the adapter (a Ktor plugin, a Spring
 * bean), or lean on [Default] when the built-in codec suffices.
 *
 * @property codec Turns signals into JSON and back. Defaults to the dependency-free built-in.
 * @property maxSignalsSize Upper bound for incoming signal payloads: bytes for a request body,
 *   characters for the `datastar` query parameter. Exceeding it raises [SignalsTooLargeException]
 *   before any parsing happens, and adapters stop reading the body at this size.
 * @property guardElements Run [ElementsGuard] over the HTML of every element patch and elements
 *   response that leaves through this instance, so a `data-*` attribute whose expression a
 *   Kotlin string template ate (`data-text="$count"` shipping as `data-text=""`) throws
 *   [io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException], and a
 *   `data-*` name one letter from a Datastar one (`data-onn:click`) throws
 *   [io.github.markusaugust.streamlord.core.domain.MistypedAttributeException], instead of
 *   reaching the browser. Both are [StreamlordException]s. Off by default; the kotlinx.html DSL
 *   guards its own expressions regardless. Meant for HTML written as strings or rendered by a
 *   template engine.
 * @property attributePrefixes The `data-*` prefixes the guard recognises: `data-` and the
 *   aliased `data-star-` by default. A bundle built with another alias lists it here.
 * @property heartbeat An SSE comment whenever a stream has been silent this long, so a proxy or
 *   load balancer that closes idle connections sees traffic, and a reader who has gone is found
 *   by the write that fails. Any frame resets the wait, so a busy stream carries none. A stream
 *   silent from the start gets its first comment after five seconds, or this interval if it is
 *   shorter, so a server that sends no headers before the first byte, as Netty does, has one
 *   before it gives up on the response. [DEFAULT_HEARTBEAT] unless given; `null` turns it off.
 *   Applies to the streams Streamlord opens itself: `respondDatastar` on Ktor and
 *   `datastarStream` on Spring WebMVC. A `Flow` handed to WebFlux through `asServerSentEvents`
 *   or `asDatastarResponse` never passes through one, so it gets no heartbeat. One stream can
 *   take its own, or none, through the `heartbeat` parameter of `respondDatastar` and
 *   `datastarStream`.
 * @property compress Gzip the streams Streamlord opens itself, when the request's
 *   `Accept-Encoding` takes gzip: `respondDatastar` on Ktor and `datastarStream` on Spring WebMVC
 *   with the request passed. Each event is flushed through the compressor whole, so nothing waits
 *   for the next, and the window carries across events, so a render that repeats most of the
 *   last costs little. Off by default. Not WebFlux: its flows are only mapped.
 * @property requireJsonContentType Refuse signals in a request body unless its `Content-Type` is
 *   `application/json`, with [SignalsMediaTypeException], a `415`. Datastar's client declares
 *   every body it sends signals in that way. A `<form>` on another site can post `text/plain`
 *   that reads as JSON, and the browser sends it without asking this server first; it cannot do
 *   the same with `application/json`. On by default; turn it off only for a client of your own
 *   that cannot set the header. Signals in the query string are not affected, nor is a request
 *   with neither a body nor a `Content-Type`.
 */
public class Streamlord(
    public val codec: SignalsCodec = BuiltInSignalsCodec,
    public val maxSignalsSize: Int = DEFAULT_MAX_SIGNALS_SIZE,
    public val guardElements: Boolean = false,
    public val attributePrefixes: List<String> = ElementsGuard.defaultPrefixes,
    public val heartbeat: Duration? = DEFAULT_HEARTBEAT,
    public val compress: Boolean = false,
    public val requireJsonContentType: Boolean = true,
) {
    init {
        require(maxSignalsSize > 0) { "maxSignalsSize must be positive" }
        requireHeartbeat(heartbeat)
    }

    /**
     * The constructor as 0.11 had it, before [heartbeat] and [compress], for code compiled against
     * it. It takes [DEFAULT_HEARTBEAT] like the other, so the default holds for every caller.
     */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        codec: SignalsCodec = BuiltInSignalsCodec,
        maxSignalsSize: Int = DEFAULT_MAX_SIGNALS_SIZE,
        guardElements: Boolean = false,
        attributePrefixes: List<String> = ElementsGuard.defaultPrefixes,
    ) : this(codec, maxSignalsSize, guardElements, attributePrefixes, DEFAULT_HEARTBEAT, false, true)

    /** The constructor as 0.12 had it, before [requireJsonContentType], for code compiled against it. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public constructor(
        codec: SignalsCodec = BuiltInSignalsCodec,
        maxSignalsSize: Int = DEFAULT_MAX_SIGNALS_SIZE,
        guardElements: Boolean = false,
        attributePrefixes: List<String> = ElementsGuard.defaultPrefixes,
        heartbeat: Duration? = DEFAULT_HEARTBEAT,
        compress: Boolean = false,
    ) : this(codec, maxSignalsSize, guardElements, attributePrefixes, heartbeat, compress, true)

    /**
     * Open a [DatastarStream] over a sink. Adapters call this; you rarely need to.
     *
     * The stream has no lifetime of its own here, so [heartbeat] does not apply to it.
     */
    public fun stream(sink: SseSink): DatastarStream = SseDatastarStream(sink, codec, ::guard)

    /**
     * Open a stream, run [block] against it, and close when it returns.
     *
     * With an [authorisation], [StreamAuthorisation.allows] is asked once before [block] starts,
     * which is the cheapest place to refuse a connection, and again before each write once
     * [StreamAuthorisation.every] has passed. A refusal runs
     * [StreamAuthorisation.onRefused] on the still-open stream and then ends it: [block] stops
     * where it was, and the response finishes rather than falling silent.
     *
     * A [block] that catches the refusal and carries on sends nothing more: every later write is
     * refused again.
     *
     * With a [heartbeat], a keep-alive comment runs alongside [block] and stops when it returns.
     * The comment is a write like any other, which changes two things for a [block] that sits
     * idle: a reader who has gone is found on the heartbeat's schedule, and the failed write ends
     * [block] where it waits; and a refusal reaches it there too, so a [block] that caught an
     * earlier refusal and carried on without writing is ended at the next heartbeat. A [block]
     * that catches every exception swallows that ending as well, and runs on until it returns.
     *
     * A check the heartbeat starts as [block] ends is cancelled with it, when
     * [StreamAuthorisation.allows] suspends as it should. One that blocks its thread or runs
     * `NonCancellable` can refuse after [block] has returned, and the stream that ran to its end is
     * then reported refused, with its last words sent.
     *
     * Adapters call this. Returns whether the stream ran to the end of [block] without a refusal.
     */
    public suspend fun stream(
        sink: SseSink,
        authorisation: StreamAuthorisation?,
        block: suspend DatastarStream.() -> Unit,
    ): Boolean = stream(sink, authorisation, heartbeat, block = block)

    /**
     * [stream] with a [heartbeat] for this stream alone, in place of the instance's: an interval,
     * or `null` for none. Adapters pass what their caller asked for.
     *
     * The heartbeat runs in [heartbeatContext] added to the caller's. An adapter whose [block] may
     * block its thread, as a servlet's can, passes a dispatcher of its own, so the comment is not
     * held back while the thread is. [StreamAuthorisation.allows] and
     * [StreamAuthorisation.onRefused] can then run there, without the caller's thread-locals. The
     * context must not carry a [Job]: the heartbeat stays a child of the stream, which waits for it
     * before it returns.
     *
     * The JVM name stays plain through `@JvmName`: a `Duration` parameter would otherwise add a
     * hash of the signature to it, which changes with every parameter added.
     */
    @JvmName("stream")
    public suspend fun stream(
        sink: SseSink,
        authorisation: StreamAuthorisation?,
        heartbeat: Duration?,
        heartbeatContext: CoroutineContext = EmptyCoroutineContext,
        block: suspend DatastarStream.() -> Unit,
    ): Boolean {
        requireHeartbeat(heartbeat)
        require(heartbeatContext[Job] == null) { "heartbeatContext must not carry a Job; the heartbeat belongs to the stream" }
        val stream = SseDatastarStream(sink, codec, ::guard, authorisation)
        return try {
            stream.authorise()
            val interval = heartbeat
            if (interval == null) {
                stream.block()
            } else {
                coroutineScope {
                    val pulse = launch(heartbeatContext) { stream.pulse(interval) }
                    try {
                        stream.block()
                    } finally {
                        // Joined, so the response never closes under a comment half written.
                        pulse.cancelAndJoin()
                    }
                }
            }
            !stream.isRefused
        } catch (_: StreamRefusedException) {
            false
        }
    }

    /** Encode a flow of events into a flow of SSE frames, one string per event. */
    public fun encode(events: Flow<DatastarEvent>): Flow<String> = events.map { SseEncoder.encode(guard(it)) }

    /**
     * The event, unchanged. When [guardElements] is on and the event patches elements, the HTML
     * has passed [ElementsGuard] first. Adapters call this before anything reaches the wire.
     */
    public fun guard(event: DatastarEvent): DatastarEvent {
        if (guardElements && event is PatchElements) {
            event.elements?.let { ElementsGuard.check(it, attributePrefixes, foreign = event.namespace != ElementNamespace.DEFAULT) }
        }
        return event
    }

    /** The response, unchanged; with [guardElements] on, an [ElementsResponse] has passed [ElementsGuard] first. */
    public fun guard(response: DatastarResponse): DatastarResponse {
        if (guardElements && response is ElementsResponse) {
            ElementsGuard.check(response.elements, attributePrefixes, foreign = response.namespace != ElementNamespace.DEFAULT)
        }
        return response
    }

    /**
     * The raw JSON text of the signals in a request, or `null` when there are none.
     * Applies the protocol rule: query parameter `datastar` for GET and DELETE, body otherwise.
     *
     * While [requireJsonContentType] is on, a request whose body is declared anything but
     * `application/json` throws [SignalsMediaTypeException] before the body is read. A request
     * with no `Content-Type` has its body read first, within [maxSignalsSize], and throws it
     * after the read when the body is not blank; a blank one is no signals, and `null`.
     */
    public suspend fun readSignalsJson(request: IncomingRequest): String? {
        val inQuery = request.carriesSignalsInQuery
        val checked = !inQuery && requireJsonContentType
        val contentType = if (checked) request.header(CONTENT_TYPE) else null
        // Before the read: a container may already have taken a form body apart.
        if (checked && contentType != null && !isJson(contentType)) throw SignalsMediaTypeException(contentType)
        val raw =
            if (inQuery) {
                request.queryParameter(DatastarProtocol.SIGNALS_PARAMETER)
            } else {
                request.bodyText(maxSignalsSize)
            }
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        if (text.length > maxSignalsSize) throw SignalsTooLargeException(text.length.toLong(), maxSignalsSize)
        if (checked && contentType == null) throw SignalsMediaTypeException(null)
        return text
    }

    /** The signals as a [JsonObject]; [JsonObject.EMPTY] when the request carried none. */
    public suspend fun readSignals(request: IncomingRequest): Signals =
        readSignalsJson(request)?.let { JsonParser.parseObject(it) } ?: JsonObject.EMPTY

    /**
     * The signals decoded into [type] by the codec, or `null` when the request carried none.
     *
     * Signals nested deeper than [MAX_SIGNALS_DEPTH] throw [SignalsDecodeException] before the
     * codec sees them, whichever codec it is.
     */
    public suspend fun <T : Any> readSignals(
        request: IncomingRequest,
        type: KType,
    ): T? {
        val json = readSignalsJson(request) ?: return null
        if (JsonParser.nestsDeeperThan(json, MAX_SIGNALS_DEPTH)) {
            throw SignalsDecodeException("Signals nested deeper than $MAX_SIGNALS_DEPTH levels")
        }
        return try {
            codec.decode(json, type)
        } catch (e: StreamlordException) {
            throw e
        } catch (e: Exception) {
            throw SignalsDecodeException("Could not decode signals into $type", e)
        }
    }

    public companion object {
        /** One mebibyte. Generous for signals, fatal for nobody. */
        public const val DEFAULT_MAX_SIGNALS_SIZE: Int = 1_048_576

        /**
         * How deep objects and arrays may nest in signals read into a type: 64, the depth the
         * built-in parser reads and [io.github.markusaugust.streamlord.core.json.JsonWriter]
         * writes. It is checked before the codec runs, because a codec that recurses, as
         * kotlinx.serialization does into a `JsonElement`, overflows the stack on twenty
         * kilobytes of brackets, and a `StackOverflowError` is no exception a handler can answer.
         */
        public const val MAX_SIGNALS_DEPTH: Int = JsonParser.DEFAULT_MAX_DEPTH

        /**
         * Thirty seconds: under the idle timeouts of most proxies and load balancers, at one
         * comment per silent half minute per stream. A proxy that closes sooner wants a shorter
         * one.
         */
        public val DEFAULT_HEARTBEAT: Duration = 30.seconds

        /** A Streamlord with the built-in codec and default limits. */
        public val Default: Streamlord = Streamlord()
    }
}

private const val CONTENT_TYPE = "Content-Type"

/** `application/json` in any case, with or without parameters such as a charset. */
private fun isJson(contentType: String): Boolean =
    contentType.substringBefore(';').trim().equals(DatastarProtocol.CONTENT_TYPE_JSON, ignoreCase = true)

private fun requireHeartbeat(heartbeat: Duration?) {
    require(heartbeat == null || heartbeat.isPositive()) { "heartbeat must be positive, or null for none" }
}

/** The signals decoded into [T], or `null` when the request carried none. */
public suspend inline fun <reified T : Any> Streamlord.readSignals(request: IncomingRequest): T? = readSignals(request, typeOf<T>())
