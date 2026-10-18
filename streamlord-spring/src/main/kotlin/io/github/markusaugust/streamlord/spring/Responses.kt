package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.DatastarResponse
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.ScriptResponse
import io.github.markusaugust.streamlord.core.domain.SignalsResponse
import org.intellij.lang.annotations.Language
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import kotlin.reflect.typeOf

/*
 * Non-SSE Datastar responses as ResponseEntity, for both WebMVC and WebFlux controllers.
 */

/**
 * This response as a [ResponseEntity] with the right content type and `datastar-*` headers.
 *
 * Always `200 OK`: Datastar's client reads the body of no other status.
 */
public fun DatastarResponse.toResponseEntity(streamlord: Streamlord = Streamlord.Default): ResponseEntity<String> =
    entity(HttpStatus.OK, streamlord)

private fun DatastarResponse.entity(
    status: HttpStatus,
    streamlord: Streamlord,
): ResponseEntity<String> {
    streamlord.guard(this)
    val builder = ResponseEntity.status(status).contentType(MediaType.parseMediaType(contentType))
    for ((name, value) in headers) builder.header(name, value)
    return builder.body(body)
}

/**
 * HTML to be patched as elements, without opening a stream. Always `200 OK`: Datastar's client
 * reads the body of no other status.
 *
 * @param streamlord The configured instance; with `guardElements` on, the HTML passes
 *   [io.github.markusaugust.streamlord.core.domain.ElementsGuard] first.
 */
public fun datastarElements(
    @Language("HTML") elements: String,
    selector: String? = null,
    mode: ElementPatchMode = ElementPatchMode.DEFAULT,
    namespace: ElementNamespace = ElementNamespace.DEFAULT,
    useViewTransition: Boolean = false,
    streamlord: Streamlord = Streamlord.Default,
): ResponseEntity<String> = ElementsResponse(elements, selector, mode, namespace, useViewTransition).toResponseEntity(streamlord)

/** A JSON object to be patched as signals, without opening a stream. Always `200 OK`, as [datastarElements]. */
public fun datastarSignals(
    @Language("JSON") signals: String,
    onlyIfMissing: Boolean = false,
): ResponseEntity<String> = SignalsResponse(signals, onlyIfMissing).toResponseEntity()

/** Any value the codec can encode, to be patched as signals. Always `200 OK`, as [datastarElements]. */
public inline fun <reified T> datastarSignals(
    value: T,
    streamlord: Streamlord = Streamlord.Default,
    onlyIfMissing: Boolean = false,
): ResponseEntity<String> = datastarSignals(streamlord.codec.encode(value, typeOf<T>()), onlyIfMissing)

/** JavaScript to execute, without opening a stream. Always `200 OK`, as [datastarElements]. */
public fun datastarScript(
    @Language("JavaScript") script: String,
    attributes: Map<String, String> = emptyMap(),
): ResponseEntity<String> = ScriptResponse(script, attributes).toResponseEntity()

/*
 * The 0.12 signatures, with a status Datastar's client ignored the body of for anything but 200.
 * Kept for code compiled against them; the inline one's body, inlined into such code, calls the
 * String variant.
 */

/** [toResponseEntity] as 0.12 had it, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public fun DatastarResponse.toResponseEntity(
    status: HttpStatus = HttpStatus.OK,
    streamlord: Streamlord = Streamlord.Default,
): ResponseEntity<String> = entity(status, streamlord)

/** [datastarElements] as 0.12 had it, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public fun datastarElements(
    elements: String,
    selector: String? = null,
    mode: ElementPatchMode = ElementPatchMode.DEFAULT,
    namespace: ElementNamespace = ElementNamespace.DEFAULT,
    useViewTransition: Boolean = false,
    status: HttpStatus = HttpStatus.OK,
    streamlord: Streamlord = Streamlord.Default,
): ResponseEntity<String> = ElementsResponse(elements, selector, mode, namespace, useViewTransition).entity(status, streamlord)

/** [datastarSignals] as 0.12 had it, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public fun datastarSignals(
    signals: String,
    onlyIfMissing: Boolean = false,
    status: HttpStatus = HttpStatus.OK,
): ResponseEntity<String> = SignalsResponse(signals, onlyIfMissing).entity(status, Streamlord.Default)

/** [datastarScript] as 0.12 had it, for code compiled against it. */
@Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
public fun datastarScript(
    script: String,
    attributes: Map<String, String> = emptyMap(),
    status: HttpStatus = HttpStatus.OK,
): ResponseEntity<String> = ScriptResponse(script, attributes).entity(status, Streamlord.Default)
