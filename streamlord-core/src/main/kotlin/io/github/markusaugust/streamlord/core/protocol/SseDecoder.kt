package io.github.markusaugust.streamlord.core.protocol

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.domain.Wire
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol.DataLines
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads the text a Datastar stream carried back into [DatastarEvent]s. The inverse of
 * [SseEncoder], and pure in the same way: the same text always yields the same events.
 *
 * This is what lets a test assert on what a handler meant rather than on the bytes it happened
 * to produce. A test that compares the whole response as one string goes red when a patch is
 * reordered or a class changes in the markup, which teaches people to stop writing them.
 *
 * It decodes what Datastar defines and nothing else. An event name the protocol does not know
 * is skipped rather than guessed at, because a stream may legitimately carry events meant for
 * something other than Datastar.
 *
 * [ExecuteScript][io.github.markusaugust.streamlord.core.domain.ExecuteScript] does not come
 * back as itself. The specification defines it as a `patch-elements` carrying a `<script>`, that
 * is what goes on the wire, and that is therefore what returns.
 */
public object SseDecoder {
    /**
     * Every Datastar event in [text], in the order the stream carried them.
     *
     * It reads a stream the way the browser does, so that a test cannot pass on a response the
     * client would not act on. A last message with no blank line after it is not an event, mode
     * and namespace tokens are matched exactly as written, and a signal patch needs its signals.
     *
     * @throws DatastarEventValidationException if a Datastar frame names a mode or namespace that
     *   does not exist, has no `signals` where it needs them, or carries a `retry` that is not a
     *   number. A malformed frame is a bug in whatever wrote it, and reporting it is more use
     *   than dropping it.
     */
    public fun decode(text: String): List<DatastarEvent> = messages(text).mapNotNull(::toEvent)

    /** One frame, for the caller who knows there is exactly one. */
    public fun decodeOne(text: String): DatastarEvent =
        decode(text).singleOrNull()
            ?: throw DatastarEventValidationException(
                "Expected exactly one Datastar event, found ${decode(text).size}",
            )

    /**
     * The SSE messages in [text], decoded as the grammar defines them and no further.
     *
     * Public because a stream carries more than Datastar's own events: a keep-alive comment, an
     * `id:` for resuming, an event from something else entirely. A caller checking any of those
     * needs the message, not the Datastar meaning of it.
     */
    public fun messages(text: String): List<SseMessage> {
        val out = ArrayList<SseMessage>()
        var event: String? = null
        var id: String? = null
        var retry: String? = null
        val data = ArrayList<String>()
        val comments = ArrayList<String>()

        fun flush() {
            if (event != null || id != null || retry != null || data.isNotEmpty() || comments.isNotEmpty()) {
                out += SseMessage(event, id, retry, data.toList(), comments.toList())
            }
            event = null
            id = null
            retry = null
            data.clear()
            comments.clear()
        }

        // One leading byte order mark is not part of the stream, and a line ends at CRLF, CR or LF.
        val lines = Wire.lines(text.removePrefix("\uFEFF"))
        // Text that ends in a line break splits into one empty piece too many: it is the end of
        // the last line, not a blank line after it.
        for (line in if (lines.last().isEmpty()) lines.dropLast(1) else lines) {
            if (line.isEmpty()) {
                flush()
                continue
            }
            if (line.startsWith(":")) {
                comments += line.removePrefix(":").removePrefix(" ")
                continue
            }
            // "field: value", and a field with no colon is a field with an empty value.
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            when (field) {
                "event" -> event = value
                "id" -> id = value
                "retry" -> retry = value
                "data" -> data += value
                else -> Unit // An unknown field is ignored, as the SSE grammar says to.
            }
        }
        // Comments are kept whatever follows them; fields with no blank line after them are an
        // unfinished message, which the SSE grammar discards and the client never dispatches.
        if (event == null && id == null && retry == null && data.isEmpty()) flush()
        return out
    }

