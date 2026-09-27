package io.github.markusaugust.streamlord.analysis

/*
 * Syntax highlighting of what Datastar adds to JavaScript and HTML: signals, actions, the scope
 * variables, option keys, durations, and the parts of a `data-*` attribute name. Everything that
 * is ordinary JavaScript gets the editor's own colours; only the Datastar concepts need rules.
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

    /** A keyword of JavaScript's own. */
    KEYWORD,

    /** A number of JavaScript's own. */
    NUMBER,

    /** A Kotlin `${'$'}` escape for a dollar, in Kotlin strings. */
    KOTLIN_DOLLAR_ESCAPE,
}

public data class Token(
    val start: Int,
    val end: Int,
    val kind: TokenKind,
)

private val BACKEND_ACTIONS = setOf("get", "post", "put", "patch", "delete", "query")
private val SCOPE_VARS = setOf("el", "evt", "patch")
private val OPTION_KEYS =
    setOf(
        "contentType",
        "filterSignals",
        "selector",
        "headers",
        "openWhenHidden",
        "payload",
        "requestCancellation",
        "responseOverrides",
        "retry",
        "retryInterval",
        "retryScaler",
        "retryMaxWait",
        "retryMaxCount",
        "include",
        "exclude",
        "mode",
        "namespace",
        "useViewTransition",
        "onlyIfMissing",
    )
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
private val KOTLIN_DOLLAR = "\${'$'}"

/** Tokenize a Datastar expression for highlighting; offsets are relative to [text]. */
public fun tokenizeExpression(text: String): List<Token> {
    val out = ArrayList<Token>()
    var i = 0
    var lastSignificant: Char? = null
    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith(KOTLIN_DOLLAR, i) -> {
                out += Token(i, i + KOTLIN_DOLLAR.length, TokenKind.KOTLIN_DOLLAR_ESCAPE)
                // `${'$'}count` is the signal `$count`: the name that follows is a signal.
                var j = i + KOTLIN_DOLLAR.length
                if (j < text.length && text[j].isIdentStart()) {
                    val nameStart = j
                    while (j < text.length && text[j].isIdentPart()) j++
                    out += Token(nameStart, j, TokenKind.SIGNAL)
                    j = signalPath(text, j, out)
                }
                lastSignificant = 'a'
                i = j
            }

            c == '\'' || c == '"' || c == '`' -> {
                var j = i + 1
                while (j < text.length && text[j] != c) {
                    if (text[j] == '\\') j++
                    j++
                }
                j = minOf(j + 1, text.length)
                out += Token(i, j, TokenKind.STRING)
                lastSignificant = c
                i = j
            }

            c == '/' && i + 1 < text.length && text[i + 1] != '/' && text[i + 1] != '*' && regexAllowedAfter(lastSignificant) -> {
                var j = i + 1
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
                        break
                    }
                    j++
                }
                if (j >= text.length || text[j] != '/') {
                    // Not a regex after all: a lone slash.
                    lastSignificant = '/'
                    i++
                } else {
                    j++
                    while (j < text.length && text[j].isLetter()) j++
                    out += Token(i, j, TokenKind.REGEX)
                    lastSignificant = 'a'
                    i = j
                }
            }

            c == '$' && i + 1 < text.length && text[i + 1].isIdentStart() -> {
                var j = i + 1
                while (j < text.length && text[j].isIdentPart()) j++
                out += Token(i, j, TokenKind.SIGNAL)
                j = signalPath(text, j, out)
                lastSignificant = 'a'
                i = j
            }

            c == '@' && i + 1 < text.length && text[i + 1].isIdentStart() -> {
                var j = i + 1
                while (j < text.length && text[j].isIdentPart()) j++
                val name = text.substring(i + 1, j)
                var k = j
                while (k < text.length && text[k].isWhitespace()) k++
                if (k < text.length && text[k] == '(') {
                    out += Token(i, j, if (name in BACKEND_ACTIONS) TokenKind.ACTION_BACKEND else TokenKind.ACTION)
                }
                lastSignificant = 'a'
                i = j
            }

            c.isIdentStart() -> {
                var j = i + 1
                while (j < text.length && text[j].isIdentPart()) j++
                val word = text.substring(i, j)
                val afterDot = i > 0 && text[i - 1] == '.'
                var k = j
                while (k < text.length && text[k].isWhitespace()) k++
                val beforeColon = k < text.length && text[k] == ':' && (k + 1 >= text.length || text[k + 1] != ':')
                when {
                    afterDot -> {}

                    word in SCOPE_VARS -> {
                        out += Token(i, j, TokenKind.SCOPE_VARIABLE)
                    }

                    word in OPTION_KEYS && beforeColon -> {
                        out += Token(i, j, TokenKind.OPTION_KEY)
                    }

                    word in JS_KEYWORDS && !beforeColon -> {
                        out += Token(i, j, TokenKind.KEYWORD)
                    }
                }
                lastSignificant = if (word in JS_KEYWORDS && word !in setOf("this", "true", "false", "null", "undefined")) 'k' else 'a'
                i = j
            }

            c.isDigit() -> {
                var j = i + 1
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                val word = text.substring(i, j)
                out += Token(i, j, if (Regex("""\d+(ms|s)""").matches(word)) TokenKind.DURATION else TokenKind.NUMBER)
                lastSignificant = 'a'
                i = j
            }

            c.isWhitespace() -> {
                i++
            }

            else -> {
                lastSignificant = c
                i++
            }
        }
    }
    return out
}

private fun signalPath(
    text: String,
    from: Int,
    out: MutableList<Token>,
): Int {
    var j = from
    val pathStart = j
    while (j + 1 < text.length && text[j] == '.' && text[j + 1].isIdentStart()) {
        j++
        while (j < text.length && text[j].isIdentPart()) j++
    }
    if (j > pathStart) out += Token(pathStart, j, TokenKind.SIGNAL_PATH)
    return j
}

/** After a value a slash divides; after an operator, a bracket or a keyword it opens a regex. */
private fun regexAllowedAfter(last: Char?): Boolean = last == null || last == 'k' || last !in setOf('a', ')', ']', '}')

/** The parts of a rendered `data-*` attribute name, for highlighting. */
public enum class NamePart { PREFIX, PLUGIN, KEY_SEPARATOR, KEY, MODIFIER_SIGIL, MODIFIER, MODIFIER_ARG_DOT, MODIFIER_ARG }

public data class NameToken(
    val start: Int,
    val end: Int,
    val part: NamePart,
)

/** Split an attribute name such as `data-on:click__debounce.500ms` into coloured parts; null when it is not a Datastar attribute. */
public fun tokenizeAttributeName(
    name: String,
    prefix: String,
): List<NameToken>? {
    if (!name.startsWith(prefix) || name.length <= prefix.length) return null
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
            if (arg.isNotEmpty()) out += NameToken(p, p + arg.length, NamePart.MODIFIER_ARG)
            p += arg.length
        }
        offset += part.length
    }
    return out
}
