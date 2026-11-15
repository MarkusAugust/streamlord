package io.github.markusaugust.streamlord.core.client

import io.github.markusaugust.streamlord.core.InternalStreamlordApi

/*
 * How Datastar's client rewrites the `$` and `@` references in an expression before compiling it.
 * The SDK reads a name with it as the client will, and the analysis judges an expression on the
 * text it gives. `conformance/client/expressions.json`, the body the client compiles for each
 * expression, holds it to the client, and ClientRewriteTest to the client's pattern as written.
 */

/** JavaScript as Datastar compiles an expression, with a map from each of its offsets back to the expression. */
@InternalStreamlordApi
public open class ClientScript(
    public val js: String,
    private val origin: IntArray,
) {
    /** The offset in the expression that [offset] in [js] came from. */
    public fun toExpression(offset: Int): Int = origin[offset.coerceIn(0, origin.size - 1)]
}

/** A `$name.path` or an `@name(` the client rewrites; [end] is the end of what it replaces. */
@InternalStreamlordApi
public data class Rewrite(
    public val signal: Boolean,
    public val start: Int,
    public val end: Int,
    public val name: String,
) {
    /** What the client writes in its place: `$['name']['path']`, or the start of a call of the action dispatcher. */
    public val written: String get() = if (signal) signalPath(name) else actionCall(name)
}

/**
 * A stretch the client stepped over and left as written: a quoted string, or the text of a template
 * literal between its `${...}`. [quote] is where that string or template literal opens.
 */
@InternalStreamlordApi
public class Run(
    public val range: IntRange,
    public val quote: Int,
)

/** The text as the client rewrites it, what it rewrote, and the quoted runs it stepped over and left as written. */
@InternalStreamlordApi
public class ClientRewrite(
    js: String,
    origin: IntArray,
    public val rewrites: List<Rewrite>,
    public val runs: List<Run>,
) : ClientScript(js, origin)

/** The client's signal pattern (engine.ts, `genRx`, in 1.0.5): `\w` and `.`/`-` joined parts. */
@InternalStreamlordApi
public val CLIENT_SIGNAL: Regex = regex("""\$(\w+(?:[.-]\w+)*)""")

/** The client's action pattern: a name and the parenthesis right after it. */
private val CLIENT_ACTION = regex("""@([A-Za-z_$][\w$]*)\(""")

private fun signalPath(name: String): String = "$" + name.split('.').joinToString("") { "['$it']" }

private fun actionCall(name: String): String = "__action(\"$name\",evt,"

/** What `.` in the client's pattern does not match. */
private fun Char.isLineTerminator(): Boolean = this == '\n' || this == '\r' || this == '\u2028' || this == '\u2029'

/**
 * The end of a quoted string as the client's pattern matches one, `"[^"\\]*(?:\\.[^"\\]*)*"`:
 * an escape takes the next character unless that is a line terminator, and a string that never
 * closes is no string. Null when the pattern would not match here.
 */
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

/** Where the `${` at [from] closes, when no brace stands inside it and it closes before [limit]; null otherwise. */
private fun interpolationClose(
    text: String,
    from: Int,
    limit: Int,
): Int? = text.indexOfAny(charArrayOf('{', '}'), from + 2).takeIf { it in 0 until limit && text[it] == '}' }

/**
 * The end of a template literal as the client's pattern matches one whole,
 * `` `(?:\\.|[^`\\$]|\$(?!\{)|\$\{[^{}]*\})*` ``: a `${` in it has to close with no brace inside. Null
 * when the pattern would not match here.
 */
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
            c == '$' && text.getOrNull(j + 1) == '{' -> j = (interpolationClose(text, j, text.length) ?: return null) + 1
            else -> j++
        }
    }
    return null
}

/**
 * The text as the client writes it before compiling, with what it rewrote (engine.ts, `genRx`, in
 * 1.0.5). The client does it with one pattern: a quoted string is stepped over and left as
 * written, and so is a template literal, matched whole, but for each `${...}` in it with no brace
 * inside, whose signals and then actions are rewritten; outside them a `$name.path` becomes
 * `$['name']['path']` and an `@name(` a call of the action dispatcher. A template literal the
 * pattern does not match, one whose `${` holds a brace, is read as code. Here it is a scan, since Java's engine recurses on that pattern's
 * loops and overflows on a long string of escapes; the alternatives are tried in the client's order
 * at every offset, and `ClientRewriteTest` holds the scan to the client's pattern as written. Every
 * character of a rewritten reference maps back to where the reference began.
 */
@InternalStreamlordApi
public fun clientRewrite(text: String): ClientRewrite {
    val js = StringBuilder()
    val origin = ArrayList<Int>(text.length + 16)
    val rewrites = ArrayList<Rewrite>()
    val runs = ArrayList<Run>()

    fun copy(
        from: Int,
        to: Int,
    ) {
        for (k in from until to) {
            origin += k
            js.append(text[k])
        }
    }

    fun put(r: Rewrite) {
        val written = r.written
        repeat(written.length) { origin += r.start }
        js.append(written)
        rewrites += r
    }

    fun interpolation(
        from: Int,
        to: Int,
    ) {
        val inner = text.substring(from, to)
        val found = ArrayList<Rewrite>()
        for (m in CLIENT_SIGNAL.findAll(inner)) found += Rewrite(true, from + m.range.first, from + m.range.last + 1, m.groupValues[1])
        val signals = found.size
        // An action the signal pass has already rewritten a part of is no longer there to match.
        // Both lists come in the order of the text, so each signal is looked at once.
        var k = 0
        for (m in CLIENT_ACTION.findAll(inner)) {
            val start = from + m.range.first
            val end = from + m.range.last + 1
            while (k < signals && found[k].end <= start) k++
            if (k == signals || found[k].start >= end) found += Rewrite(false, start, end, m.groupValues[1])
        }
        found.sortBy { it.start }
        var i = from
        for (r in found) {
            copy(i, r.start)
            put(r)
            i = r.end
        }
        copy(i, to)
    }

    // The client replaces every `${...}` without a brace inside in the literal it matched, wherever it stands in it.
    fun template(
        from: Int,
        to: Int,
    ) {
        var written = from
        var p = from
        // The search stays inside the literal: one that ran on to the end of the text would be run again for every literal.
        while (p < to - 1) {
            val close = if (text[p] == '$' && text[p + 1] == '{') interpolationClose(text, p, to) else null
            if (close == null) {
                p++
                continue
            }
            copy(written, p + 2)
            runs += Run(written until p + 2, from)
            interpolation(p + 2, close)
            written = close
            p = close
        }
        copy(written, to)
        runs += Run(written until to, from)
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val end =
            when {
                c == '"' || c == '\'' -> {
                    quotedEnd(text, i, c)?.also {
                        copy(i, it)
                        runs += Run(i until it, i)
                    }
                }

                c == '`' -> {
                    templateEnd(text, i)?.also { template(i, it) }
                }

                c == '$' -> {
                    CLIENT_SIGNAL.matchAt(text, i)?.let { m ->
                        put(Rewrite(true, i, m.range.last + 1, m.groupValues[1]))
                        m.range.last + 1
                    }
                }

                c == '@' -> {
                    CLIENT_ACTION.matchAt(text, i)?.let { m ->
                        put(Rewrite(false, i, m.range.last + 1, m.groupValues[1]))
                        m.range.last + 1
                    }
                }

                else -> {
                    null
                }
            }
        i = end ?: run {
            copy(i, i + 1)
            i + 1
        }
    }
    origin += text.length
    return ClientRewrite(js.toString(), origin.toIntArray(), rewrites, runs)
}
