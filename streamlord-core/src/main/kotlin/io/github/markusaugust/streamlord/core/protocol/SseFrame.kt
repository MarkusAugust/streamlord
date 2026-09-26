package io.github.markusaugust.streamlord.core.protocol

import kotlin.time.Duration

/**
 * A single Server-Sent Event, decomposed into its fields but not yet rendered.
 *
 * Adapters that must feed a framework's own SSE type (Spring's `ServerSentEvent`, for
 * instance) use the frame; adapters that own the wire call [render].
 */
public data class SseFrame(
    val event: String,
    val id: String? = null,
    val retry: Duration? = null,
    val dataLines: List<String> = emptyList(),
) {
    /** All data lines joined with `\n`, the form most SSE libraries accept as one `data` value. */
    public val data: String get() = dataLines.joinToString("\n")

    /**
     * Render the frame in the exact order the SDK specification demands:
     * `event`, `id` (if any), `retry` (if not the default), one `data` line per entry, blank line.
     */
    public fun render(): String = buildString(64 + dataLines.sumOf { it.length + 8 }) {
        append("event: ").append(event).append('\n')
        id?.let { append("id: ").append(it).append('\n') }
        retry?.takeIf { it != DatastarProtocol.DEFAULT_RETRY }?.let {
            append("retry: ").append(it.inWholeMilliseconds).append('\n')
        }
        for (line in dataLines) append("data: ").append(line).append('\n')
        append('\n')
    }
}
