package io.github.markusaugust.streamlord.core.protocol

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
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
     * It reads a stream the way Datastar's client does, so that a test cannot pass on a response
     * the client would not act on. A last message with no blank line after it is not an event,
     * mode and namespace tokens are matched exactly as written, and a signal patch needs its
     * signals.
     *
     * @throws DatastarEventValidationException if a Datastar frame names a mode or namespace that
     *   does not exist, has no `signals` where it needs them, or has a data line with no space
     *   after its key; or if any message, Datastar's or not, finished or not, carries a `retry`
     *   that is not a whole number of milliseconds or an `id` the client could not send back as
     *   `last-event-id`: the client takes both from every message. A malformed frame is a bug in
     *   whatever wrote it, and reporting it is more use than dropping it.
     */
    public fun decode(text: String): List<DatastarEvent> = read(text, check = true).mapNotNull(::event)

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
    public fun messages(text: String): List<SseMessage> = read(text, check = false)

    // One parser for a whole text and for a stream still arriving: the reader's.
    private fun read(
        text: String,
        check: Boolean,
    ): List<SseMessage> {
        val reader = SseReader()
        val messages = reader.feed(text)
        return reader.finish(check)?.let { messages + it } ?: messages
    }

    /**
     * The Datastar event one message carries, or `null` when it is not one of Datastar's: a
     * comment, an event name the protocol does not define. For messages that arrive one at a
     * time, from an [SseReader].
     *
     * @throws DatastarEventValidationException as [decode] does: for a Datastar frame the client
     *   would reject, and for any message whose `id` or `retry` is one [decode] rejects.
     */
    public fun event(message: SseMessage): DatastarEvent? {
        checkTaken(message.id, message.retry)
        return toEvent(message)
    }

    /**
     * The `id` and `retry` the client takes from any message, Datastar's or not, judged as
     * [decode] judges them. An `id` goes back as the `last-event-id` header on a reconnect, and
     * `fetch` cannot send one with a control character or one above U+00FF in it, so every
     * reconnect would fail. A `retry` must be a whole number of milliseconds, as the SSE grammar
     * says: the client ignores text it cannot read as a number and reads some it should not, an
     * empty one as zero among them, which is a bug in whatever wrote it either way.
     */
    internal fun checkTaken(
        id: String?,
        retry: String?,
    ) {
        id?.firstOrNull { (it < ' ' && it != '\t') || it == '\u007F' || it > '\u00FF' }?.let {
            throw DatastarEventValidationException(
                "An id with U+%04X in it cannot go back as last-event-id, so every reconnect would fail".format(it.code),
            )
        }
        retry?.let(::parseRetry)
    }

    private fun toEvent(message: SseMessage): DatastarEvent? {
        // Nothing of a message is judged until its event name says it is Datastar's.
        if (message.event != DatastarProtocol.Events.PATCH_ELEMENTS && message.event != DatastarProtocol.Events.PATCH_SIGNALS) return null
        val args = group(message.data)
        val retry = message.retry?.let(::parseRetry)
        // An empty id is valid SSE and means "no id"; the client drops its last-event-id on an
        // empty `id:` line.
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
            // The client would read the key as the line minus its last character (fetch.ts:115-117).
            if (space < 0 && line.isNotEmpty()) {
                throw DatastarEventValidationException("A data line needs a space after its key: '$line'")
            }
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
