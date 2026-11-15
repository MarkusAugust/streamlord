package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.application.Signals
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.port.driven.IncomingRequest
import io.github.markusaugust.streamlord.core.port.driven.isDatastarRequest
import io.github.markusaugust.streamlord.core.port.driven.rejectDeclaredLengthAbove
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.server.ServerWebExchange
import java.nio.charset.StandardCharsets
import kotlin.reflect.typeOf
import java.time.Duration as JavaDuration

/*
 * Spring WebFlux integration through Spring's own ServerSentEvent type, which lives in spring-web
 * and therefore costs nothing extra. A coroutine controller returns Flow<ServerSentEvent<String>>
 * and WebFlux writes the stream; Spring splits multi-line data into one `data:` line each, which
 * is exactly the Datastar wire format.
 */

/**
 * This event as a Spring [ServerSentEvent].
 *
 * @param streamlord The configured instance; with `guardElements` on, an element patch passes
 *   [io.github.markusaugust.streamlord.core.domain.ElementsGuard] first. Spring has no
 *   auto-configuration, so pass your bean; [Streamlord.Default] has the guard off.
 */
public fun DatastarEvent.toServerSentEvent(streamlord: Streamlord = Streamlord.Default): ServerSentEvent<String> {
    val frame = SseEncoder.frame(streamlord.guard(this))
    val builder = ServerSentEvent.builder(frame.data).event(frame.event)
    frame.id?.let { builder.id(it) }
    // The protocol default is left off the wire, as SseFrame.render leaves it off.
    frame.retry?.takeIf { it != DatastarProtocol.DEFAULT_RETRY }?.let { builder.retry(JavaDuration.ofMillis(it.inWholeMilliseconds)) }
    return builder.build()
}

/**
 * Map a flow of Datastar events to Spring server-sent events, ready to be returned from a
 * WebFlux controller.
 *
 * ```kotlin
 * @GetMapping("/feed", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
 * fun feed(): Flow<ServerSentEvent<String>> = ticker().asServerSentEvents()
 * ```
 *
 * @param streamlord The configured instance; with `guardElements` on, every element patch
 *   passes [io.github.markusaugust.streamlord.core.domain.ElementsGuard] before it is encoded.
 *   Its `heartbeat` and `compress` do not apply: the flow is mapped, never opened as a
 *   Streamlord stream.
 */
public fun Flow<DatastarEvent>.asServerSentEvents(streamlord: Streamlord = Streamlord.Default): Flow<ServerSentEvent<String>> =
    map { it.toServerSentEvent(streamlord) }

/**
 * The whole WebFlux response for a flow of Datastar events: the stream, and the headers a
 * stream needs to get through a proxy.
 *
 * ```kotlin
 * @GetMapping("/feed")
 * fun feed(): ResponseEntity<Flow<ServerSentEvent<String>>> = ticker().asDatastarResponse(streamlord)
 * ```
 *
 * A controller that returns the bare flow from [asServerSentEvents] gets the content type from
 * Spring and nothing else. This adds `Cache-Control: no-cache`, which the SDK specification
 * requires, and `X-Accel-Buffering: no`, without which nginx holds the events back until the
 * stream ends.
 *
 * @param streamlord The configured instance; see [asServerSentEvents].
 */
public fun Flow<DatastarEvent>.asDatastarResponse(
    streamlord: Streamlord = Streamlord.Default,
): ResponseEntity<Flow<ServerSentEvent<String>>> {
    val builder = ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
    for ((name, value) in DatastarProtocol.SSE_RESPONSE_HEADERS) builder.header(name, value)
    return builder.body(asServerSentEvents(streamlord))
}

/**
 * The [IncomingRequest] port implemented over a WebFlux exchange, as [ServletIncomingRequest] is
 * over a servlet request.
 *
 * The body is joined with a ceiling: a declared `Content-Length` above the limit is rejected
 * before a byte is read, and a body that runs past it is refused at the limit.
 */
public class WebFluxIncomingRequest(
    private val exchange: ServerWebExchange,
) : IncomingRequest {
    override val method: String get() = exchange.request.method.name()

    override fun queryParameter(name: String): String? = exchange.request.queryParams.getFirst(name)

    override fun header(name: String): String? = exchange.request.headers.getFirst(name)

    override suspend fun bodyText(maxBytes: Int): String {
        val headers = exchange.request.headers
        rejectDeclaredLengthAbove(maxBytes, headers.contentLength.takeIf { it >= 0 })
        val buffer =
            try {
                DataBufferUtils.join(exchange.request.body, maxBytes).toFuture().await() ?: return ""
            } catch (_: DataBufferLimitException) {
                throw SignalsTooLargeException(maxBytes.toLong() + 1, maxBytes)
            }
        return try {
            buffer.toString(headers.contentType?.charset ?: StandardCharsets.UTF_8)
        } finally {
            DataBufferUtils.release(buffer)
        }
    }
}

/** This exchange as the core's [IncomingRequest] port. */
public fun ServerWebExchange.asIncomingRequest(): IncomingRequest = WebFluxIncomingRequest(this)

/** `true` when the request came from a Datastar fetch action. */
public val ServerWebExchange.isDatastarRequest: Boolean get() = asIncomingRequest().isDatastarRequest

/** The raw JSON text of the signals, or `null` when the request carried none. */
public suspend fun ServerWebExchange.readSignalsJson(streamlord: Streamlord = Streamlord.Default): String? =
    streamlord.readSignalsJson(asIncomingRequest())

/**
 * The signals as a dependency-free [Signals] object; empty when the request carried none.
 *
 * ```kotlin
 * @PostMapping("/search")
 * suspend fun search(exchange: ServerWebExchange): ResponseEntity<Flow<ServerSentEvent<String>>> {
 *     val query = exchange.readSignals().string("query") ?: ""
 *     return flowOf(PatchElements(results(query))).asDatastarResponse()
 * }
 * ```
 */
public suspend fun ServerWebExchange.readSignals(streamlord: Streamlord = Streamlord.Default): Signals =
    streamlord.readSignals(asIncomingRequest())

/** The signals decoded into [T] by the codec, or `null` when the request carried none. */
@JvmName("readSignalsTyped")
public suspend inline fun <reified T : Any> ServerWebExchange.readSignals(streamlord: Streamlord = Streamlord.Default): T? =
    streamlord.readSignals(asIncomingRequest(), typeOf<T>())

/** The signals decoded into [T], or [default] when the request carried none. */
public suspend inline fun <reified T : Any> ServerWebExchange.readSignalsOr(
    default: T,
    streamlord: Streamlord = Streamlord.Default,
): T = readSignals<T>(streamlord) ?: default
