package io.github.markusaugust.streamlord.analysis

/** How serious an [Issue] is; editors map these to their own diagnostic levels. */
public enum class Severity { ERROR, WARNING, INFO, HINT }

/**
 * A quick fix: replace `[start, end)` with [text]. Offsets are in the same space as the issue's.
 *
 * The text of a fix that rewrites a whole literal is made only when it is asked for, so a literal
 * with thousands of placeholders does not build a rewrite for each.
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
    /**
     * Whether an editor that sorts fixes itself keeps this one at the top though it is not [preferred]: the one
     * most authors want, where the choice is still theirs. Read only on the first fix of an issue.
     */
    public val leads: Boolean,
) {
    public constructor(
        title: String,
        start: Int,
        end: Int,
        text: String,
        edits: List<Edit> = emptyList(),
        preferred: Boolean = true,
        leads: Boolean = false,
    ) : this(title, start, end, lazyOf(text), edits, preferred, leads)

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
        leads: Boolean = this.leads,
    ): Fix = Fix(title, start, end, if (text != null) lazyOf(text) else made, edits, preferred, leads)

    override fun equals(other: Any?): Boolean =
        other is Fix && title == other.title && start == other.start && end == other.end && text == other.text &&
            edits == other.edits && preferred == other.preferred && leads == other.leads

    override fun hashCode(): Int = listOf(title, start, end, text, edits, preferred, leads).hashCode()

    override fun toString(): String =
        "Fix(title=$title, start=$start, end=$end, text=$text, edits=$edits, preferred=$preferred, leads=$leads)"

    /** Whether this fix and [other] write the same text, made once for both: the rewrite of a literal, of which each value offers a [copy]. */
    internal fun sharesTextWith(other: Fix): Boolean = made === other.made

    internal companion object {
        /** A fix whose text is made the first time it is read. */
        fun later(
            title: String,
            start: Int,
            end: Int,
            edits: List<Edit> = emptyList(),
            preferred: Boolean = true,
            leads: Boolean = false,
            text: () -> String,
        ): Fix = Fix(title, start, end, lazy(LazyThreadSafetyMode.PUBLICATION, text), edits, preferred, leads)
    }
}

/** One replacement of a [Fix]: `[start, end)` becomes [text]. */
public data class Edit(
    val start: Int,
    val end: Int,
    val text: String,
)

/**
 * What an [Issue] is about, one for each kind of finding the analysis gives. [id] is the name
 * [Issue.format] writes in brackets and the editors show beside the message.
 */
