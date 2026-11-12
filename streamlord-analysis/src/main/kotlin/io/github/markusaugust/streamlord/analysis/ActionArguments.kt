package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.InternalStreamlordApi
import io.github.markusaugust.streamlord.core.client.Rewrite
import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.domain.ElementsGuard

/*
 * The arguments of each action call, and the options and filters written in them, held to the
 * kinds the catalog takes from the client: one table, read for every action alike. Only a literal
 * is judged; a value computed by an expression is the author's.
 */

/** What a literal written as an argument or a value is; [EXPRESSION] is anything else, and never judged. */
private enum class Written(
    val phrase: String,
) {
    STRING("a string"),
    NUMBER("a number"),
    BOOLEAN("a boolean"),
    NULLISH(""),
    OBJECT("an object"),
    ARRAY("an array"),
    REGEX("a regular expression"),
    FUNCTION("a function"),
    EXPRESSION(""),
}

/** A value from [start] to [end] of the script: what it is, and a string's text where it has no escape. */
private class Value(
    val start: Int,
    val end: Int,
    val written: Written,
    val text: String?,
)

/** The longest number literal read. */
private const val SHORT = 40

private val NUMBER_LITERAL =
    regex("""[+-]?(?:0[xXoObB][0-9a-fA-F_]+|(?:\d[\d_]*\.?[\d_]*|\.\d[\d_]*)(?:[eE][+-]?\d+)?)n?""")
private val NUMERIC_STRING = regex("""\s*[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?\s*""")
private val IDENTIFIER = regex("""[A-Za-z_$][\w$]*""")

private fun ArgumentKind.phrase(): String =
    when (this) {
        ArgumentKind.STRING -> "a string"
        ArgumentKind.NUMBER -> "a number"
        ArgumentKind.BOOLEAN -> "true or false"
        ArgumentKind.OBJECT -> "an object"
        ArgumentKind.OPTIONS -> "an object of options"
        ArgumentKind.FILTER -> "an object of include and exclude"
        ArgumentKind.FUNCTION -> "a function"
        ArgumentKind.PATTERN -> "a regular expression or a string"
        ArgumentKind.ANY -> "anything"
    }

/** Whether a value written so is one of [kind]: a string of digits counts as a number, as the client counts with it. */
private fun ArgumentKind.takes(value: Value): Boolean =
    when (value.written) {
        Written.EXPRESSION, Written.NULLISH -> {
            true
        }

        Written.STRING -> {
            this == ArgumentKind.STRING || this == ArgumentKind.PATTERN || this == ArgumentKind.ANY ||
                (this == ArgumentKind.NUMBER && (value.text == null || NUMERIC_STRING.matchEntire(value.text) != null))
        }

        Written.NUMBER -> {
            this == ArgumentKind.NUMBER || this == ArgumentKind.BOOLEAN || this == ArgumentKind.ANY
        }

        Written.BOOLEAN -> {
            this == ArgumentKind.BOOLEAN || this == ArgumentKind.ANY
        }

        Written.OBJECT -> {
            this == ArgumentKind.OBJECT || this == ArgumentKind.OPTIONS || this == ArgumentKind.FILTER ||
                this == ArgumentKind.ANY
        }

        Written.REGEX -> {
            this == ArgumentKind.PATTERN || this == ArgumentKind.ANY
        }

        Written.FUNCTION -> {
            this == ArgumentKind.FUNCTION || this == ArgumentKind.ANY
        }

        Written.ARRAY -> {
            this == ArgumentKind.ANY
        }
    }

/**
 * The brackets of [js] in code, read in one pass: where each closes, and the commas and colons
 * at its own depth.
 */
