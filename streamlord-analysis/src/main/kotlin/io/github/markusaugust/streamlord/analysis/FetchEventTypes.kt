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
private val OPERATOR_BEFORE = regex("""(?:===?|!==?)\s*$""")

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
    for (m in DETAIL_TYPE.findAll(text)) {
        val at = m.range.first
        if (regions[at] != Region.CODE || text.getOrNull(at - 1).let { it != null && (it.isJsNamePart() || it == '.') }) continue
        val right = OPERATOR_AFTER.matchAt(text, m.range.last + 1)?.let { literal(it.range.last + 1) }
        val left =
            OPERATOR_BEFORE.find(text.substring(0, at).trimEnd())?.let { op ->
                var close = op.range.first - 1
                while (close >= 0 && isJsSpace(text[close])) close--
                val q = text.getOrNull(close)
                val open = if (q == null) -1 else text.lastIndexOf(q, close - 1)
                if (open >= 0) literal(open)?.takeIf { it.last == close } else null
            }
        for (range in listOfNotNull(left, right)) {
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
