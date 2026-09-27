package io.github.markusaugust.streamlord.core.port.driving

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.json.JsonWriter
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import kotlinx.coroutines.flow.Flow
import org.intellij.lang.annotations.Language
import kotlin.reflect.typeOf
import kotlin.time.Duration

/**
 * Driving port: the hand that holds the stream.
 *
 * This is the API your route handlers and controllers speak to. Every method is a suspend
 * function that encodes an event, writes it and flushes it before returning, in order, no
 * matter how many coroutines share the stream. The stream stays open for as long as the
 * enclosing response does; return from the response block, and it closes.
 *
 * Only [send] and [comment] are abstract. Everything else is sugar with the defaults the
 * Datastar SDK specification prescribes.
 */
public interface DatastarStream {
    /** The codec used by the typed [patchSignals] extension. */
    public val codec: SignalsCodec

    /** Encode, write and flush one event. The primitive everything else is built on. */
    public suspend fun send(event: DatastarEvent)

    /** Write an SSE comment. The client ignores it; proxies and idle timeouts do not. */
    public suspend fun comment(text: String = "keep-alive")

    /** Send every event of a [Flow], in order, until the flow completes. */
    public suspend fun sendAll(events: Flow<DatastarEvent>) {
        events.collect { send(it) }
    }

    /**
     * Patch complete HTML elements into the DOM. See [PatchElements] for the rules.
     *
     * ```kotlin
     * patchElements("""<div id="feed">Ashes</div>""")
     * patchElements("<li>New</li>", selector = "#feed", mode = ElementPatchMode.APPEND)
     * ```
     */
    public suspend fun patchElements(
        @Language("HTML") elements: String,
        selector: String? = null,
        mode: ElementPatchMode = ElementPatchMode.DEFAULT,
        namespace: ElementNamespace = ElementNamespace.DEFAULT,
        useViewTransition: Boolean = false,
        viewTransitionSelector: String? = null,
        eventId: String? = null,
        retry: Duration? = null,
    ) {
        send(
            PatchElements(
                elements = elements,
                selector = selector,
                mode = mode,
                namespace = namespace,
                useViewTransition = useViewTransition,
                viewTransitionSelector = viewTransitionSelector,
                eventId = eventId,
                retry = retry,
            ),
        )
    }

    /** Remove every element matching [selector]. */
    public suspend fun removeElements(
        selector: String,
        useViewTransition: Boolean = false,
        eventId: String? = null,
        retry: Duration? = null,
    ) {
        send(PatchElements.remove(selector, useViewTransition, eventId, retry))
    }

    /** Patch signals from a JSON object text. */
    public suspend fun patchSignals(
        @Language("JSON") signals: String,
        onlyIfMissing: Boolean = false,
        eventId: String? = null,
        retry: Duration? = null,
    ) {
        send(PatchSignals(signals, onlyIfMissing, eventId, retry))
    }

    /**
     * Patch signals from key/value pairs. Values may be primitives, maps, lists or `null`
     * (which removes the signal).
     *
     * ```kotlin
     * patchSignals("count" to 42, "user" to mapOf("name" to "Gorvek"), "draft" to null)
     * ```
     */
    public suspend fun patchSignals(
        vararg signals: Pair<String, Any?>,
        onlyIfMissing: Boolean = false,
        eventId: String? = null,
        retry: Duration? = null,
    ) {
        send(PatchSignals(JsonWriter.write(signals.toMap()), onlyIfMissing, eventId, retry))
    }

    /** Remove signals by name. Sugar for patching them to `null`. */
    public suspend fun removeSignals(
        vararg names: String,
        eventId: String? = null,
        retry: Duration? = null,
    ) {
        send(PatchSignals(JsonWriter.write(names.associateWith { null }), eventId = eventId, retry = retry))
    }

    /** Execute JavaScript in the browser. See [ExecuteScript]. */
    public suspend fun executeScript(
        @Language("JavaScript") script: String,
        autoRemove: Boolean = true,
        attributes: Map<String, String> = emptyMap(),
        eventId: String? = null,
        retry: Duration? = null,
    ) {
        send(ExecuteScript(script, autoRemove, attributes, eventId, retry))
    }

    /** Send the browser to another page. The URL is safely quoted. */
    public suspend fun redirect(url: String) {
        executeScript("window.location.href = ${JsonWriter.writeString(url)}")
    }
}

/**
 * Patch signals from any value the configured [SignalsCodec] can encode: a data class, a map,
 * a list. Requires a codec adapter for data classes.
 */
public suspend inline fun <reified T> DatastarStream.patchSignals(
    value: T,
    onlyIfMissing: Boolean = false,
    eventId: String? = null,
    retry: Duration? = null,
) {
    patchSignals(codec.encode(value, typeOf<T>()), onlyIfMissing, eventId, retry)
}
