package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.regex

/*
 * Syntax highlighting of a Datastar expression and of a `data-*` attribute name. Every token gets
 * the kind the VS Code grammar (editors/vscode/syntaxes/datastar-expression.tmLanguage.json) gives
 * the same text, so both editors colour it as the same kind of thing; each editor maps a kind to a
 * standard key or scope of the user's theme.
 */

public enum class TokenKind {
    /** `$count` and `$user.name`: the `$name` part. */
    SIGNAL,

    /** The `.name` path after a signal. */
    SIGNAL_PATH,

    /** `@get`, `@post`, `@put`, `@patch`, `@delete`, `@query`. */
    ACTION_BACKEND,

    /** Any other `@name(`. */
    ACTION,

    /** `el`, `evt`, `patch`. */
    SCOPE_VARIABLE,

    /** A fetch option or response override key: `headers`, `selector`, `mode`. */
    OPTION_KEY,

    /** `500ms`, `1s`. */
    DURATION,

    /** A string literal, so the parts inside are not read as signals or actions. */
    STRING,

    /** A regular expression literal. */
    REGEX,

    /** A keyword of JavaScript's own, `true`, `null` and the arrow `=>` among them. */
    KEYWORD,

    /** A number of JavaScript's own. */
    NUMBER,

    /** A Kotlin `${'$'}` escape for a dollar, in Kotlin strings. */
    KOTLIN_DOLLAR_ESCAPE,

    /** The key of an object literal: `a` in `{a: 1}`. */
    OBJECT_KEY,

    /** A property after a dot: `length` in `x.length`. */
    PROPERTY,

    /** A function called by name or after a dot: `max` in `Math.max(1)`. */
    METHOD,

    /** One of the browser's globals: `window`, `Math`, `JSON`. */
    GLOBAL,

    /** Any other name. */
    IDENTIFIER,

    /** An operator: `+`, `===`, `&&`, `...`. */
    OPERATOR,

    /** A comment, `/* ... */` or `// ...`. */
    COMMENT,

    /** The `${` and `}` around an expression in a template literal. */
    TEMPLATE_PUNCTUATION,
}

public data class Token(
    val start: Int,
    val end: Int,
    val kind: TokenKind,
)

private val BACKEND_ACTIONS = Catalog.default.backendActions.toSet()

// The options of a backend action, and the keys of the one that holds an object: filterSignals'
// include and exclude.
private val OPTION_KEYS =
    Catalog.default.fetchOptions
        .map { it.name }
        .toSet() +
        setOf("include", "exclude")

// The VS Code grammar's keywords and constants; grammar.test.ts holds the two to this list.
private val JS_KEYWORDS =
    setOf(
        "if",
        "else",
        "return",
        "await",
        "async",
        "new",
        "typeof",
        "instanceof",
        "in",
        "of",
        "for",
        "while",
        "do",
        "switch",
        "case",
        "break",
        "continue",
        "throw",
        "try",
        "catch",
        "finally",
        "void",
        "delete",
        "function",
        "let",
        "const",
        "var",
        "this",
        "yield",
        "class",
        "true",
        "false",
        "null",
        "undefined",
        "NaN",
        "Infinity",
    )

// The VS Code grammar's globals; grammar.test.ts holds the two to this list.
private val JS_GLOBALS =
    setOf(
        "window",
        "document",
        "console",
        "Math",
        "JSON",
        "Date",
        "Intl",
        "navigator",
        "location",
        "history",
        "localStorage",
        "sessionStorage",
        "fetch",
        "setTimeout",
        "setInterval",
        "clearTimeout",
        "clearInterval",
        "Promise",
        "Array",
        "Object",
        "String",
        "Number",
        "Boolean",
    )

/** The words after which a value follows, as after a name: a slash then divides. */
private val VALUE_WORDS = setOf("this", "true", "false", "null", "undefined", "NaN", "Infinity")

/** JavaScript's operators, the longest first so that each is read whole. */
private val OPERATORS =
    (
        ">>>= ... === !== **= <<= >>= >>> &&= ||= ??= => == != <= >= && || ?? ?. ++ -- += -= *= /= %= &= |= ^= ** << >> " +
            "= + - * / % < > ! ? : & | ^ ~"
    ).split(' ')

