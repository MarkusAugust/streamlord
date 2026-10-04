package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonValue

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
    val isEmpty: Boolean get() = data.isEmpty() && event == "message" && id == null && comments.isEmpty()

    /** A keep-alive: comments only. */
    val isComment: Boolean get() = comments.isNotEmpty() && data.isEmpty() && event == "message"
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

/** Feeds chunks of an SSE stream, hands back every complete message. */
public class SseParser {
    private val buffer = StringBuilder()
    private var event = "message"
    private var id: String? = null
    private var retry: Int? = null
    private val data = ArrayList<String>()
    private val comments = ArrayList<String>()

    // A chunk may end between the CR and the LF of one line ending. The CR ends its line at once,
    // so nothing waits on a stream that closes there, and the LF that opens the next chunk is dropped.
    private var afterCr = false

    public fun feed(chunk: String): List<SseMessage> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk, if (afterCr && chunk[0] == '\n') 1 else 0, chunk.length)
        afterCr = false
        val out = ArrayList<SseMessage>()
        while (true) {
            val idx = buffer.indexOfFirst { it == '\n' || it == '\r' }
            if (idx < 0) break
            val line = buffer.substring(0, idx)
            val crLast = buffer[idx] == '\r' && idx + 1 == buffer.length
            val sepLen = if (buffer[idx] == '\r' && !crLast && buffer[idx + 1] == '\n') 2 else 1
            if (crLast) afterCr = true
            buffer.delete(0, idx + sepLen)
            if (line.isEmpty()) {
                val msg = SseMessage(event, id, retry, data.toList(), comments.toList())
                if (!msg.isEmpty) out += msg
                event = "message"
                id = null
                retry = null
                data.clear()
                comments.clear()
                continue
            }
            if (line.startsWith(":")) {
                comments += line.substring(1).removePrefix(" ")
                continue
            }
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> data += value
                "id" -> id = value
                "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) retry = value.toIntOrNull()
            }
        }
        return out
    }
}

public fun decodeDatastar(
    msg: SseMessage,
    receivedAt: Long = System.currentTimeMillis(),
): DatastarFrame {
    val groups = LinkedHashMap<String, MutableList<String>>()
    for (line in msg.data) {
        val sp = line.indexOf(' ')
        val key = if (sp < 0) line else line.substring(0, sp)
        val value = if (sp < 0) "" else line.substring(sp + 1)
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
