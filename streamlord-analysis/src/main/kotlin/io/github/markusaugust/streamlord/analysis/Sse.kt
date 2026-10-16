package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonValue
import io.github.markusaugust.streamlord.core.protocol.SseReader
import io.github.markusaugust.streamlord.core.protocol.SseMessage as CoreSseMessage

/*
 * A Server-Sent Events parser and a Datastar event decoder, for the Stream Inspector.
 */

public data class SseMessage(
    val event: String = "message",
    val id: String? = null,
    val retry: Int? = null,
    val data: List<String> = emptyList(),
    val comments: List<String> = emptyList(),
) {
    /** A message that carries nothing at all is not dispatched. */
    val isEmpty: Boolean get() = data.isEmpty() && event == "message" && id == null && retry == null && comments.isEmpty()

    /** A keep-alive: comments only. */
    val isComment: Boolean get() = comments.isNotEmpty() && data.isEmpty() && event == "message" && id == null && retry == null
}

public data class DatastarFrame(
    val event: String,
    val id: String?,
    val retry: Int?,
    /** Data lines grouped by their first word, joined with newlines, as the client does it. */
    val args: Map<String, String>,
    val raw: String,
    val receivedAt: Long,
)

/**
 * Feeds chunks of an SSE stream, hands back every complete message.
 *
 * It reads as Datastar's client does, through the core's [SseReader]: a line without a colon is
 * skipped, a byte order mark before a field or a value is dropped, and an `id` is kept as
 * written. A message carrying only a `retry` is handed back, since the client acts on it. The
 * inspector shows what arrived and judges nothing, so a `retry` that is not a whole number of
 * milliseconds that fits an [Int] is shown as none.
 */
public class SseParser {
    private val reader = SseReader()

    public fun feed(chunk: String): List<SseMessage> = reader.feed(chunk).map(::shown)

    /**
     * The stream has ended: the comments read since the last message, if any, as one message.
     * An unfinished message is dropped, as the client never dispatches one.
     */
    public fun finish(): SseMessage? =
        try {
            reader.finish()?.let(::shown)
        } catch (_: DatastarEventValidationException) {
            // The reader judges an unfinished message's id and retry; the inspector does not.
            null
        }

    private fun shown(message: CoreSseMessage): SseMessage =
        SseMessage(
            event = message.event ?: "message",
            id = message.id,
            retry = message.retry?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull(),
            data = message.data,
            comments = message.comments,
        )
}

public fun decodeDatastar(
    msg: SseMessage,
    receivedAt: Long = System.currentTimeMillis(),
): DatastarFrame {
    val groups = LinkedHashMap<String, MutableList<String>>()
    for (line in msg.data) {
        // As the client splits it, slice(0, i) and slice(i + 1): without a space, the key is the
        // line minus its last character and the value is the whole line (fetch.ts:115-117).
        val sp = line.indexOf(' ')
        val key = if (sp < 0) line.dropLast(1) else line.substring(0, sp)
        val value = if (sp < 0) line else line.substring(sp + 1)
        groups.getOrPut(key) { ArrayList() } += value
    }
    val args = groups.mapValues { it.value.joinToString("\n") }
    val raw =
        buildList {
            add("event: ${msg.event}")
            msg.id?.let { add("id: $it") }
            msg.retry?.let { add("retry: $it") }
            msg.data.forEach { add("data: $it") }
        }.joinToString("\n")
    return DatastarFrame(msg.event, msg.id, msg.retry, args, raw, receivedAt)
}
