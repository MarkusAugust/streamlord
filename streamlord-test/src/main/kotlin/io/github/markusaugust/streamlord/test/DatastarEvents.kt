package io.github.markusaugust.streamlord.test

import io.github.markusaugust.streamlord.core.JsonParseException
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.domain.Wire
import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonNull
import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonValue
import io.github.markusaugust.streamlord.core.json.JsonWriter
import io.github.markusaugust.streamlord.core.json.mergePatchAsClient
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import io.github.markusaugust.streamlord.core.protocol.SseMessage
import kotlin.reflect.KClass

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
     * every [PatchSignals] folded in the way the Datastar client merges one, in order.
     *
     * So a signal patched twice reads as its last value, and one patched to `null` is gone,
     * which is what a test usually means when it asks what a signal ended up as. `onlyIfMissing`
     * is honoured leaf by leaf, as the client honours it: a nested object is still walked, and
     * only the values that are already there are left alone. A `null` inside a stored array, and
     * the hole a deleted index leaves, read as `""`, which is what the client hands an expression
     * and sends to the server. See `mergePatchAsClient`.
     *
     * @throws AssertionError if a patch carried signals that are not a JSON object.
     */
    public val signals: JsonObject by lazy {
        var store = JsonObject.EMPTY
        for (event in events.filterIsInstance<PatchSignals>()) {
            val patch =
                try {
                    JsonParser.parseObject(event.signals)
                } catch (e: JsonParseException) {
                    fail("A signal patch is not a JSON object (${e.message}): ${event.signals}")
                }
            store = fold(store, patch, event.onlyIfMissing)
        }
        store
    }

    /** The client's merge, with the hole limit of the core function turned into the assertion it is here. */
    private fun fold(
        target: JsonObject,
        patch: JsonObject,
        ifMissing: Boolean,
    ): JsonObject =
        try {
            mergePatchAsClient(target, patch, ifMissing)
        } catch (e: IllegalArgumentException) {
            throw AssertionError(e.message)
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
        val wanted = elementsWanted(selector, mode, elements, containing)
        return events.filterIsInstance<PatchElements>().firstOrNull { it.matches(selector, mode, elements, containing) }
            ?: fail("No element patch with $wanted")
    }

    /**
     * That no element patch matches every argument given, the arguments being those of
     * [assertPatchElements]. With none, that the stream patched no elements at all.
     *
     * An [io.github.markusaugust.streamlord.core.domain.ExecuteScript] goes on the wire as the
     * element patch that appends its `<script>` to `body`, and comes back as one, so a stream that
     * ran a script fails a call with no arguments.
     */
    public fun assertNoPatchElements(
        selector: String? = null,
        mode: ElementPatchMode? = null,
        elements: String? = null,
        containing: String? = null,
    ) {
        val found = events.filterIsInstance<PatchElements>().firstOrNull { it.matches(selector, mode, elements, containing) }
        if (found != null) {
            fail("Expected no element patch with ${elementsWanted(selector, mode, elements, containing)}, found ${describe(found)}")
        }
    }

    private fun PatchElements.matches(
        selector: String?,
        mode: ElementPatchMode?,
        elements: String?,
        containing: String?,
    ): Boolean =
        (selector == null || this.selector == selector) &&
            (mode == null || this.mode == mode) &&
            (elements == null || this.elements == onWire(elements)) &&
            (containing == null || this.elements?.contains(containing) == true)

    private fun elementsWanted(
        selector: String?,
        mode: ElementPatchMode?,
        elements: String?,
        containing: String?,
    ): String =
        listOfNotNull(
            selector?.let { "selector $it" },
            mode?.let { "mode ${it.wire}" },
            elements?.let { "elements exactly $it" },
            containing?.let { "elements containing $it" },
        ).ifEmpty { listOf("any shape") }.joinToString(", ")

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
     * [expected] is compared as JSON, so `13`, `"ash"` and `true` say what they look like, and
     * numbers are compared by value: `13`, `13.0` and `1.3e1` are one number, as they are in the
     * browser. A nested path is reached with dots: `assertSignal("address.city", "Thurn")`.
     *
     * `null` means the signal is not there, which is all a `null` ever leaves behind: patching a
     * signal to `null` removes it. It is the same question [assertNoSignal] asks.
     */
    public fun assertSignal(
        name: String,
        expected: Any?,
    ) {
        val actual = path(name)
        val wanted = JsonParser.parse(JsonWriter.write(expected))
        if (wanted is JsonNull) {
            if (actual != null) fail("Signal '$name' is ${actual.toJson()}, expected it not to be set")
            return
        }
        if (actual == null || !same(actual, wanted)) {
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
     *
     * Two events are the same when they put the same frame on the wire. So an `ExecuteScript` is
     * the element patch it is sent as, a `retry` of the protocol default is no `retry` at all, and
     * signals are compared as JSON rather than as text.
     */
    public fun assertExactly(vararg expected: DatastarEvent) {
        if (events.map(::frame) != expected.map(::frame)) {
            fail(
                "Expected exactly ${expected.size} ${if (expected.size == 1) "event" else "events"}:\n" +
                    expected.joinToString("\n") { "  " + describe(it) },
            )
        }
    }

    /**
     * That the stream was exactly these kinds of event, in this order and no others, whatever
     * each one carried: `assertEventTypes(PatchElements::class, PatchSignals::class)`.
     *
     * For a test about the shape of a stream, where [assertExactly] would have to spell out every
     * event in full. An [io.github.markusaugust.streamlord.core.domain.ExecuteScript] comes back
     * from the wire as the [PatchElements] it is sent as, so asking for one, or for the sealed
     * [DatastarEvent] itself, which no event is exactly, throws [IllegalArgumentException].
     */
    public fun assertEventTypes(vararg types: KClass<out DatastarEvent>) {
        for (type in types) {
            require(type == PatchElements::class || type == PatchSignals::class) {
                "${type.simpleName} never comes back from the wire; ask for PatchElements or PatchSignals"
            }
        }
        if (events.map { it::class } != types.toList()) {
            if (types.isEmpty()) fail("Expected no Datastar events")
            fail(
                "Expected exactly these event types: ${types.joinToString(", ") { it.simpleName.orEmpty() }}; " +
                    "found ${events.joinToString(", ") { it::class.simpleName.orEmpty() }.ifEmpty { "none" }}",
            )
        }
    }

    /**
     * The value at a dotted path in the folded store, or `null` when nothing is there. No key holds
     * a dot: [PatchSignals] refuses one, since the client could not reach it.
     */
    private fun path(name: String): JsonValue? =
        name.split('.').fold<String, JsonValue?>(signals) { here, key -> (here as? JsonObject)?.get(key) }

    /** JSON equality with numbers compared by value, so `13` is `13.0`. */
    private fun same(
        a: JsonValue,
        b: JsonValue,
    ): Boolean =
        when {
            a is JsonNumber && b is JsonNumber -> a.toBigDecimalOrNull()?.let { x -> b.toBigDecimalOrNull()?.compareTo(x) == 0 } ?: (a == b)
            a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, value) -> same(value, b.getValue(key)) }
            a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
            else -> a == b
        }

    /** The frame an event puts on the wire, with its signals in one spelling. */
    private fun frame(event: DatastarEvent): String =
        SseEncoder.encode(
            if (event is PatchSignals) {
                event.copy(signals = runCatching { JsonParser.parse(event.signals).toJson() }.getOrDefault(event.signals))
            } else {
                event
            },
        )

    /** Markup as the wire carries it: every kind of line break arrives as `\n`. */
    private fun onWire(elements: String): String = Wire.lines(elements).joinToString("\n")

    /** Every failure prints the stream, because the first question is always what it did send. */
    private fun fail(what: String): Nothing = throw AssertionError(withStream(what))

    /** [what], then what the stream carried, as every failure here prints it. */
    internal fun withStream(what: String): String =
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
        }

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
