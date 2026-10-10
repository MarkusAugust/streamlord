package io.github.markusaugust.streamlord.core.port.driving

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.domain.withEventId
import io.github.markusaugust.streamlord.core.json.JsonWriter
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import org.intellij.lang.annotations.Language
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.TimeSource

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
     * Keep the page in step with [states]: render each state into events and send them, until
     * the flow completes.
     *
     * Made for a view the server re-renders whole from its state, a region or the page, and lets
     * the client's morph work out what changed. Three things keep that cheap:
     *
     * - **Only the latest state is rendered.** States that arrive while a render is being sent
     *   are dropped for the newest one, so a burst of changes costs one render, not one each.
     * - **At most one send per [minInterval],** measured from the start of one render to the
     *   start of the next. The first change goes out at once, and the newest of those that arrive
     *   within the interval after it goes out when it ends. With zero, the default, a render goes
     *   out as soon as the previous one is written. After the last render of a flow that
     *   completes, the interval is waited out before this returns.
     * - **A render that changes nothing is not sent.** Its events are compared with the last
     *   ones sent, by [SseEncoder.fingerprint], and dropped when they are the same.
     *
     * The fingerprint goes out as the id of the last event of each render, so the client sends it
     * back as `last-event-id` when it reconnects. Pass that header as [resumeFrom] and a reconnect
     * whose first render matches what the page already shows sends nothing. Without it, the first
     * render always goes out, which is the safe default: a page that missed a render while it was
     * away gets the whole of it.
     *
     * ```kotlin
     * get("/board") {
     *     call.respondDatastar {
     *         sendLatest(board, resumeFrom = call.request.headers["last-event-id"]) { state ->
     *             listOf(PatchElements(renderBoard(state), selector = "#board", mode = ElementPatchMode.INNER))
     *         }
     *     }
     * }
     * ```
     *
     * A [StateFlow][kotlinx.coroutines.flow.StateFlow] is the usual source: it starts with the
     * current state, which is what a stream that has just opened needs first, and it never
     * completes, so the stream lives until the reader leaves.
     *
     * It is for views: what the events describe must be the whole of what the region shows, and
     * nothing else may patch it, or an unchanged render that is skipped would leave it wrong. A
     * render that carries an [ExecuteScript] runs it again whenever the render changes, and not
     * when it does not; one-off effects belong in [executeScript] directly. [resumeFrom] comes
     * from the client and is only compared, never trusted. A frame cut by a dropped connection
     * after its `id:` line and before its end leaves the client holding an id it never applied;
     * the page is then corrected by the next change rather than on reconnect.
     *
     * @param render The events for one state. An empty list sends nothing. The last event's id is
     *   replaced by the fingerprint; the others keep theirs.
     */
    public suspend fun <S> sendLatest(
        states: Flow<S>,
        minInterval: Duration = Duration.ZERO,
        resumeFrom: String? = null,
        render: suspend (S) -> List<DatastarEvent>,
    ) {
        require(!minInterval.isNegative()) { "minInterval must not be negative" }
        var last = resumeFrom
        states.conflate().collect { state ->
            val started = TimeSource.Monotonic.markNow()
            val events = render(state)
            if (events.isEmpty()) return@collect
            val fingerprint = SseEncoder.fingerprint(events)
            if (fingerprint == last) return@collect
            events.forEachIndexed { index, event -> send(if (index == events.lastIndex) event.withEventId(fingerprint) else event) }
            last = fingerprint
            val rest = minInterval - started.elapsedNow()
            if (rest.isPositive()) delay(rest)
        }
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

    /**
     * Send the browser to another page. The URL is safely quoted. The navigation runs from a
     * `setTimeout`, as the official SDKs do, so the script element is applied and removed before
     * the page unloads.
     */
    public suspend fun redirect(url: String) {
        executeScript("setTimeout(() => { window.location.href = ${JsonWriter.writeString(url)} })")
    }

    /**
     * Put [url] in the address bar without loading it, replacing the current history entry.
     *
     * For a page whose state the server owns: a filter, a search, a selected row. The server
     * builds the canonical URL once, from the same state it rendered, and the reader can
     * bookmark, share or reload it, provided the route answers a plain request with the page.
     * The back button is not affected, which is what a filter that changes on every keystroke
     * wants. The history entry's state is kept, for any script that stored one there.
     *
     * The URL is quoted as [redirect] quotes it. The browser refuses a URL of another origin with
     * a `SecurityError`, so pass a path, or an absolute URL on the page's own origin.
     */
    public suspend fun replaceUrl(url: String) {
        executeScript("window.history.replaceState(window.history.state, '', ${JsonWriter.writeString(url)})")
    }

    /**
     * Put [url] in the address bar without loading it, as a new history entry.
     *
     * Back then returns to the previous URL without reloading, and the page hears it as a
     * `popstate` on `window`. A page that pushes URLs listens for it and asks the server to render
     * the URL it has returned to; that answer replaces or keeps the URL and never pushes it again,
     * or Back would only ever lead forward. The new entry carries no state; Datastar keeps none.
     *
     * Quoted and refused as for [replaceUrl].
     */
    public suspend fun pushUrl(url: String) {
        executeScript("window.history.pushState(null, '', ${JsonWriter.writeString(url)})")
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
