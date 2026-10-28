package io.github.markusaugust.streamlord.core.domain

/*
 * How Datastar's client finds the `$` references in an expression before compiling it (engine.ts:448
 * in 1.0.4), for the SDK to read a name as the client will. The client does it with one pattern: a
 * quoted string or a template literal without `${` is stepped over, a `${...}` with no brace inside
 * has its signals and then its actions rewritten, and outside them `$name.path` becomes
 * `$['name']['path']` and `@name(` a call of the action dispatcher. Here it is a scan, as in
 * streamlord-analysis's `clientRewrite`, and `ClientOracleTest` holds it to the body the client
 * compiles for every expression in `conformance/client/expressions.json`.
 */

/** One `$name.path` the client rewrites, at `[start, end)` of the text, with `name.path` as [name]. */
internal class ClientReference(
    val start: Int,
    val end: Int,
    val name: String,
)

/** The text as the client writes it before compiling, the signal references it rewrote, and the strings it stepped over. */
internal class ClientRewritten(
    val js: String,
    val references: List<ClientReference>,
    val strings: List<IntRange>,
)

/** The client's signal pattern: `\w` and `.`/`-` joined parts. */
private val CLIENT_SIGNAL = Regex("""\$(\w+(?:[.-]\w+)*)""")

/** The client's action pattern: a name and the parenthesis right after it. */
private val CLIENT_ACTION = Regex("""@([A-Za-z_$][\w$]*)\(""")

private fun signalPath(name: String): String = "$" + name.split('.').joinToString("") { "['$it']" }

private fun actionCall(name: String): String = "__action(\"$name\",evt,"

/** What `.` in the client's pattern does not match. */
private fun Char.isLineTerminator(): Boolean = this == '\n' || this == '\r' || this == ' ' || this == ' '

/** The end of a quoted string as the client's pattern matches one, or null when it would not match one here. */
private fun quotedEnd(
    text: String,
    from: Int,
    quote: Char,
): Int? {
    var j = from + 1
    while (j < text.length) {
        val c = text[j]
        when {
            c == quote -> return j + 1
            c == '\\' -> if (j + 1 < text.length && !text[j + 1].isLineTerminator()) j += 2 else return null
            else -> j++
        }
    }
    return null
}

/** The end of a template literal with no `${` in it, as the client's pattern matches one; null otherwise. */
private fun templateEnd(
    text: String,
    from: Int,
): Int? {
    var j = from + 1
    while (j < text.length) {
        val c = text[j]
        when {
            c == '`' -> return j + 1
            c == '\\' -> if (j + 1 < text.length && !text[j + 1].isLineTerminator()) j += 2 else return null
            c == '$' && text.getOrNull(j + 1) == '{' -> return null
            else -> j++
        }
    }
    return null
}

/** [text] rewritten as the client rewrites it, with the signal references it found. */
internal fun clientRewritten(text: String): ClientRewritten {
    val js = StringBuilder()
    val references = ArrayList<ClientReference>()
    val strings = ArrayList<IntRange>()

    fun interpolation(
        from: Int,
        to: Int,
    ) {
        val inner = text.substring(from, to)
        // Signals first, then the actions the signal pass left whole, as the client's two replaces do.
        val signals =
            CLIENT_SIGNAL
                .findAll(inner)
                .map {
                    ClientReference(from + it.range.first, from + it.range.last + 1, it.groupValues[1])
                }.toList()
        val actions =
            CLIENT_ACTION
                .findAll(inner)
                .map { Triple(from + it.range.first, from + it.range.last + 1, it.groupValues[1]) }
                .filter { (start, end) -> signals.none { it.start < end && start < it.end } }
                .toList()
        val found =
            (
                signals.map { Triple(it.start, it.end, signalPath(it.name)) } +
                    actions.map { (s, e, n) -> Triple(s, e, actionCall(n)) }
            ).sortedBy { it.first }
        references += signals
        var i = from
        for ((start, end, written) in found) {
            js.append(text, i, start).append(written)
            i = end
        }
        js.append(text, i, to)
    }

    var i = 0
    while (i < text.length) {
        val c = text[i]
        val end =
            when {
                c == '"' || c == '\'' -> {
                    quotedEnd(text, i, c)?.also {
                        js.append(text, i, it)
                        strings += i until it
                    }
                }

                c == '`' -> {
                    templateEnd(text, i)?.also {
                        js.append(text, i, it)
                        strings += i until it
                    }
                }

                c == '$' && text.getOrNull(i + 1) == '{' -> {
                    val close = text.indexOfAny(charArrayOf('{', '}'), i + 2)
                    if (close < 0 || text[close] != '}') {
                        null
                    } else {
                        js.append("\${")
                        interpolation(i + 2, close)
                        js.append('}')
                        close + 1
                    }
                }

                c == '$' -> {
                    CLIENT_SIGNAL.matchAt(text, i)?.let { m ->
                        references += ClientReference(i, m.range.last + 1, m.groupValues[1])
                        js.append(signalPath(m.groupValues[1]))
                        m.range.last + 1
                    }
                }

                c == '@' -> {
                    CLIENT_ACTION.matchAt(text, i)?.let { m ->
                        js.append(actionCall(m.groupValues[1]))
                        m.range.last + 1
                    }
                }

                else -> {
                    null
                }
            }
        i = end ?: run {
            js.append(c)
            i + 1
        }
    }
    return ClientRewritten(js.toString(), references, strings)
}
