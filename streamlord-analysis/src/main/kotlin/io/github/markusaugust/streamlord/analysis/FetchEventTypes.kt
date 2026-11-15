package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.domain.ElementsGuard

/*
 * The type a handler of datastar-fetch compares evt.detail.type with: one the client dispatches
 * (fetch.ts: started, finished, error, retrying, retries-failed, and every event of the response
 * whose name starts with datastar), or a string that never comes.
 */

private val DETAIL_TYPE = regex("""evt\s*\.\s*detail\s*\.\s*type(?![\w$])""")
private val OPERATOR_AFTER = regex("""\s*(?:===?|!==?)\s*""")
private val OPERATORS = listOf("!==", "===", "!=", "==")

// What may stand before and after a literal compared on its own, the comparison binding tighter: `'a' + 'b' === evt.detail.type`
// compares with what the sum gives, and so does `case 'a' + 'b':`.
private const val STARTS = "([{},;?:&|=>"
private const val ENDS = ")]},;?:&|"
private val SWITCH_AFTER = regex("""\s*\)\s*\{""")
private val CASE = regex("""(?<![\w$.])case\s*""")

/** The warnings for each string literal [text] compares with `evt.detail.type` that datastar-fetch is never dispatched with. */
internal fun fetchTypeIssues(
    catalog: Catalog,
    text: String,
): List<Issue> {
    if ("detail" !in text) return emptyList()
    val regions = jsRegions(text)
    val out = ArrayList<Issue>()

    /** The string literal from [open] to its closing quote, without an escape or a template's code in it. */
    fun literal(open: Int): IntRange? {
        val q = text.getOrNull(open) ?: return null
        if (q != '\'' && q != '"' && q != '`') return null
        val close = text.indexOf(q, open + 1)
        if (close < 0 || (open..close).any { regions[it] != regions[open] }) return null
        val content = text.substring(open + 1, close)
        // A Kotlin template there is the code's value, not written.
        return if ('\\' in content || "\${" in content || PLACEHOLDER in content) null else open..close
    }

    /** Where the text from [from], a [step] of 1 or -1 at a time, has passed white space, and comments where [comments]. */
    fun past(
        from: Int,
        step: Int,
        comments: Boolean = true,
    ): Int {
        var i = from
        while (i in text.indices && ((comments && regions[i] == Region.COMMENT) || isJsSpace(text[i]))) i += step
        return i
    }

    // Whether the text past a literal ends or goes on with one of [chars]: what it is compared with is the literal.
    fun alone(
        from: Int,
        step: Int,
        chars: String,
    ) = past(from, step).let { it !in text.indices || text[it] in chars }

    /**
     * The string literals of the cases of `switch (evt.detail.type) { ... }`, where the match from [at] until [end] is that
     * switch's subject: a switch compares with each case by `===`. A case of a switch nested inside is that switch's.
     */
    fun switchCases(
        at: Int,
        end: Int,
    ): List<IntRange> {
        val paren = past(at - 1, -1, comments = false)
        val word = past(paren - 1, -1, comments = false) + 1
        if (text.getOrNull(paren) != '(' || word < 6 || !text.startsWith("switch", word - 6)) return emptyList()
        if (text.getOrNull(word - 7).let { it != null && (it.isJsNamePart() || it == '.') }) return emptyList()
        var i = SWITCH_AFTER.matchAt(text, end)?.range?.last ?: return emptyList()
        val cases = ArrayList<IntRange>()
        var depth = 0
        while (i < text.length) {
            if (regions[i] == Region.CODE && text[i] == '{') depth++
            if (regions[i] == Region.CODE && text[i] == '}' && --depth == 0) break
            if (regions[i] == Region.CODE && depth == 1) CASE.matchAt(text, i)?.let { m -> literal(m.range.last + 1)?.let { cases += it } }
            i++
        }
        return cases.filter { alone(it.last + 1, 1, ":}") }
    }
    for (m in DETAIL_TYPE.findAll(text)) {
        val at = m.range.first
        if (regions[at] != Region.CODE || text.getOrNull(at - 1).let { it != null && (it.isJsNamePart() || it == '.') }) continue
        val right =
            OPERATOR_AFTER.matchAt(text, m.range.last + 1)?.let { op ->
                literal(op.range.last + 1)?.takeIf { alone(it.last + 1, 1, ENDS) }
            }
        val opEnd = past(at - 1, -1, comments = false) + 1
        val left =
            OPERATORS.firstOrNull { opEnd >= it.length && text.startsWith(it, opEnd - it.length) }?.let { op ->
                val close = past(opEnd - op.length - 1, -1, comments = false)
                val q = text.getOrNull(close)
                val open = if (q == null || q !in "'\"`") -1 else text.lastIndexOf(q, close - 1)
                if (open >= 0) literal(open)?.takeIf { it.last == close && alone(it.first - 1, -1, STARTS) } else null
            }
        for (range in listOfNotNull(left, right) + switchCases(at, m.range.last + 1)) {
            val type = text.substring(range.first + 1, range.last)
            if (type in catalog.fetchEvents || type.startsWith("datastar")) continue
            val near =
                catalog.fetchEvents.singleOrNull { type.isNotEmpty() && it.startsWith(type) }
                    ?: catalog.fetchEvents.singleOrNull {
                        ElementsGuard.oneEditAway(type, it, swaps = true) ||
                            it.equals(type, ignoreCase = true)
                    }
            out +=
                Issue(
                    range.first,
                    range.last + 1,
                    "$DATASTAR_FETCH is never dispatched with the type ${text.substring(
                        range.first,
                        range.last + 1,
                    )}: Datastar dispatches " +
                        catalog.fetchEvents.joinToString(", ") + " and the datastar events the response sends." +
                        (near?.let { " Did you mean $it?" } ?: ""),
                    Severity.WARNING,
                    "fetch-event-type",
                    Docs.ACTIONS,
                    listOfNotNull(near?.let { Fix("Change to $it", range.first + 1, range.last, it) }),
                )
        }
    }
    return out
}