/** HTML's spellings of a quote, which a value in an attribute may hold for one. */
private val ENTITY_QUOTES = listOf("&quot;", "&#34;", "&#39;", "&apos;")

private val KOTLIN_DOLLAR = "\${'$'}"

private val DURATION_WORD = regex("""\d+(ms|s)""")

/**
 * Tokenize a Datastar expression for highlighting; offsets are relative to [text]. What [opaque]
 * covers is somebody else's, a Kotlin template or a template engine's placeholder: it is read as
 * a value and gets no token, and a string or comment around it keeps the parts outside it.
 */
public fun tokenizeExpression(
    text: String,
    opaque: List<IntRange> = emptyList(),
): List<Token> {
    if (opaque.isEmpty()) return ExpressionScanner(text).all()
    val masked = CharArray(text.length) { text[it] }
    for (r in opaque) for (k in maxOf(0, r.first)..minOf(text.length - 1, r.last)) masked[k] = '_'
    val out = ArrayList<Token>()
    for (t in ExpressionScanner(masked.concatToString()).all()) {
        val inside = opaque.filter { t.start <= it.last && it.first < t.end }.sortedBy { it.first }
        if (inside.isEmpty()) {
            out += t
        } else if (t.kind == TokenKind.STRING || t.kind == TokenKind.COMMENT) {
            var from = t.start
            for (r in inside) {
                if (r.first > from) out += Token(from, r.first, t.kind)
                from = maxOf(from, r.last + 1)
            }
            if (from < t.end) out += Token(from, t.end, t.kind)
        }
    }
    return out
}

