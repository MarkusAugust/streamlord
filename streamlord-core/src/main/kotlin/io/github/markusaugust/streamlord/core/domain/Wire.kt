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
     * Reject an event id that would not come back as it was written. It returns as the
     * `last-event-id` header on a reconnect, and only printable ASCII survives that trip:
     * `fetch` cannot send a character above U+00FF, Node's `fetch` refuses a control character,
     * Tomcat and Netty answer one with 400, which ends the client's retries, and Ktor's CIO reads
     * the header as UTF-8, so a Latin-1 character arrives as U+FFFD. A space at either end is
     * stripped on the way, so the server would get another id.
     */
    internal fun eventId(value: String): String {
        if (value.isEmpty()) throw DatastarEventValidationException("eventId must not be empty")
        value.firstOrNull { it !in ' '..'~' }?.let {
            throw DatastarEventValidationException(
                "eventId must be printable ASCII, not U+%04X: it goes back as the last-event-id header".format(it.code),
            )
        }
        if (value.first() == ' ' || value.last() == ' ') {
            throw DatastarEventValidationException("eventId must not start or end with a space: the client strips it")
        }
        return value
    }

    /**
     * Reject a retry the client cannot use as written: a whole number of milliseconds from 1 to
     * [Int.MAX_VALUE]. The line carries whole milliseconds, so a fraction would be cut off, and
     * one under a millisecond would read `retry: 0`, a reconnect with no wait whose backoff never
     * grows from zero. Over [Int.MAX_VALUE] the browser's timer overflows.
     */
    internal fun retry(value: Duration): Duration {
        if (value.isInfinite() ||
            value < 1.milliseconds ||
            value > Int.MAX_VALUE.milliseconds ||
            value.inWholeNanoseconds % 1_000_000 != 0L
        ) {
            throw DatastarEventValidationException("retry must be a whole number of milliseconds from 1 to ${Int.MAX_VALUE}, not $value")
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
