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
)

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