private class ExpressionScanner(
    private val text: String,
) {
    private val out = ArrayList<Token>()

    /** What came before, for a slash: `a` a value, `k` a keyword, else the character. */
    private var last: Char? = null

    /** Where the colon after an object's key stands: punctuation there, no operator, as the VS Code grammar scopes it. */
    private var keyColon = -1

    fun all(): List<Token> {
        run(0, inTemplate = false)
        return out
    }

    /** Reads from [from] to the end, or to the `}` that ends a template literal's expression; returns where it stopped. */
    private fun run(
        from: Int,
        inTemplate: Boolean,
    ): Int {
        var i = from
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                inTemplate && c == '}' && depth == 0 -> {
                    return i
                }

                text.startsWith(KOTLIN_DOLLAR, i) -> {
                    out += Token(i, i + KOTLIN_DOLLAR.length, TokenKind.KOTLIN_DOLLAR_ESCAPE)
                    // `${'$'}count` is the signal `$count`: the name that follows is a signal.
                    var j = i + KOTLIN_DOLLAR.length
                    if (j < text.length && text[j].isIdentPart()) j = signalTokens(text, j, j, out)
                    last = 'a'
                    i = j
                }

                c == '&' && ENTITY_QUOTES.any { text.startsWith(it, i) } -> {
                    val quote = ENTITY_QUOTES.first { text.startsWith(it, i) }
                    val close = text.indexOf(quote, i + quote.length)
                    val end = if (close < 0) text.length else close + quote.length
                    out += Token(i, end, TokenKind.STRING)
                    last = 'a'
                    i = end
                }

                c == '/' && next == '/' -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    out += Token(i, end, TokenKind.COMMENT)
                    i = end
                }

                c == '/' && next == '*' -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    out += Token(i, end, TokenKind.COMMENT)
                    i = end
                }

                c == '`' -> {
                    i = template(i)
                    last = 'a'
                }

                c == '\'' || c == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != c) {
                        if (text[j] == '\\') j++
                        j++
                    }
                    j = minOf(j + 1, text.length)
                    out += Token(i, j, TokenKind.STRING)
                    if (last == '{' || last == ',') markKeyColon(j)
                    last = 'a'
                    i = j
                }

                c == '/' && regexAllowedAfter(last) && regexEnd(i) > 0 -> {
                    val j = regexEnd(i)
                    out += Token(i, j, TokenKind.REGEX)
                    last = 'a'
                    i = j
                }

                c == '$' && next != null && next.isIdentPart() -> {
                    i = signalTokens(text, i, i + 1, out)
                    last = 'a'
                }

                c == '@' && next != null && next.isIdentStart() -> {
                    var j = i + 1
                    while (j < text.length && text[j].isIdentPart()) j++
                    val name = text.substring(i + 1, j)
                    if (nextNonSpace(j) == '(') {
                        out += Token(i, j, if (name in BACKEND_ACTIONS) TokenKind.ACTION_BACKEND else TokenKind.ACTION)
                    }
                    last = 'a'
                    i = j
                }

                c.isDigit() || (c == '.' && next != null && next.isDigit() && text.getOrNull(i - 1)?.isIdentPart() != true) -> {
                    i = number(i)
                    last = 'a'
                }

                c.isIdentStart() -> {
                    i = word(i)
                }

                c.isWhitespace() -> {
                    i++
                }

                else -> {
                    val op = OPERATORS.firstOrNull { text.startsWith(it, i) }
                    if (op != null) {
                        if (i != keyColon) out += Token(i, i + op.length, if (op == "=>") TokenKind.KEYWORD else TokenKind.OPERATOR)
                        last = if (op == "++" || op == "--") 'a' else op.last()
                        i += op.length
                    } else {
                        if (c == '{') depth++
                        if (c == '}' && depth > 0) depth--
                        last = c
                        i++
                    }
                }
            }
        }
        return i
    }

    /** A template literal from its backtick: its text as strings, each `${...}` as an expression. */
    private fun template(start: Int): Int {
        var from = start
        var j = start + 1
        while (j < text.length && text[j] != '`') {
            when {
                text[j] == '\\' -> {
                    j += 2
                }

                text.startsWith("\${", j) -> {
                    if (j > from) out += Token(from, j, TokenKind.STRING)
                    out += Token(j, j + 2, TokenKind.TEMPLATE_PUNCTUATION)
                    val outer = last
                    last = null
                    val close = run(j + 2, inTemplate = true)
                    last = outer
                    if (close >= text.length) return text.length
                    out += Token(close, close + 1, TokenKind.TEMPLATE_PUNCTUATION)
                    j = close + 1
                    from = j
                }

                else -> {
                    j++
                }
            }
        }
        val end = minOf(j + 1, text.length)
        if (end > from) out += Token(from, end, TokenKind.STRING)
        return end
    }

    /** Where a regular expression that opens at [start] ends, or 0 when the slash opens none on its line. */
    private fun regexEnd(start: Int): Int {
        var j = start + 1
        var inClass = false
        while (j < text.length && text[j] != '\n') {
            val d = text[j]
            if (d == '\\') {
                j++
            } else if (d == '[') {
                inClass = true
            } else if (d == ']') {
                inClass = false
            } else if (d == '/' && !inClass) {
                j++
                while (j < text.length && text[j].isLetter()) j++
                return j
            }
            j++
        }
        return 0
    }

    private fun number(start: Int): Int {
        var j = start
        val radix = text[j] == '0' && text.getOrNull(j + 1)?.lowercaseChar() in setOf('x', 'b', 'o')
        if (radix) j += 2
        var dot = false
        while (j < text.length) {
            val d = text[j]
            when {
                d.isLetterOrDigit() || d == '_' -> {
                    j++
                }

                d == '.' && !dot && !radix && text.getOrNull(j + 1)?.isDigit() == true -> {
                    dot = true
                    j++
                }

                (d == '+' || d == '-') && !radix && text[j - 1].lowercaseChar() == 'e' && text.getOrNull(j + 1)?.isDigit() == true -> {
                    j++
                }

                else -> {
                    break
                }
            }
        }
        val word = text.substring(start, j)
        out += Token(start, j, if (DURATION_WORD.matchEntire(word) != null) TokenKind.DURATION else TokenKind.NUMBER)
        return j
    }

    private fun word(start: Int): Int {
        var j = start + 1
        while (j < text.length && text[j].isIdentPart()) j++
        val word = text.substring(start, j)
        val afterDot = start > 0 && text[start - 1] == '.'
        val after = nextNonSpace(j)
        val beforeColon = after == ':' && text.getOrNull(text.indexOf(':', j) + 1) != ':'
        val keyPlace = beforeColon && (last == '{' || last == ',')
        val kind =
            when {
                afterDot -> if (after == '(') TokenKind.METHOD else TokenKind.PROPERTY
                word in Catalog.default.scopeNames -> TokenKind.SCOPE_VARIABLE
                word in OPTION_KEYS && keyPlace -> TokenKind.OPTION_KEY
                keyPlace -> TokenKind.OBJECT_KEY
                word in JS_KEYWORDS -> TokenKind.KEYWORD
                word in JS_GLOBALS -> TokenKind.GLOBAL
                after == '(' -> TokenKind.METHOD
                else -> TokenKind.IDENTIFIER
            }
        out += Token(start, j, kind)
        if (keyPlace) markKeyColon(j)
        last = if (kind == TokenKind.KEYWORD && word !in VALUE_WORDS) 'k' else 'a'
        return j
    }

    /** Note the colon after a key that ends at [from], if one follows it. */
    private fun markKeyColon(from: Int) {
        var k = from
        while (k < text.length && text[k].isWhitespace()) k++
        if (text.getOrNull(k) == ':' && text.getOrNull(k + 1) != ':') keyColon = k
    }

    /** The first character from [from] that is not white space. */
    private fun nextNonSpace(from: Int): Char? {
        var k = from
        while (k < text.length && text[k].isWhitespace()) k++
        return text.getOrNull(k)
    }
}