private class Brackets(
    val js: String,
    val regions: ByteArray,
) {
    val close = IntArray(js.length) { -1 }
    private val commas = HashMap<Int, MutableList<Int>>()
    private val colons = HashMap<Int, MutableList<Int>>()

    init {
        val open = ArrayList<Int>()
        for (i in js.indices) {
            if (regions[i] != Region.CODE) continue
            when (js[i]) {
                '(', '[', '{' -> open += i
                ')', ']', '}' -> open.removeLastOrNull()?.let { close[it] = i }
                ',' -> open.lastOrNull()?.let { commas.getOrPut(it) { ArrayList() } += i }
                ':' -> open.lastOrNull()?.let { colons.getOrPut(it) { ArrayList() } += i }
            }
        }
    }

    /** The pieces between the bracket at [open] and its close, split at its commas, each trimmed; an empty last one is left out. */
    fun pieces(open: Int): List<IntRange> {
        val end = close[open]
        if (end < 0) return emptyList()
        val cuts = listOf(open) + (commas[open] ?: emptyList()) + end
        val out = cuts.zipWithNext().map { (a, b) -> trim(a + 1, b) }
        return if (out.last().isEmpty()) out.dropLast(1) else out
    }

    /** The colons of the bracket at [open] at its own depth, in order. */
    fun colons(open: Int): List<Int> = colons[open] ?: emptyList()

    /** [from] until [to] without the white space and comments at either end. */
    fun trim(
        from: Int,
        to: Int,
    ): IntRange {
        var a = from
        var b = to
        while (a < b && (isJsSpace(js[a]) || regions[a] == Region.COMMENT)) a++
        while (b > a && (isJsSpace(js[b - 1]) || regions[b - 1] == Region.COMMENT)) b--
        return a until b
    }

    /** What [piece] is, read from its first characters: a long one is read whole only where it is a string. */
    fun value(piece: IntRange): Value {
        val s = piece.first
        val e = piece.last + 1
        if (s >= e) return Value(s, e, Written.EXPRESSION, null)
        val first = js[s]

        fun all(region: Byte) = (s until e).all { regions[it] == region }
        val short = if (e - s <= SHORT) js.substring(s, e) else ""
        val written =
            when {
                (first == '\'' || first == '"') && e - s >= 2 && js[e - 1] == first && all(Region.STRING) -> Written.STRING

                // A template literal with a ${...} holds code, which is no part of its region.
                first == '`' && e - s >= 2 && js[e - 1] == '`' && all(Region.TEMPLATE) -> Written.STRING

                regions[s] == Region.REGEX && all(Region.REGEX) -> Written.REGEX

                NUMBER_LITERAL.matchEntire(short) != null -> Written.NUMBER

                short == "true" || short == "false" -> Written.BOOLEAN

                short == "null" || short == "undefined" -> Written.NULLISH

                first == '{' && close[s] == e - 1 -> Written.OBJECT

                first == '[' && close[s] == e - 1 -> Written.ARRAY

                isFunction(s, e) -> Written.FUNCTION

                else -> Written.EXPRESSION
            }
        val content = if (written == Written.STRING) js.substring(s + 1, e - 1).takeIf { '\\' !in it } else null
        return Value(s, e, written, content)
    }

    /** `function ...`, maybe after `async`, or an arrow: a name or a parenthesised list, maybe after `async`, then `=>`. */
    private fun isFunction(
        s: Int,
        e: Int,
    ): Boolean {
        var at = s

        fun word(w: String) = js.startsWith(w, at) && !js.getOrNull(at + w.length).isJsNamePart()

        fun space() {
            while (at < e && isJsSpace(js[at])) at++
        }
        if (word("async")) {
            at += 5
            space()
        }
        if (word("function")) return true
        if (at < e && js[at] == '(') {
            val c = close[at]
            if (c !in at until e) return false
            at = c + 1
        } else {
            at = (IDENTIFIER.matchAt(js, at) ?: return false).range.last + 1
        }
        space()
        return js.startsWith("=>", at)
    }
}

