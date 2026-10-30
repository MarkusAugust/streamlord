package io.github.markusaugust.streamlord.analysis

/** How serious an [Issue] is; editors map these to their own diagnostic levels. */
public enum class Severity { ERROR, WARNING, INFO, HINT }

/** A quick fix: replace `[start, end)` with [text]. Offsets are in the same space as the issue's. */
public data class Fix(
    val title: String,
    val start: Int,
    val end: Int,
    val text: String,
    /** Further replacements the fix makes elsewhere in the file, such as an import; never overlapping the main one. */
    val edits: List<Edit> = emptyList(),
) {
    /** Every replacement the fix makes, the last in the file first, so that making them in turn moves no offset. */
    public val replacements: List<Edit> get() = (listOf(Edit(start, end, text)) + edits).sortedByDescending { it.start }

    /** The fix applied to [source]: its replacement and every further edit. */
    public fun apply(source: String): String =
        replacements.fold(source) { out, e -> out.substring(0, e.start) + e.text + out.substring(e.end) }
}

/** One replacement of a [Fix]: `[start, end)` becomes [text]. */
public data class Edit(
    val start: Int,
    val end: Int,
    val text: String,
)

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
     * `src/main/kotlin/Page.kt:12:5: error: Kotlin interpolates $count ... [kotlin-interpolation]`.
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
 * UTF-16 characters, as the offsets of an [Issue] do. Only `\n` breaks a line: `\r\n` counts as one
 * break, and a lone `\r` as none.
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
    public const val CASING: String = "https://data-star.dev/reference/attributes#attribute-casing"

    /** The anchor of one attribute in the reference. */
    public fun attribute(name: String): String = "$ATTRIBUTES#" + if (name.startsWith("data-")) name else "data-$name"
}
