package io.github.markusaugust.streamlord.test

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonValue
import io.github.markusaugust.streamlord.core.json.JsonWriter
import io.github.markusaugust.streamlord.core.json.mergePatch
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import io.github.markusaugust.streamlord.core.protocol.SseMessage

/**
 * What a Datastar response said, ready to be asserted on.
 *
 * ```kotlin
 * val events = datastarEvents(client.get("/feed").bodyAsText())
 *
 * events.assertPatchElements(selector = "#feed", mode = ElementPatchMode.APPEND)
 * events.assertSignal("heads", 13)
 * ```
 *
 * It is a `List<DatastarEvent>`, so your own framework's assertions work on it unchanged and
 * these are a convenience rather than a cage.
 *
 * **Every assertion here is satisfied by one matching event and ignores the rest.** A test about
 * markup should not go red because a handler started patching a signal as well. When the whole
 * stream is the point, and sometimes it is, [assertExactly] says so out loud.
 *
 * Nothing in this module binds a test framework. A failure is an `AssertionError`, which every
 * runner understands, so kotlin.test, JUnit, Kotest and TestNG all work and none is required.
 */
public class DatastarEvents(
    private val events: List<DatastarEvent>,
    /** Every SSE message, Datastar's or not: comments, ids, anything else the stream carried. */
    public val messages: List<SseMessage>,
) : List<DatastarEvent> by events {
    /**
     * The signal store as the browser would hold it once the whole stream has been applied:
     * every [PatchSignals] folded in with the RFC 7386 merge the client uses, in order.
     *
     * So a signal patched twice reads as its last value, and one patched to `null` is gone,
     * which is what a test usually means when it asks what a signal ended up as.
     */
    public val signals: JsonObject by lazy {
        var store: JsonValue = JsonObject.EMPTY
        for (event in events.filterIsInstance<PatchSignals>()) {
            val patch = JsonParser.parse(event.signals)
            val applied =
                if (!event.onlyIfMissing || patch !is JsonObject) {
                    patch
                } else {
                    val existing = store as? JsonObject ?: JsonObject.EMPTY
                    JsonObject(patch.filterKeys { !existing.containsKey(it) })
                }
            store = mergePatch(store, applied)
        }
        store as? JsonObject ?: JsonObject.EMPTY
    }

    /**
     * One element patch matching every argument given, ignoring the arguments left out.
     *
     * @param selector The `selector` the patch targets.
     * @param mode The patch mode. Remember the default is [ElementPatchMode.OUTER]; a patch sent
     *   without a mode matches `OUTER` here, because that is what the client will do with it.
     * @param elements The markup, compared whole.
     * @param containing A fragment the markup must contain, for the common case where the whole
     *   of it is not the point.
     * @return the matching patch, so a test can go on asserting on it with its own tools.
     */
    public fun assertPatchElements(
        selector: String? = null,
        mode: ElementPatchMode? = null,
        elements: String? = null,
        containing: String? = null,
    ): PatchElements {
        val wanted =
            listOfNotNull(
                selector?.let { "selector $it" },
                mode?.let { "mode ${it.wire}" },
                elements?.let { "elements exactly $it" },
                containing?.let { "elements containing $it" },
            )
        return events.filterIsInstance<PatchElements>().firstOrNull { patch ->
            (selector == null || patch.selector == selector) &&
                (mode == null || patch.mode == mode) &&
                (elements == null || patch.elements == elements) &&
                (containing == null || patch.elements?.contains(containing) == true)
        } ?: fail("No element patch with ${wanted.ifEmpty { listOf("any shape") }.joinToString(", ")}")
    }

    /**
     * One signal patch matching every argument given.
     *
     * To ask what a signal ended up as, rather than that some patch carried it, use
     * [assertSignal]: a stream may set a signal and change it again.
     */
    public fun assertPatchSignals(
        signals: String? = null,
        containing: String? = null,
        onlyIfMissing: Boolean? = null,
    ): PatchSignals {
        val wanted =
            listOfNotNull(
                signals?.let { "signals exactly $it" },
                containing?.let { "signals containing $it" },
                onlyIfMissing?.let { "onlyIfMissing $it" },
            )
        return events.filterIsInstance<PatchSignals>().firstOrNull { patch ->
            (signals == null || patch.signals == signals) &&
                (containing == null || patch.signals.contains(containing)) &&
                (onlyIfMissing == null || patch.onlyIfMissing == onlyIfMissing)
        } ?: fail("No signal patch with ${wanted.ifEmpty { listOf("any shape") }.joinToString(", ")}")
    }

    /**
     * The value a signal ended up with once the whole stream was applied.
     *
     * [expected] is compared as JSON, so `13`, `"ash"`, `true` and `null` all say what they look
     * like, and a nested path is reached with dots: `assertSignal("address.city", "Thurn")`.
     */
    public fun assertSignal(
        name: String,
        expected: Any?,
    ) {
        val actual = path(name)
        val wanted = JsonParser.parse(JsonWriter.write(expected))
        if (actual != wanted) {
            fail("Signal '$name' is ${actual?.toJson() ?: "not set"}, expected ${wanted.toJson()}")
        }
    }

    /** That a signal never arrived at all, or was removed by a later patch. */
    public fun assertNoSignal(name: String) {
        val actual = path(name)
        if (actual != null) fail("Signal '$name' is ${actual.toJson()}, expected it not to be set")
    }

    /** That the stream carried no Datastar events, whatever else it carried. */
    public fun assertNoEvents() {
        if (events.isNotEmpty()) fail("Expected no Datastar events")
    }

    /**
     * That the stream was exactly these events, in this order and no others.
     *
     * The strict counterpart to the rest of this class, for a handler where the number of events
     * is the point: the counter that must send thirty and not thirty-one.
     */
    public fun assertExactly(vararg expected: DatastarEvent) {
        if (events != expected.toList()) {
            fail(
                "Expected exactly ${expected.size} ${if (expected.size == 1) "event" else "events"}:\n" +
                    expected.joinToString("\n") { "  " + describe(it) },
            )
        }
    }

    /** The value at a dotted path in the folded store, or `null` when nothing is there. */
    private fun path(name: String): JsonValue? {
        var here: JsonValue? = signals
        for (segment in name.split('.')) {
            here = (here as? JsonObject)?.get(segment) ?: return null
        }
        return here
    }

    /** Every failure prints the stream, because the first question is always what it did send. */
    private fun fail(what: String): Nothing =
        throw AssertionError(
            buildString {
                append(what)
                append(".\n\nThe stream carried ")
                if (events.isEmpty()) {
                    append("no Datastar events")
                    if (messages.isNotEmpty()) append(" and ${messages.size} other message(s)")
                    append(".")
                } else {
                    append("${events.size} ${if (events.size == 1) "event" else "events"}:\n")
                    events.forEach { append("  ").append(describe(it)).append('\n') }
                }
            },
        )

    private fun describe(event: DatastarEvent): String = SseEncoder.encode(event).trimEnd().replace("\n", " | ")

    override fun toString(): String = events.joinToString("\n") { describe(it) }
}

/**
 * Read a Datastar response body back into the events it carried.
 *
 * Give it whatever your test host hands you: `client.get("/feed").bodyAsText()` on Ktor,
 * `response.contentAsString` on Spring's `MockHttpServletResponse`.
 */
public fun datastarEvents(wire: String): DatastarEvents = DatastarEvents(SseDecoder.decode(wire), SseDecoder.messages(wire))
