package io.github.markusaugust.streamlord.core.protocol

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.domain.Wire
import kotlin.time.Duration

/**
 * A single Server-Sent Event, decomposed into its fields but not yet rendered.
 *
 * Adapters that must feed a framework's own SSE type (Spring's `ServerSentEvent`, for
 * instance) use the frame; adapters that own the wire call [render].
 *
 * A frame built by hand is held to what an event is: no field can carry a line break and so a
 * line of its own, the [id] is one the client can send back, and the [retry] is one it can use.
 */
public data class SseFrame(
    val event: String,
    val id: String? = null,
    val retry: Duration? = null,
    val dataLines: List<String> = emptyList(),
) {
    init {
        noLineBreak(event, "event")
        id?.let(Wire::eventId)
        retry?.let(Wire::retry)
        // NUL is harmless in data, where markup can carry one, and only refused in the id.
        for (line in dataLines) noLineBreak(line, "data")
    }

    /** All data lines joined with `\n`, the form most SSE libraries accept as one `data` value. */
    public val data: String get() = dataLines.joinToString("\n")

    /**
     * Render the frame in the exact order the SDK specification demands:
     * `event`, `id` (if any), `retry` (if not the default), one `data` line per entry, blank line.
     */
    public fun render(): String =
        buildString(64 + dataLines.sumOf { it.length + 8 }) {
            append("event: ").append(event).append('\n')
            id?.let { append("id: ").append(it).append('\n') }
            // Compared as written: 1000.5 ms is the 1000 the client would read, and is left off too.
            retry?.takeIf { it.inWholeMilliseconds != DatastarProtocol.DEFAULT_RETRY.inWholeMilliseconds }?.let {
                append("retry: ").append(it.inWholeMilliseconds).append('\n')
            }
            for (line in dataLines) append("data: ").append(line).append('\n')
            append('\n')
        }
}

private fun noLineBreak(
    value: String,
    field: String,
) {
    if ('\n' in value || '\r' in value) throw DatastarEventValidationException("$field must not contain a line break")
}