/**
 * The signal the client's pattern `\$(\w+(?:[.-]\w+)*)` (engine.ts:546 in 1.0.5) reads at [nameStart]:
 * the name up to its first dot as a SIGNAL token from [start], the rest as its path. Returns the
 * offset after the match, which a trailing dot or hyphen is outside of.
 */
private fun signalTokens(
    text: String,
    start: Int,
    nameStart: Int,
    out: MutableList<Token>,
): Int {
    fun wordEnd(from: Int): Int {
        var j = from
        while (j < text.length && text[j].isIdentPart()) j++
        return j
    }
    var j = wordEnd(nameStart)
    while (j + 1 < text.length && text[j] == '-' && text[j + 1].isIdentPart()) j = wordEnd(j + 1)
    out += Token(start, j, TokenKind.SIGNAL)
    val pathStart = j
    while (j + 1 < text.length && (text[j] == '.' || text[j] == '-') && text[j + 1].isIdentPart()) j = wordEnd(j + 1)
    if (j > pathStart) out += Token(pathStart, j, TokenKind.SIGNAL_PATH)
    return j
}

/** After a value a slash divides; after an operator, a bracket or a keyword it opens a regex. */
private fun regexAllowedAfter(last: Char?): Boolean = last == null || last == 'k' || last !in setOf('a', ')', ']', '}')

private val SIGNAL_NAME = regex("""[A-Za-z_][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)*(?:\.[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)*)*""")

/**
 * A signal named without its `$`, as `data-bind="user.name"` and `dataBind("user.name")` write it:
 * the name and its path, or nothing when [text] is no name the client would read.
 */
public fun tokenizeSignalName(text: String): List<Token> {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return emptyList()
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    if (SIGNAL_NAME.matchEntire(text.substring(start, end)) == null) return emptyList()
    val out = ArrayList<Token>()
    signalTokens(text.substring(0, end), start, start, out)
    return out
}

/**
 * Where a template engine writes into a value before the browser reads it: `${...}`, `{{...}}`,
 * `{% %}`, `<% %>`, a directive with its condition, and Streamlord's own `__kt__`. A `${` inside a
 * JavaScript template literal is JavaScript's own and is none.
 */
public fun templatePlaceholders(value: String): List<IntRange> {
    val quotes = QuoteScan(value)
    val out = ArrayList<IntRange>()
    for (m in TEMPLATE_SYNTAX.findAll(value)) {
        var s = m.range.first
        while (s < m.range.last && (value[s].isWhitespace() || value[s] == '>')) s++
        if (value[s] == '!' && value.getOrNull(s - 1) == '$') s--
        if (out.lastOrNull()?.let { s <= it.last } == true) continue
        if (value.startsWith("\${", s) && quotes.at(s) == '`') continue
        out += s until placeholderEnd(value, s)
    }
    return out
}

