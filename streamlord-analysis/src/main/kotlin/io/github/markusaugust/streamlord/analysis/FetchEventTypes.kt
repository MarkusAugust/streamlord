package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.domain.ElementsGuard

/*
 * The type a handler of datastar-fetch compares evt.detail.type with: one the client dispatches
 * (fetch.ts: started, finished, error, retrying, retries-failed, and every event of the response
 * whose name starts with datastar), or a string that never comes.
 */

private val EQUALITY = setOf("===", "==", "!==", "!=")

/** `evt.detail.type`, read of the event itself, with `?.` or without. */
private fun JsNode.isDetailType(): Boolean {
    val read = if (type == JsKind.CHAIN) inner!! else this
    val detail = read.inner ?: return false
    val evt = detail.inner ?: return false
    return read.type == JsKind.MEMBER && read.name == "type" && detail.type == JsKind.MEMBER && detail.name == "detail" &&
        evt.type == JsKind.IDENT && evt.name == "evt"
}

/**
 * The warnings for each string literal [text] compares with `evt.detail.type` that datastar-fetch is never dispatched
 * with: the other side of an equality, and each case of a switch on it, which compares by `===`. A string joined to
 * something or chosen by a condition is the code's. [parsed] is the tree of the text, and [at] says where in [text]
 * an offset of a node stands.
 */
internal fun fetchTypeIssues(
    catalog: Catalog,
    text: String,
    parsed: Parsed,
    at: (Int) -> Int,
): List<Issue> {
    val compared = ArrayList<JsNode>()
    // What is read to its end is judged while the rest is typed: a comparison, and a case at its colon.
    for (node in parsed.finished) {
        if (node.type == JsKind.BINARY && node.name in EQUALITY) {
            val (left, right) = node.items
            if (left!!.isDetailType()) {
                compared += right!!
            } else if (right!!.isDetailType()) {
                compared += left
            }
        } else if (node.type == JsKind.CASE && node.items[0]!!.isDetailType()) {
            compared += node.inner!!
        }
    }
    val out = ArrayList<Issue>()
    for (node in compared.sortedBy { it.start }) {
        if (node.type != JsKind.STRING && !(node.type == JsKind.TEMPLATE && node.items.isEmpty())) continue
        val start = at(node.start)
        val end = at(node.end)
        val type = writtenString(text, start, end) ?: continue
        // An escape is not read here, and a Kotlin template there is the code's value, not written.
        if ('\\' in type || PLACEHOLDER in type || type in catalog.fetchEvents || type.startsWith("datastar")) continue
        val near =
            catalog.fetchEvents.singleOrNull { type.isNotEmpty() && it.startsWith(type) }
                ?: catalog.fetchEvents.singleOrNull {
                    ElementsGuard.oneEditAway(type, it, swaps = true) ||
                        it.equals(type, ignoreCase = true)
                }
        out +=
            Issue(
                start,
                end,
                "$DATASTAR_FETCH is never dispatched with the type ${text.substring(start, end)}: Datastar dispatches " +
                    catalog.fetchEvents.joinToString(", ") + " and the datastar events the response sends." +
                    (near?.let { " Did you mean $it?" } ?: ""),
                Severity.WARNING,
                "fetch-event-type",
                Docs.ACTIONS,
                listOfNotNull(near?.let { Fix("Change to $it", start + 1, end - 1, it) }),
            )
    }
    return out
}
