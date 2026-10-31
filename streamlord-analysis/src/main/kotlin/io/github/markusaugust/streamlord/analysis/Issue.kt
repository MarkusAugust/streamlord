package io.github.markusaugust.streamlord.analysis

/** How serious an [Issue] is; editors map these to their own diagnostic levels. */
public enum class Severity { ERROR, WARNING, INFO, HINT }

/**
 * A quick fix: replace `[start, end)` with [text]. Offsets are in the same space as the issue's.
 *
 * The text of a fix that rewrites a whole literal is made only when it is asked for, so a literal
 * with thousands of holes does not build a rewrite for each.
 */
public class Fix private constructor(
    public val title: String,
    public val start: Int,
    public val end: Int,
    private val made: Lazy<String>,
    /** Further replacements the fix makes elsewhere in the file, such as an import; never overlapping the main one. */
    public val edits: List<Edit>,
    /**
     * Whether an editor may offer the fix as the one to apply, when it comes first: false where the
     * choice between the fixes is the author's, so none of them is preferred.
     */
    public val preferred: Boolean,
) {
    public constructor(
        title: String,
        start: Int,
        end: Int,
        text: String,
        edits: List<Edit> = emptyList(),
        preferred: Boolean = true,
    ) : this(title, start, end, lazyOf(text), edits, preferred)

    /** What `[start, end)` becomes. */
    public val text: String get() = made.value

    /** Every replacement the fix makes, the last in the file first, so that making them in turn moves no offset. */
    public val replacements: List<Edit> get() = (listOf(Edit(start, end, text)) + edits).sortedByDescending { it.start }

    /** The fix applied to [source]: its replacement and every further edit. */
    public fun apply(source: String): String =
        replacements.fold(source) { out, e -> out.substring(0, e.start) + e.text + out.substring(e.end) }

    /** The fix with some of its parts changed; the text is made only if it is changed or asked for. */
    public fun copy(
        title: String = this.title,
        start: Int = this.start,
        end: Int = this.end,
        text: String? = null,
        edits: List<Edit> = this.edits,
        preferred: Boolean = this.preferred,
    ): Fix = Fix(title, start, end, if (text != null) lazyOf(text) else made, edits, preferred)

    override fun equals(other: Any?): Boolean =
        other is Fix && title == other.title && start == other.start && end == other.end && text == other.text &&
            edits == other.edits && preferred == other.preferred

    override fun hashCode(): Int = listOf(title, start, end, text, edits, preferred).hashCode()

    override fun toString(): String = "Fix(title=$title, start=$start, end=$end, text=$text, edits=$edits, preferred=$preferred)"

    internal companion object {
        /** A fix whose text is made the first time it is read. */
        fun later(
            title: String,
            start: Int,
            end: Int,
            edits: List<Edit> = emptyList(),
            preferred: Boolean = true,
            text: () -> String,
        ): Fix = Fix(title, start, end, lazy(LazyThreadSafetyMode.PUBLICATION, text), edits, preferred)
    }
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
    ): String = format(file, position(source))

    internal fun format(
        file: String,
        at: SourcePosition,
    ): String {
        val tag = if (code != null) " [$code]" else ""
        return "$file:${at.line}:${at.column}: ${severity.name.lowercase()}: $message$tag"
    }
}

/** An [Issue] of a project's sources, as [Analyzer.analyzeProject] finds them: the [file], and the [line] and [column] where it starts. */
public data class ProjectIssue(
    val file: java.io.File,
    val line: Int,
    val column: Int,
    val issue: Issue,
) {
    /** Whether the issue should fail a build: an error, or an `unsafe-interpolation` warning, which escaping cannot save. */
    public val fails: Boolean
        get() = issue.severity == Severity.ERROR || (issue.code == "unsafe-interpolation" && issue.severity == Severity.WARNING)

    /** The issue as one line in the form compilers write, as [Issue.format] has it: `src/main/kotlin/Page.kt:12:5: error: ... [code]`. */
    public fun format(): String = issue.format(file.path, SourcePosition(line, column))
}

/** The issues that should fail a build: each error, and each `unsafe-interpolation` warning. A hint never does. */
public fun List<ProjectIssue>.failures(): List<ProjectIssue> = filter { it.fails }

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
