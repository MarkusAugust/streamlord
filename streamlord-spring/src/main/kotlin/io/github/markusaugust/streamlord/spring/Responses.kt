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

/** This response as a [ResponseEntity] with the right content type and `datastar-*` headers. */
public fun DatastarResponse.toResponseEntity(
    status: HttpStatus = HttpStatus.OK,
    streamlord: Streamlord = Streamlord.Default,
): ResponseEntity<String> {
    streamlord.guard(this)
    val builder = ResponseEntity.status(status).contentType(MediaType.parseMediaType(contentType))
    for ((name, value) in headers) builder.header(name, value)
    return builder.body(body)
}

/**
 * HTML to be patched as elements, without opening a stream.
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
    status: HttpStatus = HttpStatus.OK,
    streamlord: Streamlord = Streamlord.Default,
): ResponseEntity<String> = ElementsResponse(elements, selector, mode, namespace, useViewTransition).toResponseEntity(status, streamlord)

/** A JSON object to be patched as signals, without opening a stream. */
public fun datastarSignals(
    @Language("JSON") signals: String,
    onlyIfMissing: Boolean = false,
    status: HttpStatus = HttpStatus.OK,
): ResponseEntity<String> = SignalsResponse(signals, onlyIfMissing).toResponseEntity(status)

/** Any value the codec can encode, to be patched as signals. */
public inline fun <reified T> datastarSignals(
    value: T,
    streamlord: Streamlord = Streamlord.Default,
    onlyIfMissing: Boolean = false,
    status: HttpStatus = HttpStatus.OK,
): ResponseEntity<String> = datastarSignals(streamlord.codec.encode(value, typeOf<T>()), onlyIfMissing, status)

/** JavaScript to execute, without opening a stream. */
public fun datastarScript(
    @Language("JavaScript") script: String,
    attributes: Map<String, String> = emptyMap(),
    status: HttpStatus = HttpStatus.OK,
): ResponseEntity<String> = ScriptResponse(script, attributes).toResponseEntity(status)