/** Where the placeholder that starts at [s] ends. */
private fun placeholderEnd(
    value: String,
    s: Int,
): Int {
    fun closeAt(close: String): Int = value.indexOf(close, s + 2).let { if (it < 0) value.length else it + close.length }
    return when {
        value.startsWith("{{", s) -> {
            closeAt("}}").let { if (it < value.length && value[it] == '}') it + 1 else it }
        }

        value.startsWith("{%", s) -> {
            closeAt("%}")
        }

        value.startsWith("<%", s) -> {
            closeAt("%>")
        }

        value.startsWith("[#", s) || value.startsWith("[=", s) -> {
            closeAt("]")
        }

        value.startsWith(PLACEHOLDER, s) -> {
            s + PLACEHOLDER.length
        }

        value.getOrNull(s + 1) == '{' || value.getOrNull(s + 2) == '{' -> {
            balanced(value, value.indexOf('{', s), '{', '}')
        }

        else -> {
            // A directive: its word and the condition in parentheses after it.
            var j = s + 1
            while (j < value.length && value[j].isLetter()) j++
            if (value.getOrNull(j) == '(') balanced(value, j, '(', ')') else j
        }
    }
}

/** The offset after the bracket that closes the one at [open], or the end. */
private fun balanced(
    text: String,
    open: Int,
    opening: Char,
    closing: Char,
): Int {
    var depth = 0
    var j = open
    while (j < text.length) {
        if (text[j] == opening) depth++
        if (text[j] == closing && --depth == 0) return j + 1
        j++
    }
    return text.length
}

/** The parts of a rendered `data-*` attribute name, for highlighting. */
public enum class NamePart {
    PREFIX,
    PLUGIN,
    KEY_SEPARATOR,
    KEY,
    MODIFIER_SIGIL,
    MODIFIER,
    MODIFIER_ARG_DOT,

    /** A word: `leading`, `kebab`. */
    MODIFIER_ARG,

    /** A number: `50` in `__threshold.50`. */
    MODIFIER_ARG_NUMBER,

    /** A duration: `500ms` in `__debounce.500ms`. */
    MODIFIER_ARG_DURATION,
}

public data class NameToken(
    val start: Int,
    val end: Int,
    val part: NamePart,
)

private val DIGITS = regex("""\d+""")

/**
 * Split an attribute name such as `data-on:click__debounce.500ms` into coloured parts; null when it
 * is not a Datastar attribute. The prefix is matched in any case, as the browser lowercases a name.
 */
public fun tokenizeAttributeName(
    name: String,
    prefix: String,
): List<NameToken>? {
    if (!name.startsWith(prefix, ignoreCase = true) || name.length <= prefix.length) return null
    val out = ArrayList<NameToken>()
    out += NameToken(0, prefix.length, NamePart.PREFIX)
    val rest = name.substring(prefix.length)
    val parts = rest.split("__")
    val head = parts[0]
    var offset = prefix.length
    val colon = head.indexOf(':')
    if (colon >= 0) {
        out += NameToken(offset, offset + colon, NamePart.PLUGIN)
        out += NameToken(offset + colon, offset + colon + 1, NamePart.KEY_SEPARATOR)
        if (head.length > colon + 1) out += NameToken(offset + colon + 1, offset + head.length, NamePart.KEY)
    } else {
        out += NameToken(offset, offset + head.length, NamePart.PLUGIN)
    }
    offset += head.length
    for (part in parts.drop(1)) {
        out += NameToken(offset, offset + 2, NamePart.MODIFIER_SIGIL)
        offset += 2
        val pieces = part.split(".")
        val modEnd = offset + pieces[0].length
        if (pieces[0].isNotEmpty()) out += NameToken(offset, modEnd, NamePart.MODIFIER)
        var p = modEnd
        for (arg in pieces.drop(1)) {
            out += NameToken(p, p + 1, NamePart.MODIFIER_ARG_DOT)
            p++
            val kind =
                when {
                    DURATION_WORD.matchEntire(arg) != null -> NamePart.MODIFIER_ARG_DURATION
                    DIGITS.matchEntire(arg) != null -> NamePart.MODIFIER_ARG_NUMBER
                    else -> NamePart.MODIFIER_ARG
                }
            if (arg.isNotEmpty()) out += NameToken(p, p + arg.length, kind)
            p += arg.length
        }
        offset += part.length
    }
    return out
}
