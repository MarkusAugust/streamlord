package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
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
