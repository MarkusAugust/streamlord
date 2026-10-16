package io.github.markusaugust.streamlord.core.protocol

import io.github.markusaugust.streamlord.core.DatastarEventValidationException

/**
 * Reads a stream that is still arriving: feed it text in whatever pieces the connection hands
 * over, and it gives back each message the moment its blank line has arrived.
 *
 * [SseDecoder.messages] reads a stream that has ended. This reads one that has not, which is what
 * a test needs when it holds a stream open, sends a command and waits for the patch it causes,
 * and what an inspector needs when it shows a stream as it runs. The grammar is the same: a line
 * ends at CRLF, CR or LF, a blank line ends a message, a `:` line is a comment, a line without a
 * colon is skipped, an unknown field is ignored, and an `id` is kept as written, as Datastar's
 * client keeps it. [SseDecoder.event] judges what the client would reject.
 *
 * A line ending can be split between two pieces, a CR at the end of one and its LF at the start
 * of the next. The CR ends its line at once, so nothing waits on a stream that stops there, and
 * the LF is then dropped rather than read as a blank line.
 *
 * The pieces are text. Decode bytes with a decoder that keeps a character split across two reads,
 * such as an `InputStreamReader`, rather than decoding each read on its own.
 *
 * Not thread-safe: one reader belongs to one stream, fed from one place.
 */
public class SseReader {
    private val line = StringBuilder()
    private var afterCr = false

    private var event: String? = null
    private var id: String? = null
    private var retry: String? = null
    private val data = ArrayList<String>()
    private val comments = ArrayList<String>()

    /**
     * The stream has ended: the comments read since the last message, as one message, the way
     * [SseDecoder.messages] keeps them at the end of a text. Fields with no blank line after them
     * are an unfinished message, which the client never dispatches, and are dropped.
     *
     * @throws DatastarEventValidationException when that unfinished message carries an `id` or a
     *   `retry` that [SseDecoder.decode] rejects: the client takes both from each line as it ends,
     *   dispatched or not. The reader is reset first, so it reads on cleanly after the throw.
     */
    public fun finish(): SseMessage? = finish(check = true)

    internal fun finish(check: Boolean): SseMessage? {
        if (line.isNotEmpty()) {
            val text = line.toString()
            line.setLength(0)
            if (text.startsWith(":")) comments += text.removePrefix(":").removePrefix(" ")
        }
        val unfinished = event != null || id != null || retry != null || data.isNotEmpty()
        val trailing = if (!unfinished && comments.isNotEmpty()) SseMessage(null, null, null, emptyList(), comments.toList()) else null
        val takenId = id
        val takenRetry = retry
        event = null
        id = null
        retry = null
        data.clear()
        comments.clear()
        // Only lines that have ended were read; one still arriving was never seen by the client.
        // Judged after the reset, so a reader that throws here is still clean.
        if (check && unfinished) SseDecoder.checkTaken(takenId, takenRetry)
        return trailing
    }

    /** Every message [chunk] completed, in order. Usually none or one. */
    public fun feed(chunk: String): List<SseMessage> {
        val out = ArrayList<SseMessage>()
        for (char in chunk) {
            if (afterCr) {
                afterCr = false
                if (char == '\n') continue
            }
            when (char) {
                '\r' -> {
                    afterCr = true
                    endLine(out)
                }

                '\n' -> {
                    endLine(out)
                }

                else -> {
                    line.append(char)
                }
            }
        }
        return out
    }

    private fun endLine(out: MutableList<SseMessage>) {
        val text = line.toString()
        line.setLength(0)
        if (text.isEmpty()) {
            if (event != null || id != null || retry != null || data.isNotEmpty() || comments.isNotEmpty()) {
                out += SseMessage(event, id, retry, data.toList(), comments.toList())
            }
            event = null
            id = null
            retry = null
            data.clear()
            comments.clear()
            return
        }
        if (text.startsWith(":")) {
            comments += text.removePrefix(":").removePrefix(" ")
            return
        }
        // "field: value". A line without a colon is skipped, as Datastar's client skips it, and a
        // byte order mark is dropped from the start of the field and of the value, as the client's
        // TextDecoder drops it from each (fetch.ts:403-408 in 1.0.4).
        val colon = text.indexOf(':')
        if (colon < 0) return
        val field = text.substring(0, colon).removePrefix("\uFEFF")
        val value = text.substring(colon + 1).removePrefix(" ").removePrefix("\uFEFF")
        when (field) {
            "event" -> event = value
            "id" -> id = value
            "retry" -> retry = value
            "data" -> data += value
            else -> Unit // An unknown field is ignored, as the SSE grammar says to.
        }
    }
}