/** The warnings for the arguments of every action called in [js], whose characters are [regions]; [span] maps a stretch of it back to the text. */
@OptIn(InternalStreamlordApi::class)
internal fun actionArgumentIssues(
    catalog: Catalog,
    js: String,
    regions: ByteArray,
    rewrites: List<Rewrite>,
    span: (Int, Int) -> Pair<Int, Int>,
): List<Issue> {
    val calls =
        rewrites.filter {
            !it.signal && regions[it.start] == Region.CODE &&
                catalog.actionsByName[it.name]?.kinds?.isNotEmpty() == true
        }
    if (calls.isEmpty()) return emptyList()
    val brackets = Brackets(js, regions)
    val out = ArrayList<Issue>()
    val filterKey = { key: String ->
        "A signal filter has only ${catalog.signalFilter.joinToString(" and ") { it.name }}, so Datastar ignores $key."
    }

    fun issue(
        from: Int,
        to: Int,
        message: String,
        code: String,
        link: String,
        fixes: List<Fix> = emptyList(),
    ) {
        val (start, end) = span(from, to)
        val mapped =
            fixes.map {
                val (a, b) = span(it.start, it.end)
                it.copy(start = a, end = b)
            }
        out += Issue(start, end, message, Severity.WARNING, code, link, mapped)
    }

    fun shown(v: Value): String = js.substring(v.start, v.end).takeIf { it.length <= 40 && '\n' !in it } ?: "this value"

    /** What a value of the wrong kind is, and what the client then does, for a value of [kind] written as [v]. */
    fun wrong(
        kind: ArgumentKind,
        v: Value,
        pro: Boolean,
        filterChanges: Boolean,
    ): String {
        val what =
            when {
                kind == ArgumentKind.NUMBER && v.written == Written.STRING -> "a string that is no number"
                else -> v.written.phrase
            }
        val then =
            if (pro) {
                ""
            } else {
                when (kind) {
                    ArgumentKind.BOOLEAN -> ", which Datastar reads as ${v.text != ""}"
                    ArgumentKind.OPTIONS -> ", so Datastar reads every option at its default"
                    ArgumentKind.FILTER -> ", so Datastar reads no filter from it" + if (filterChanges) " and changes every signal" else ""
                    ArgumentKind.FUNCTION -> ", so Datastar throws a TypeError as it calls it"
                    ArgumentKind.PATTERN -> ", so Datastar throws a TypeError as it matches a signal"
                    else -> ""
                }
            }
        return "${shown(v)} is $what$then."
    }

    /** The keys of the object literal opening at [open], held to [fields]; [unknown] says what Datastar does with a key that is none. */
    fun judgeObject(
        open: Int,
        fields: List<FetchOption>,
        unknown: (String) -> String,
        link: String,
    ) {
        val byName = fields.associateBy { it.name }
        val colons = brackets.colons(open)
        var c = 0
        for (piece in brackets.pieces(open)) {
            // The pieces and the colons both run in order, so one pass over each finds a piece's first colon.
            while (c < colons.size && colons[c] < piece.first) c++
            val colon = colons.getOrNull(c)?.takeIf { it <= piece.last } ?: -1
            if (piece.isEmpty() || js.startsWith("...", piece.first)) continue
            val keyRange = if (colon < 0) piece else brackets.trim(piece.first, colon)
            if (keyRange.isEmpty() || keyRange.last - keyRange.first >= SHORT) continue
            val keyText = js.substring(keyRange.first, keyRange.last + 1)
            val quoted = keyText.length >= 2 && (keyText[0] == '\'' || keyText[0] == '"') && keyText.last() == keyText[0]
            val key = if (quoted) keyText.substring(1, keyText.length - 1) else keyText
            // A computed key, a method or a shorthand that is no name is not judged.
            if (IDENTIFIER.matchEntire(key) == null || (colon < 0 && quoted)) continue
            val field = byName[key]
            if (field == null) {
                val near =
                    fields.map { it.name }.singleOrNull {
                        ElementsGuard.oneEditAway(key, it, swaps = true) ||
                            it.equals(key, ignoreCase = true)
                    }
                val inner = if (quoted) keyRange.first + 1 else keyRange.first
                issue(
                    keyRange.first,
                    keyRange.last + 1,
                    unknown(key) + (near?.let { " Did you mean $it?" } ?: ""),
                    "unknown-option",
                    link,
                    listOfNotNull(near?.let { Fix("Change to $it", inner, inner + key.length, it) }),
                )
                continue
            }
            if (colon < 0) continue
            val v = brackets.value(brackets.trim(colon + 1, piece.last + 1))
            when {
                !field.kind.takes(v) -> {
                    issue(
                        v.start,
                        v.end,
                        "${field.name} takes ${field.kind.phrase()}, and " + wrong(field.kind, v, false, false),
                        "option-value",
                        link,
                    )
                }

                field.values.isNotEmpty() && v.written == Written.STRING && v.text != null && v.text !in field.values -> {
                    issue(
                        v.start,
                        v.end,
                        "${field.name} takes ${field.type}, and with ${shown(v)} ${field.otherwise}.",
                        "option-value",
                        link,
                    )
                }

                field.kind == ArgumentKind.FILTER && v.written == Written.OBJECT -> {
                    judgeObject(v.start, catalog.signalFilter, filterKey, link)
                }
            }
        }
    }

    for (r in calls) {
        val spec = catalog.actionsByName.getValue(r.name)
        val open = r.end - 1
        if (js.getOrNull(open) != '(' || brackets.close[open] < 0) continue
        val link = "${Docs.ACTIONS}#${spec.name.lowercase()}"
        val parameters = spec.parameters
        val pieces = brackets.pieces(open)
        if (pieces.any { js.startsWith("...", it.first) }) continue
        val required = parameters.count { !it.endsWith("?") }
        if (pieces.size < required) {
            issue(
                r.start,
                brackets.close[open] + 1,
                "@${spec.name} needs ${count(required)}, as in ${spec.signature}, and is given ${pieces.size}.",
                "action-arity",
                link,
            )
        } else if (pieces.size > parameters.size) {
            issue(
                pieces[parameters.size].first,
                pieces.last().last + 1,
                "@${spec.name} takes at most ${count(parameters.size)}, as in ${spec.signature}, and Datastar ignores the rest.",
                "action-arity",
                link,
            )
        }
        for ((n, piece) in pieces.withIndex()) {
            val kind = spec.kinds.getOrNull(n) ?: break
            val v = brackets.value(piece)
            val parameter = parameters[n].removeSuffix("?")
            val takes = "@${spec.name} takes ${kind.phrase()} as $parameter, and "
            when {
                !kind.takes(v) -> {
                    issue(v.start, v.end, takes + wrong(kind, v, spec.pro, filterChanges = true), "action-argument", link)
                }

                spec.kind == "backend" && n == 0 && v.text == "" -> {
                    issue(
                        v.start,
                        v.end,
                        "$takes${shown(v)} is empty, so Datastar throws FetchNoUrlProvided and sends nothing.",
                        "action-argument",
                        link,
                    )
                }

                kind == ArgumentKind.OPTIONS && v.written == Written.OBJECT -> {
                    judgeObject(
                        v.start,
                        catalog.fetchOptions,
                        { "@${spec.name} has no option $it, so Datastar ignores it." },
                        "${Docs.ACTIONS}#options",
                    )
                }

                kind == ArgumentKind.FILTER && v.written == Written.OBJECT -> {
                    judgeObject(v.start, catalog.signalFilter, filterKey, link)
                }
            }
        }
    }
    return out
}

private fun count(n: Int): String = if (n == 1) "1 argument" else "$n arguments"
