package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Guards at the gate of the wire format.
 *
 * Server-Sent Events are line-oriented. A value that smuggles a line break into a single-line
 * field (`selector`, `id`, a header) could forge extra data lines and make the client patch
 * elements nobody asked for. Everything that becomes a single wire line passes through here.
 */
public object Wire {
    private val LINE_BREAKS = Regex("\r\n|\r|\n")
    private val ATTRIBUTE_NAME = Regex("[A-Za-z_:][A-Za-z0-9_:.-]*")
    private val END_SCRIPT = Regex("</script", RegexOption.IGNORE_CASE)

    /** Split text into lines on `\r\n`, `\r` or `\n`, exactly as the SSE grammar does. */
    public fun lines(text: String): List<String> = text.split(LINE_BREAKS)

    /** Reject values that cannot live on a single SSE line or HTTP header. */
    public fun singleLine(
        value: String,
        field: String,
    ): String {
        for (ch in value) {
            if (ch == '\n' || ch == '\r' || ch == '\u0000') {
                throw DatastarEventValidationException("$field must not contain line breaks or NUL characters")
            }
        }
        return value
    }

    /**
     * Reject an event id the client could not send back as it is. It returns as the
     * `last-event-id` header on a reconnect: `fetch` cannot send a character above U+00FF, so
     * every reconnect would fail, and it strips spaces and tabs at either end, so the server
     * would be handed another id than the one it wrote.
     */
    internal fun eventId(value: String): String {
        if (value.isEmpty()) throw DatastarEventValidationException("eventId must not be empty")
        singleLine(value, "eventId")
        value.firstOrNull { it > '\u00FF' }?.let {
            throw DatastarEventValidationException("eventId must not contain '$it': the client cannot send it back as last-event-id")
        }
        if (value.first().isEdgeSpace() || value.last().isEdgeSpace()) {
            throw DatastarEventValidationException("eventId must not start or end with a space or tab: the client strips them")
        }
        return value
    }

    private fun Char.isEdgeSpace() = this == ' ' || this == '\t'

    /**
     * Reject a retry the client cannot use: under one millisecond it is written as `retry: 0`,
     * which has the client reconnect at once and again without end, and over [Int.MAX_VALUE]
     * milliseconds the browser's timer overflows.
     */
    internal fun retry(value: Duration): Duration {
        if (value.isInfinite() || value < 1.milliseconds || value > Int.MAX_VALUE.milliseconds) {
            throw DatastarEventValidationException("retry must be between 1 ms and ${Int.MAX_VALUE} ms, not $value")
        }
        return value
    }

    /** Reject blank or multi-line CSS selectors. */
    public fun selector(
        value: String,
        field: String = "selector",
    ): String {
        if (value.isBlank()) throw DatastarEventValidationException("$field must not be blank")
        return singleLine(value, field)
    }

    /** Reject anything that is not a syntactically sane HTML attribute name. */
    public fun attributeName(name: String): String {
        if (!ATTRIBUTE_NAME.matches(name)) {
            throw DatastarEventValidationException("'$name' is not a valid HTML attribute name")
        }
        return name
    }

    /** Escape a value for use inside a double-quoted HTML attribute. */
    public fun escapeAttribute(value: String): String =
        buildString(value.length + 8) {
            for (ch in value) {
                when (ch) {
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    else -> append(ch)
                }
            }
        }

    /**
     * Make a script body safe to embed in a `<script>` element. The only sequence that can end
     * the element early is `</script`; inside a JavaScript string or regex `<\/script` means the
     * same thing, and outside of one it was never valid JavaScript anyway.
     */
    public fun escapeScriptBody(script: String): String = script.replace(END_SCRIPT) { "<\\/script" }
}
