package io.github.markusaugust.streamlord.analysis

/**
 * A Kotlin string that is Datastar's, with what an editor colours and spells in it. Every offset is one of the
 * source, so a Kotlin escape or template in the string is already counted.
 */
public sealed interface DatastarString {
    public val string: KotlinString

    /**
     * An expression: handed to a DSL call, set as an option of a backend action, or assigned to
     * `attributes["data-..."]`. Its [prose] is the text of its string literals, which is all a spell checker reads of it.
     */
    public class Expression internal constructor(
        override val string: KotlinString,
        tokens: () -> List<Token>,
        prose: () -> List<IntRange>,
    ) : DatastarString {
        public val tokens: List<Token> by lazy(tokens)
        public val prose: List<IntRange> by lazy(prose)
    }

    /** A signal's name: the first argument of `dataBind` and its like, or the value of `attributes["data-bind"]`. */
    public class SignalName internal constructor(
        override val string: KotlinString,
        tokens: () -> List<Token>,
    ) : DatastarString {
        public val tokens: List<Token> by lazy(tokens)
    }

    /** The key of `attributes["data-..."]`, one of the catalog's attributes. */
    public class AttributeName internal constructor(
        override val string: KotlinString,
        public val parts: List<NameToken>,
    ) : DatastarString

    /**
     * Markup: the [parts] of each Datastar attribute's name in it and the [tokens] of their values. It is HTML [byLook]
     * when it reads as HTML wherever it stands, and otherwise only because the call it is handed to takes markup.
     */
    public class Html internal constructor(
        override val string: KotlinString,
        public val byLook: Boolean,
        painted: () -> Pair<List<NameToken>, List<Token>>,
    ) : DatastarString {
        private val painted by lazy(painted)
        public val parts: List<NameToken> get() = painted.first
        public val tokens: List<Token> get() = painted.second
    }
}

/**
 * Every string of the Kotlin file [source] that is Datastar's, in the order of the file: what colouring, spelling and
 * injection ask of a string. kotlinx.html's `attributes["data-..."]` is Datastar whatever the file can call; a DSL
 * call's string is where the file can call the DSL and [Source.foreign] does not say the call is somebody else's.
 */
public fun Analyzer.datastarStrings(source: Source): List<DatastarString> {
    val src = source.text
    val lex = lex(src)
    val indexes = attributesIndexes(src)
    val values = indexes.mapNotNull { at -> at.value?.let { it.start to at } }.toMap()
    val keys = indexes.associateBy { it.nameStart }
    val modules = settle(source)
    val dsl = modules.dsl
    val html = htmlStrings(src, lex)
    val names = if (dsl) signalNameStrings(source).mapTo(HashSet()) { it.string.start } else emptySet()
    val options = if (dsl) expressionOptions(src, lex).mapTo(HashSet()) { it.value.start } else emptySet()
    // A string is the expression of the call it is an argument of, so of the innermost: `dataOnClick(getExpr("..."))` is getExpr's.
    val handed = HashMap<Int, CallSite>()
    if (dsl) {
        for (site in findCallSites(src, catalog.expressionCallSites.keys, lex)) {
            for (s in site.expressionStrings(catalog.expressionCallSites.getValue(site.name))) handed[s.start] = site
        }
    }
    val out = ArrayList<DatastarString>()
    for (s in lex.strings) {
        val value = values[s.start]
        val key = keys[s.contentStart]
        out +=
            when {
                value != null -> {
                    val kind = datastarName(value.name, source.prefix)?.spec?.valueKind
                    when {
                        !value.whole -> null
                        kind == ValueKind.EXPRESSION -> expression(src, s)
                        kind == ValueKind.SIGNAL -> DatastarString.SignalName(s) { signalNameTokens(s, 0, s.text) }
                        else -> null
                    }
                }

                key != null -> {
                    datastarName(key.name, source.prefix)?.let { name ->
                        DatastarString.AttributeName(
                            s,
                            name.parts.map { NameToken(key.nameStart + it.start, key.nameStart + it.end, it.part) },
                        )
                    }
                }

                // Markup that only a call makes HTML is that where the file has core: elsewhere the call is somebody else's.
                html(s) && (modules.core || isHtmlString(src, s)) -> {
                    DatastarString.Html(s, isHtmlString(src, s)) { paintedHtml(src, s, source.prefix) }
                }

                s.start in names -> {
                    DatastarString.SignalName(s) { signalNameTokens(s, 0, s.text) }
                }

                s.start in options -> {
                    expression(src, s)
                }

                else -> {
                    handed[s.start]?.takeUnless { source.foreign(it.name, it.nameStart) }?.let { expression(src, s) }
                }
            } ?: continue
    }
    return out
}