    /**
     * The Datastar event one message carries, or `null` when it is not one of Datastar's: a
     * comment, an event name the protocol does not define. For messages that arrive one at a
     * time, from an [SseReader].
     *
     * @throws DatastarEventValidationException as [decode] does, for a Datastar frame the client
     *   would reject.
     */
    public fun event(message: SseMessage): DatastarEvent? = toEvent(message)

    private fun toEvent(message: SseMessage): DatastarEvent? {
        // Nothing of a message is judged until its event name says it is Datastar's.
        if (message.event != DatastarProtocol.Events.PATCH_ELEMENTS && message.event != DatastarProtocol.Events.PATCH_SIGNALS) return null
        val args = group(message.data)
        val retry = message.retry?.let(::parseRetry)
        // An empty id is valid SSE and means "no id"; the client drops its last-event-id on an
        // empty `id:` line. The client skips a bare `id` with no colon, as it skips every line
        // without one (fetch.ts:403 in Datastar 1.0.4).
        val id = message.id?.takeIf { it.isNotEmpty() }
        return when (message.event) {
            DatastarProtocol.Events.PATCH_ELEMENTS -> {
                PatchElements(
                    elements = args[DataLines.ELEMENTS],
                    selector = args[DataLines.SELECTOR],
                    mode = args[DataLines.MODE]?.let(::mode) ?: ElementPatchMode.DEFAULT,
                    namespace = args[DataLines.NAMESPACE]?.let(::namespace) ?: ElementNamespace.DEFAULT,
                    useViewTransition = args[DataLines.USE_VIEW_TRANSITION] == "true",
                    viewTransitionSelector = args[DataLines.VIEW_TRANSITION_SELECTOR],
                    eventId = id,
                    retry = retry,
                )
            }

            DatastarProtocol.Events.PATCH_SIGNALS -> {
                PatchSignals(
                    signals =
                        args[DataLines.SIGNALS]
                            ?: throw DatastarEventValidationException("A signal patch carried no signals line; the client rejects it"),
                    onlyIfMissing = args[DataLines.ONLY_IF_MISSING] == "true",
                    eventId = id,
                    retry = retry,
                )
            }

            else -> {
                null
            }
        }
    }

    /**
     * Data lines grouped by their first word and rejoined with newlines, which is how the client
     * reads them: `data: elements <li>a</li>` twice is one two-line value, not two values.
     */
    private fun group(data: List<String>): Map<String, String> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (line in data) {
            val space = line.indexOf(' ')
            val key = if (space < 0) line else line.substring(0, space)
            val value = if (space < 0) "" else line.substring(space + 1)
            groups.getOrPut(key) { ArrayList() } += value
        }
        return groups.mapValues { it.value.joinToString("\n") }
    }

    private fun parseRetry(value: String): Duration =
        value.toLongOrNull()?.milliseconds
            ?: throw DatastarEventValidationException("retry must be a whole number of milliseconds, not '$value'")

    // Exact, where ElementPatchMode.fromWire is forgiving: the client compares these tokens as written.
    private fun mode(wire: String): ElementPatchMode =
        ElementPatchMode.entries.firstOrNull { it.wire == wire }
            ?: throw DatastarEventValidationException("Unknown element patch mode: '$wire'")

    private fun namespace(wire: String): ElementNamespace =
        ElementNamespace.entries.firstOrNull { it.wire == wire }
            ?: throw DatastarEventValidationException("Unknown element namespace: '$wire'")
}

/**
 * One Server-Sent Events message, as the grammar defines it and before any Datastar meaning is
 * read into it.
 *
 * @property event The `event:` field, or `null` when the message carried none.
 * @property id The `id:` field, which the client sends back as `last-event-id` on its next try.
 * @property retry The `retry:` field, as written.
 * @property data Every `data:` line, in order, each without its field name.
 * @property comments Every `:` line, which the client ignores and a proxy sees as traffic.
 */
public data class SseMessage(
    val event: String?,
    val id: String?,
    val retry: String?,
    val data: List<String>,
    val comments: List<String>,
)
