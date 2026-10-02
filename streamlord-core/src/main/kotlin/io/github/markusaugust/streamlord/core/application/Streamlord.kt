package io.github.markusaugust.streamlord.core.application

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.StreamRefusedException
import io.github.markusaugust.streamlord.core.StreamlordException
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.DatastarResponse
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.reflect.KType
import kotlin.reflect.typeOf

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
 */
public class Streamlord(
    public val codec: SignalsCodec = BuiltInSignalsCodec,
    public val maxSignalsSize: Int = DEFAULT_MAX_SIGNALS_SIZE,
    public val guardElements: Boolean = false,
    public val attributePrefixes: List<String> = ElementsGuard.defaultPrefixes,
) {
    init {
        require(maxSignalsSize > 0) { "maxSignalsSize must be positive" }
    }

    /** Open a [DatastarStream] over a sink. Adapters call this; you rarely need to. */
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
     * Adapters call this. Returns whether the stream ran to the end of [block].
     */
    public suspend fun stream(
        sink: SseSink,
        authorisation: StreamAuthorisation?,
        block: suspend DatastarStream.() -> Unit,
    ): Boolean {
        val stream = SseDatastarStream(sink, codec, ::guard, authorisation)
        return try {
            stream.authorise()
            stream.block()
            true
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
        if (guardElements && event is PatchElements) event.elements?.let { ElementsGuard.check(it, attributePrefixes) }
        return event
    }

    /** The response, unchanged; with [guardElements] on, an [ElementsResponse] has passed [ElementsGuard] first. */
    public fun guard(response: DatastarResponse): DatastarResponse {
        if (guardElements && response is ElementsResponse) ElementsGuard.check(response.elements, attributePrefixes)
        return response
    }

    /**
     * The raw JSON text of the signals in a request, or `null` when there are none.
     * Applies the protocol rule: query parameter `datastar` for GET and DELETE, body otherwise.
     */
    public suspend fun readSignalsJson(request: IncomingRequest): String? {
        val raw =
            if (request.carriesSignalsInQuery) {
                request.queryParameter(DatastarProtocol.SIGNALS_PARAMETER)
            } else {
                request.bodyText(maxSignalsSize)
            }
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        if (text.length > maxSignalsSize) throw SignalsTooLargeException(text.length.toLong(), maxSignalsSize)
        return text
    }

    /** The signals as a [JsonObject]; [JsonObject.EMPTY] when the request carried none. */
    public suspend fun readSignals(request: IncomingRequest): Signals =
        readSignalsJson(request)?.let { JsonParser.parseObject(it) } ?: JsonObject.EMPTY

    /** The signals decoded into [type] by the codec, or `null` when the request carried none. */
    public suspend fun <T : Any> readSignals(
        request: IncomingRequest,
        type: KType,
    ): T? {
        val json = readSignalsJson(request) ?: return null
        return try {
            codec.decode(json, type)
        } catch (e: StreamlordException) {
            throw e
        } catch (e: Exception) {
            throw SignalsCodecException("Could not decode signals into $type", e)
        }
    }

    public companion object {
        /** One mebibyte. Generous for signals, fatal for nobody. */
        public const val DEFAULT_MAX_SIGNALS_SIZE: Int = 1_048_576

        /** A Streamlord with the built-in codec and default limits. */
        public val Default: Streamlord = Streamlord()
    }
}

/** The signals decoded into [T], or `null` when the request carried none. */
public suspend inline fun <reified T : Any> Streamlord.readSignals(request: IncomingRequest): T? = readSignals(request, typeOf<T>())