public enum class IssueCode(
    public val id: String,
) {
    KOTLIN_INTERPOLATION("kotlin-interpolation"),
    UNESCAPED_INTERPOLATION("unescaped-interpolation"),
    UNSAFE_INTERPOLATION("unsafe-interpolation"),
    INTERPOLATE_TEMPLATE("interpolate-template"),
    INTERPOLATE_PLACEHOLDERS("interpolate-placeholders"),
    EXPRESSION_SYNTAX("expression-syntax"),
    EMPTY_EXPRESSION("empty-expression"),
    ONE_VALUE("one-value"),
    UNKNOWN_ACTION("unknown-action"),
    ACTION_SPACE("action-space"),
    PRO_ACTION("pro-action"),
    SIGNAL_KEBAB("signal-kebab"),
    LITERAL_REWRITTEN("literal-rewritten"),
    SIGNAL_NOT_READ("signal-not-read"),
    STATEMENT_SPLIT("statement-split"),
    SCOPE_NAME("scope-name"),
    UNKNOWN_OPTION("unknown-option"),
    OPTION_VALUE("option-value"),
    ACTION_ARITY("action-arity"),
    ACTION_ARGUMENT("action-argument"),
    FETCH_EVENT_TYPE("fetch-event-type"),
    UNKNOWN_ATTRIBUTE("unknown-attribute"),
    UNKNOWN_MODIFIER("unknown-modifier"),
    MODIFIER_ARGS("modifier-args"),
    MISSING_KEY("missing-key"),
    UNEXPECTED_KEY("unexpected-key"),
    WRONG_ELEMENT("wrong-element"),
    SIGNAL_NAME_EXPECTED("signal-name-expected"),
    UNEXPECTED_VALUE("unexpected-value"),
    MISSING_VALUE("missing-value"),
    KEY_AND_VALUE("key-and-value"),
    MISSING_KEY_OR_VALUE("missing-key-or-value"),
    PRO_ATTRIBUTE("pro-attribute"),
    PREFIX_MISMATCH("prefix-mismatch"),
    INDICATOR_WITHOUT_ACTION("indicator-without-action"),
    INDICATOR_ORDER("indicator-order"),
    REPEATED_ATTRIBUTE("repeated-attribute"),
    SIGNAL_NAME("signal-name"),
    COMPUTED_VALUE("computed-value"),
    FILTER_PATTERN("filter-pattern"),
    SIGNAL_NULL("signal-null"),
    MISSING_NONCE("missing-nonce"),
    INVALID_NONCE("invalid-nonce"),
    UNKNOWN_SIGNAL("unknown-signal"),
    SIGNAL_NEVER_DECLARED("signal-never-declared"),
    KEY_CASE("key-case"),
    KEY_CASE_WIRE("key-case-wire"),
    SIGNAL_NAME_HYPHEN("signal-name-hyphen"),
    ATTRIBUTE_NAME_CASE("attribute-name-case"),
    KEY_REFUSED("key-refused"),
    MISSING_ID("missing-id"),
    MODE_NEEDS_SELECTOR("mode-needs-selector"),
    UNCLOSED("unclosed"),
    STRAY_CLOSE("stray-close"),
    TOP_LEVEL_TEXT("top-level-text"),
    BLANK_SELECTOR("blank-selector"),
    SELECTOR_NEWLINE("selector-newline"),
    SCRIPT_CLOSE("script-close"),
    EVENT_REFUSED("event-refused"),
    ;

    /** The [id], so a Kotlin template that holds a code writes it as the editors show it. */
    override fun toString(): String = id
}

/** One finding of the analysis: a range in the source, a message, and what to do about it. */
public data class Issue(
    val start: Int,
    val end: Int,
    val message: String,
    val severity: Severity,
    val code: IssueCode,
    /** Documentation the diagnostic links to. */
    val link: String? = null,
    /** Quick fixes, the preferred one first. */
    val fixes: List<Fix> = emptyList(),
) {
    /** Where [start] falls in [source], the text the issue was found in. */
    internal fun position(source: String): SourcePosition = sourcePosition(source, start)

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
    ): String = "$file:${at.line}:${at.column}: ${severity.name.lowercase()}: $message [${code.id}]"
}

/**
 * A place in a text as editors count it: [line] and [column] both start at 1, and a column counts
 * UTF-16 characters, as the offsets of an [Issue] do. Only `\n` breaks a line: `\r\n` counts as one
 * break, and a lone `\r` as none.
 */
internal data class SourcePosition(
    val line: Int,
    val column: Int,
)

/** Where [offset] falls in [source]. An offset outside the text is held to its ends. */
internal fun sourcePosition(
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

/** Where the diagnostics point: in the Datastar reference, and for a Kotlin value in an HTML string in Streamlord's own documentation. */
internal object Docs {
    /** Streamlord's page on HTML strings and `interpolate`. */
    public const val STRINGS: String = "https://streamlord-docs.netlify.app/strings/"
    public const val ATTRIBUTES: String = "https://data-star.dev/reference/attributes"
    public const val ACTIONS: String = "https://data-star.dev/reference/actions"
    public const val SSE: String = "https://data-star.dev/reference/sse_events"
    public const val EXPRESSIONS: String = "https://data-star.dev/guide/datastar_expressions"
    public const val SIGNALS: String = "https://data-star.dev/guide/reactive_signals"
    public const val CASING: String = "https://data-star.dev/reference/attributes#attribute-casing"

    /** The anchor of one attribute in the reference. */
    public fun attribute(name: String): String = "$ATTRIBUTES#" + if (name.startsWith("data-")) name else "data-$name"

    /** Where the reference describes [spec]: its own page where the catalog names one, its anchor among the attributes otherwise. */
    public fun attribute(spec: AttributeSpec): String = spec.reference ?: attribute(spec.name)
}
