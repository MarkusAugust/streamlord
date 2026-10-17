package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.json.JsonShape
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

    // The end tags that close the element early, and the three Datastar's client takes for a whole
    // document, which drops the script or appends a second html element.
    private val END_TAGS = Regex("</(script|body|head|html)", RegexOption.IGNORE_CASE)

    // After <!--, a <script that a name boundary follows sends the tokenizer into a state where
    // </script> no longer ends the element.
    private val OPEN_SCRIPT = Regex("<(script)(?=[\\t\\n\\u000C\\r />])", RegexOption.IGNORE_CASE)

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
     * Reject signals that are not one JSON object. Datastar's client reads signals with
     * `JSON.parse` and, when that fails, runs the text as JavaScript, so text that is not JSON is
     * code in the browser; an array or a string would arrive as an object keyed `0`, `1` and on.
     * A key the client cannot hold, at any depth, is rejected too; see [SignalNames].
     */
    internal fun signals(value: String): String {
        var refused: String? = null
        val shaped =
            JsonShape.isObject(value) { key, objectUnder ->
                if (refused == null) refused = SignalNames.keyProblem(key, objectUnder)?.let { "signals hold the key '$key': $it" }
            }
        if (!shaped) {
            throw DatastarEventValidationException(
                "signals must be one JSON object: Datastar's client runs signals that are not JSON as JavaScript",
            )
        }
        refused?.let { throw DatastarEventValidationException(it) }
        return value
    }

    /**
     * Reject an event id that would not come back as it was written. It returns as the
     * `last-event-id` header on a reconnect, and printable ASCII is what reliably survives the trip:
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
     * Make a script body safe to embed in a `<script>` element and to patch as one.
     *
     * `</script` ends the element early, and `</body>`, `</head>` and `</html>` make Datastar's
     * client read the patch as a whole document; each becomes `<\/…`. After a `<!--`, a
     * `<script` keeps the element open past its `</script>`, so it becomes `<\x73cript`. Inside a
     * JavaScript string, regex or template literal both mean what they meant, and outside of one
     * neither was valid JavaScript anyway.
     */
    public fun escapeScriptBody(script: String): String {
        val ended = script.replace(END_TAGS) { "<\\/${it.groupValues[1].lowercase()}" }
        val comment = ended.indexOf("<!--")
        if (comment < 0) return ended
        return ended.substring(0, comment) + ended.substring(comment).replace(OPEN_SCRIPT) { "<\\x73${it.groupValues[1].drop(1)}" }
    }
}
