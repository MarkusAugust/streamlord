package io.github.markusaugust.streamlord.analysis

/** How serious an [Issue] is; editors map these to their own diagnostic levels. */
public enum class Severity { ERROR, WARNING, INFO, HINT }

/** A quick fix: replace `[start, end)` with [text]. Offsets are in the same space as the issue's. */
public data class Fix(
    val title: String,
    val start: Int,
    val end: Int,
    val text: String,
) {
    /** The fix applied to [source]. */
    public fun apply(source: String): String = source.substring(0, start) + text + source.substring(end)
}

/** One finding of the analysis: a range in the source, a message, and what to do about it. */
public data class Issue(
    val start: Int,
    val end: Int,
    val message: String,
    val severity: Severity,
    val code: String? = null,
    /** Documentation the diagnostic links to. */
    val link: String? = null,
    /** Quick fixes, the preferred one first. */
    val fixes: List<Fix> = emptyList(),
) {
    /** Where [start] falls in [source], the text the issue was found in. */
    public fun position(source: String): SourcePosition = sourcePosition(source, start)

    /**
     * The issue as one line in the form compilers write and IDEs and CI make clickable:
     * `src/main/kotlin/Page.kt:12:5: warning: Kotlin interpolates $count ... [kotlin-interpolation]`.
     * [file] is written as given; [source] is the text the issue was found in.
     */
    public fun format(
        file: String,
        source: String,
    ): String {
        val (line, column) = position(source)
        val tag = if (code != null) " [$code]" else ""
        return "$file:$line:$column: ${severity.name.lowercase()}: $message$tag"
    }
}

/**
 * A place in a text as editors count it: [line] and [column] both start at 1, and a column counts
 * UTF-16 characters, as the offsets of an [Issue] do. A `\r\n` line ending counts as one line break.
 */
public data class SourcePosition(
    val line: Int,
    val column: Int,
)

/** Where [offset] falls in [source]. An offset outside the text is held to its ends. */
public fun sourcePosition(
    source: String,
    offset: Int,
): SourcePosition {
    val at = offset.coerceIn(0, source.length)
    var line = 1
    var lineStart = 0
    for (i in 0 until at) {
        if (source[i] == '\n') {
            line++
            lineStart = i + 1
        }
    }
    return SourcePosition(line, at - lineStart + 1)
}

/** Where the diagnostics point in the Datastar reference. */
public object Docs {
    public const val ATTRIBUTES: String = "https://data-star.dev/reference/attributes"
    public const val ACTIONS: String = "https://data-star.dev/reference/actions"
    public const val SSE: String = "https://data-star.dev/reference/sse_events"
    public const val EXPRESSIONS: String = "https://data-star.dev/guide/datastar_expressions"
    public const val SIGNALS: String = "https://data-star.dev/guide/reactive_signals"

    /** The anchor of one attribute in the reference. */
    public fun attribute(name: String): String = "$ATTRIBUTES#" + if (name.startsWith("data-")) name else "data-$name"
}