/** A Datastar attribute's name: its [spec] in the catalog and the [parts] to colour, as offsets into the name. */
public class DatastarName internal constructor(
    public val spec: AttributeSpec,
    public val parts: List<NameToken>,
)

/**
 * [name] as one of the catalog's attributes, under [prefix] or under either bundle's, `data-` and `data-star-`: the VS Code
 * grammar knows both, and so do colours and spelling. Null for the page's own `data-color`, or a typo.
 */
public fun Analyzer.datastarName(
    name: String,
    prefix: String,
): DatastarName? {
    for (p in listOf(prefix, "data-", "data-star-").distinct()) {
        val spec = catalog.parseAttributeName(name.lowercase(), p)?.spec ?: continue
        val parts = tokenizeAttributeName(name, p) ?: continue
        return DatastarName(spec, parts)
    }
    return null
}

private fun expression(
    src: String,
    s: KotlinString,
) = DatastarString.Expression(s, { expressionTokens(src, s, 0, s.text.length) }, { prose(src, s) })

/** The tokens of the expression in the decoded range [from, to) of [s]. */
private fun expressionTokens(
    src: String,
    s: KotlinString,
    from: Int,
    to: Int,
): List<Token> {
    // The source text of the range is read, not the decoded text, so `${'$'}count` is seen as written.
    val range = s.toSource(from, to)
    val start = range.first
    val end = if (to >= s.text.length) s.contentEnd else range.last + 1
    if (end <= start || src.substring(start, end).isBlank()) return emptyList()
    // A Kotlin template is Kotlin's: its own highlighter colours it, and Datastar never sees it as written.
    val templates = s.interpolations.filter { it.start < end && start < it.end }.map { (it.start - start) until (it.end - start) }
    return tokenizeExpression(src.substring(start, end), templates).map { Token(start + it.start, start + it.end, it.kind) }
}

/** The tokens of the signal's name [name], which starts at [from] of the decoded text of [s]; none around a Kotlin template. */
private fun signalNameTokens(
    s: KotlinString,
    from: Int,
    name: String,
): List<Token> {
    if (PLACEHOLDER in name) return emptyList()
    return tokenizeSignalName(name).map {
        val r = s.toSource(from + it.start, from + it.end)
        Token(r.first, r.last + 1, it.kind)
    }
}

private fun Analyzer.paintedHtml(
    src: String,
    s: KotlinString,
    prefix: String,
): Pair<List<NameToken>, List<Token>> {
    val names = ArrayList<NameToken>()
    val tokens = ArrayList<Token>()
    for (tag in tokenize(s.text, templates = false).tags) {
        for (attr in tag.attributes) {
            val name = datastarName(attr.name, prefix) ?: continue
            for (p in name.parts) {
                val r = s.toSource(attr.nameStart + p.start, attr.nameStart + p.end)
                names += NameToken(r.first, r.last + 1, p.part)
            }
            val value = attr.value?.takeIf { it.isNotBlank() } ?: continue
            when (name.spec.valueKind) {
                ValueKind.EXPRESSION, ValueKind.FILTER -> {
                    tokens +=
                        expressionTokens(src, s, attr.valueStart, attr.valueStart + value.length)
                }

                ValueKind.SIGNAL -> {
                    tokens += signalNameTokens(s, attr.valueStart, value)
                }

                else -> {}
            }
        }
    }
    return names to tokens
}

/** The prose of the expression [s]: its strings as [proseRanges] finds them in the source, each cut at a Kotlin escape and around a Kotlin template. */
private fun prose(
    src: String,
    s: KotlinString,
): List<IntRange> {
    val out = ArrayList<IntRange>()
    for (range in proseRanges(src.substring(s.contentStart, s.contentEnd))) {
        // `\n` ends a word, as a new line does.
        var start = s.contentStart + range.first
        val end = s.contentStart + range.last + 1
        var i = start
        val pieces = ArrayList<IntRange>()
        while (i < end) {
            if (src[i] != '\\') {
                i++
                continue
            }
            if (i > start) pieces += start until i
            i += if (src.getOrNull(i + 1) == 'u') 6 else 2
            start = minOf(i, end)
        }
        if (end > start) pieces += start until end
        // A Kotlin template inside the string is Kotlin's code, spelled where it is declared.
        for (piece in pieces) {
            var from = piece.first
            for (t in s.interpolations) {
                if (t.end <= from || t.start > piece.last) continue
                if (t.start > from) out += from until t.start
                from = t.end
            }
            if (from <= piece.last) out += from..piece.last
        }
    }
    return out
}
