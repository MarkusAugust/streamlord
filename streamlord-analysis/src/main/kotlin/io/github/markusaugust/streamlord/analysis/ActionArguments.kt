package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.InternalStreamlordApi
import io.github.markusaugust.streamlord.core.client.ClientRewrite
import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.domain.ElementsGuard

/*
 * The arguments of each action call, and the options and filters written in them, held to the
 * kinds the catalog takes from the client: one table, read for every action alike. Only a literal
 * is judged; a value computed by an expression is the author's.
 */

/** What a literal written as an argument or a value is; [EXPRESSION] is anything else, and never judged. */
internal enum class Written(
    val phrase: String,
) {
    STRING("a string"),
    NUMBER("a number"),
    BOOLEAN("a boolean"),
    NULL("none"),
    UNDEFINED(""),
    OBJECT("an object"),
    ARRAY("an array"),
    REGEX("a regular expression"),
    FUNCTION("a function"),
    EXPRESSION(""),
}

/** A value from [start] to [end] of the script: what it is, a string's text where it has no escape, and what the parser read of it. */
internal class Value(
    val start: Int,
    val end: Int,
    val written: Written,
    val text: String?,
    val parsed: JsValue? = null,
)

/** The longest number literal read. */
private const val SHORT = 40

// What JavaScript's Number reads as a number: white space alone is 0, and a sign is no part of a hexadecimal, octal or binary one.
private val NUMERIC_STRING =
    regex("""\s*(?:[+-]?(?:Infinity|(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)|0[xX][0-9a-fA-F]+|0[oO][0-7]+|0[bB][01]+)?\s*""")
private val IDENTIFIER = regex("""[A-Za-z_$][\w$]*""")

internal fun ArgumentKind.phrase(): String =
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

/** The kinds a literal written so is one of, besides [ArgumentKind.ANY]. */
private val TAKEN =
    mapOf(
        Written.STRING to setOf(ArgumentKind.STRING, ArgumentKind.PATTERN),
        Written.NUMBER to setOf(ArgumentKind.NUMBER, ArgumentKind.BOOLEAN),
        Written.BOOLEAN to setOf(ArgumentKind.BOOLEAN),
        Written.OBJECT to setOf(ArgumentKind.OBJECT, ArgumentKind.OPTIONS, ArgumentKind.FILTER),
        Written.REGEX to setOf(ArgumentKind.PATTERN),
        Written.FUNCTION to setOf(ArgumentKind.FUNCTION),
    )

/**
 * Whether a value written so is one of [kind]; an expression always is, and a string Number reads
 * as a number is one, as the client counts with it. Null is judged apart, where the client throws on it.
 */
private fun ArgumentKind.takes(value: Value): Boolean {
    if (value.written == Written.NULL) return true
    val taken = TAKEN[value.written] ?: return value.written != Written.ARRAY || this == ArgumentKind.ANY
    return this == ArgumentKind.ANY || this in taken ||
        (
            this == ArgumentKind.NUMBER && value.written == Written.STRING &&
                (value.text == null || NUMERIC_STRING.matchEntire(value.text) != null)
        )
}

/**
 * What [v], a value [JsParser] read, is, with its offsets in the script: [at] maps an offset of the
 * text the parser read back to it. A long number is not judged, nor anything a Kotlin template
 * computes.
 */
private fun Judge.value(v: JsValue): Value {
    val s = at(v.start)
    val e = at(v.end)
    if (s >= e) return Value(s, e, Written.EXPRESSION, null)
    val short = e - s <= SHORT
    val written =
        when (v.kind) {
            JsKind.STRING -> Written.STRING

            // A template literal with a ${...} holds code.
            JsKind.TEMPLATE -> if (v.code) Written.EXPRESSION else Written.STRING

            JsKind.REGEX -> Written.REGEX

            JsKind.NUMBER -> if (short) Written.NUMBER else Written.EXPRESSION

            JsKind.BOOLEAN -> Written.BOOLEAN

            JsKind.NULL -> Written.NULL

            JsKind.NAME -> if (js.substring(s, e) == "undefined") Written.UNDEFINED else Written.EXPRESSION

            JsKind.OBJECT -> Written.OBJECT

            JsKind.ARRAY -> Written.ARRAY

            JsKind.FUNCTION -> Written.FUNCTION

            else -> Written.EXPRESSION
        }
    // An object or an array literal is written whatever a template inside it computes: each of its values is judged on its own.
    if (written != Written.OBJECT && written != Written.ARRAY && computed(s, e)) return Value(s, e, Written.EXPRESSION, null)
    val content = if (written == Written.STRING) js.substring(s + 1, e - 1).takeIf { '\\' !in it } else null
    return Value(s, e, written, content, v)
}

/**
 * The judging of the literals of one script, [js]: [at] maps an offset of the text the parser read
 * to one of [js], and [span] a stretch of [js] back to the text.
 */
private class Judge(
    val catalog: Catalog,
    val js: String,
    val at: (Int) -> Int,
    val span: (Int, Int) -> Pair<Int, Int>,
) {
    val out = ArrayList<Issue>()

    /** How many of the places a Kotlin template was read at start before each offset. */
    private val templates =
        IntArray(js.length + 1).also { counts ->
            var next = js.indexOf(PLACEHOLDER)
            var seen = 0
            for (i in js.indices) {
                if (i == next) {
                    seen++
                    next = js.indexOf(PLACEHOLDER, i + 1)
                }
                counts[i + 1] = seen
            }
        }

    /** Whether a Kotlin template was read somewhere from [from] until [to]: the value is the code's, not written. */
    fun computed(
        from: Int,
        to: Int,
    ): Boolean = to - from >= PLACEHOLDER.length && templates[to - PLACEHOLDER.length + 1] > templates[from]

    val filterKey = { key: String ->
        "A signal filter has only ${catalog.filterFields.joinToString(" and ") { it.name }}, so Datastar ignores $key."
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

    fun shown(v: Value): String = js.substring(v.start, v.end).takeIf { it.length <= SHORT && '\n' !in it } ?: "this value"

    /**
     * What a value of [kind] written as [v] is, where it is not one, and what the client then does:
     * [otherwise] for a number that is NaN, [url] for the address of a backend action.
     */
    fun wrong(
        kind: ArgumentKind,
        v: Value,
        otherwise: String,
        filterChanges: Boolean,
        url: Boolean,
        known: Boolean = true,
    ): String? {
        val nan = kind == ArgumentKind.NUMBER && v.written in setOf(Written.STRING, Written.OBJECT, Written.REGEX, Written.FUNCTION)
        val what =
            when {
                v.written == Written.NULL -> "none"
                url && v.text == "" -> "empty"
                url && v.written == Written.ARRAY -> "an empty array"
                nan -> v.written.phrase + " Datastar reads as NaN"
                else -> v.written.phrase
            }
        val reads = if (kind == ArgumentKind.OPTIONS) "them" else "it"
        val then =
            when {
                url && (
                    v.text == "" || v.written in
                        setOf(
                            Written.NULL,
                            Written.NUMBER,
                            Written.BOOLEAN,
                            Written.OBJECT,
                            Written.REGEX,
                            Written.ARRAY,
                        )
                ) -> {
                    ", so Datastar throws FetchNoUrlProvided and sends nothing"
                }

                v.written == Written.NULL && kind in setOf(ArgumentKind.OPTIONS, ArgumentKind.FILTER) -> {
                    ", so Datastar throws a TypeError as it reads $reads"
                }

                v.written == Written.NULL && kind !in setOf(ArgumentKind.PATTERN, ArgumentKind.FUNCTION) -> {
                    return null
                }

                nan && otherwise.isNotEmpty() -> {
                    ", so $otherwise"
                }

                // A Pro action's code is not read here, so what it does with the value is not said.
                !known -> {
                    ""
                }

                // The client counts with it, and JavaScript counts true as 1.
                kind == ArgumentKind.NUMBER && v.written == Written.BOOLEAN -> {
                    ", which Datastar counts as ${if (js.substring(v.start, v.end) == "true") 1 else 0}"
                }

                // Object.assign copies a string's characters and an array's items under their places, and nothing of the rest.
                kind == ArgumentKind.OBJECT && v.written == Written.STRING -> {
                    if (v.text ==
                        ""
                    ) {
                        ", from which Datastar reads no key"
                    } else {
                        ", whose characters Datastar reads as keys named by their places, 0, 1 and on"
                    }
                }

                kind == ArgumentKind.OBJECT && v.written == Written.ARRAY -> {
                    ", whose items Datastar reads as keys named by their places, 0, 1 and on"
                }

                kind == ArgumentKind.OBJECT && v.written != Written.NULL -> {
                    ", from which Datastar reads no key"
                }

                kind == ArgumentKind.BOOLEAN -> {
                    ", which Datastar reads as ${v.text != ""}"
                }

                kind == ArgumentKind.OPTIONS -> {
                    ", so Datastar reads every option at its default"
                }

                kind == ArgumentKind.FILTER -> {
                    ", so Datastar reads no filter from it" + if (filterChanges) " and changes every signal" else ""
                }

                kind == ArgumentKind.FUNCTION -> {
                    ", so Datastar throws a TypeError as it calls it"
                }

                kind == ArgumentKind.PATTERN -> {
                    ", so Datastar throws a TypeError as it matches a signal"
                }

                else -> {
                    ""
                }
            }
        return "${shown(v)} is $what$then."
    }

    /** The keys of the object literal [o], held to [fields]; [unknown] says what Datastar does with a key that is none. */
    fun judgeObject(
        o: JsValue,
        fields: List<ValueField>,
        unknown: (String) -> String,
        link: String,
    ) {
        val byName = fields.associateBy { it.name }
        for (p in o.properties) {
            if (!p.named) continue
            val keyStart = at(p.keyStart)
            val keyEnd = at(p.keyEnd)
            if (keyEnd - keyStart > SHORT) continue
            val keyText = js.substring(keyStart, keyEnd)
            val key = if (p.quoted) keyText.substring(1, keyText.length - 1) else keyText
            // A key with an escape or a quote in it, one not written as a plain name and one the code writes are not judged.
            val written = if (p.quoted) '\\' !in key && key.none { it == keyText[0] } else IDENTIFIER.matchEntire(key) != null
            if (!written || PLACEHOLDER in key) continue
            val field = byName[key]
            if (field == null) {
                val near =
                    fields.map { it.name }.singleOrNull {
                        ElementsGuard.oneEditAway(key, it, swaps = true) ||
                            it.equals(key, ignoreCase = true)
                    }
                val inner = if (p.quoted) keyStart + 1 else keyStart
                issue(
                    keyStart,
                    keyEnd,
                    unknown(key) + (near?.let { " Did you mean $it?" } ?: ""),
                    "unknown-option",
                    link,
                    listOfNotNull(near?.let { Fix("Change to $it", inner, inner + key.length, it) }),
                )
                continue
            }
            val v = value(p.value ?: continue)
            val enum = field.values.isNotEmpty()
            when {
                // The client compares the option with each of its strings, so any other literal takes the branch of a string it never compares with.
                enum && v.written !in setOf(Written.EXPRESSION, Written.UNDEFINED) &&
                    (v.written != Written.STRING || (v.text != null && v.text !in field.values)) -> {
                    issue(
                        v.start,
                        v.end,
                        "${field.name} takes ${field.type}, and with ${shown(v)} ${field.otherwise}.",
                        "option-value",
                        link,
                    )
                }

                !field.kind.takes(v) || v.written == Written.NULL -> {
                    wrong(field.kind, v, field.otherwise, filterChanges = false, url = false)?.let {
                        issue(v.start, v.end, "${field.name} takes ${field.kind.phrase()}, and $it", "option-value", link)
                    }
                }

                field.kind == ArgumentKind.FILTER && v.written == Written.OBJECT -> {
                    judgeObject(v.parsed!!, catalog.filterFields, filterKey, link)
                }
            }
        }
    }
}

/**
 * The warnings for the arguments of every action called in [js], read from [calls], the calls
 * [JsParser] read in [rewritten], the script as the client compiles it: each action is a call of
 * its dispatcher there, `__action("name",evt,...)`. [span] maps a stretch of [js] back to the text.
 */
@OptIn(InternalStreamlordApi::class)
internal fun actionArgumentIssues(
    catalog: Catalog,
    js: String,
    rewritten: ClientRewrite,
    calls: List<JsCall>,
    span: (Int, Int) -> Pair<Int, Int>,
): List<Issue> {
    val actions = rewritten.rewrites.filter { !it.signal && catalog.actionKinds[it.name]?.isNotEmpty() == true }.associateBy { it.start }
    if (actions.isEmpty()) return emptyList()
    // An offset of the rewritten script, each character of a rewrite standing for where it began, and an end for the end.
    val at = { offset: Int -> if (offset >= rewritten.js.length) js.length else rewritten.toExpression(offset) }
    val judge = Judge(catalog, js, at, span)
    with(judge) {
        for (call in calls) {
            if (call.callee != DISPATCHER) continue
            val r = actions[at(call.start)] ?: continue
            val spec = catalog.actionsByName.getValue(r.name)
            val kinds = catalog.actionKinds.getValue(r.name)
            val values = catalog.actionValues[r.name].orEmpty()
            val link = "${Docs.ACTIONS}#${spec.name.lowercase()}"
            val parameters = spec.parameters
            // The dispatcher is handed the action's name and the event before the action's own arguments.
            val pieces = call.arguments.drop(2)
            if (pieces.any { it.kind == JsKind.SPREAD }) continue
            val required = parameters.count { !it.endsWith("?") }
            if (pieces.size < required) {
                issue(
                    r.start,
                    at(call.end),
                    "@${spec.name} needs ${count(required)}, as in ${spec.signature}, and is given ${pieces.size}.",
                    "action-arity",
                    link,
                )
            } else if (pieces.size > parameters.size) {
                issue(
                    at(pieces[parameters.size].start),
                    at(pieces.last().end),
                    "@${spec.name} takes at most ${count(parameters.size)}, as in ${spec.signature}, and Datastar ignores the rest.",
                    "action-arity",
                    link,
                )
            }
            for ((n, piece) in pieces.withIndex()) {
                val kind = kinds.getOrNull(n) ?: break
                if (kind == ArgumentKind.ANY) continue
                val v = value(piece)
                // A Pro action's string is what the browser makes of a number, which it takes whole.
                if (spec.pro && kind == ArgumentKind.STRING && v.written == Written.NUMBER) continue
                val parameter = parameters[n].removeSuffix("?")
                // A parameter the signature names is named; one it writes out, as @peek(() => expr), is shown in it.
                val takes =
                    "@${spec.name} takes ${kind.phrase()}" +
                        (if (IDENTIFIER.matchEntire(parameter) != null) " as $parameter" else ", as in ${spec.signature}") + ", and "
                val url = spec.kind == "backend" && n == 0
                // new URL() writes an array as its items joined by commas, and only an empty one has no length.
                if (url && v.written == Written.ARRAY && js.substring(v.start + 1, v.end - 1).isNotBlank()) continue
                when {
                    !kind.takes(v) || v.written == Written.NULL || (url && v.text == "") -> {
                        wrong(
                            kind,
                            v,
                            "",
                            filterChanges = true,
                            url = url,
                            known = !spec.pro,
                        )?.let { issue(v.start, v.end, takes + it, "action-argument", link) }
                    }

                    v.written == Written.STRING && v.text != null && values.getOrNull(n).let { !it.isNullOrEmpty() && v.text !in it } -> {
                        val named = values[n]
                        val near =
                            named.singleOrNull {
                                it.equals(v.text, ignoreCase = true) ||
                                    ElementsGuard.oneEditAway(v.text, it, swaps = true)
                            }
                        // A Pro action's values come from its reference page alone, which is all the message can claim.
                        val source = if (spec.pro) ", as the reference names them" else ""
                        issue(
                            v.start,
                            v.end,
                            "@${spec.name} takes one of ${named.joinToString(
                                ", ",
                            ) { "'$it'" }} as $parameter$source, and ${shown(v)} is none of them." +
                                (near?.let { " Did you mean '$it'?" } ?: ""),
                            "action-argument",
                            link,
                            listOfNotNull(near?.let { Fix("Change to '$it'", v.start, v.end, js[v.start] + it + js[v.start]) }),
                        )
                    }

                    kind == ArgumentKind.OPTIONS && v.written == Written.OBJECT -> {
                        judgeObject(
                            v.parsed!!,
                            catalog.optionFields,
                            { "@${spec.name} has no option $it, so Datastar ignores it." },
                            "${Docs.ACTIONS}#options",
                        )
                    }

                    kind == ArgumentKind.FILTER && v.written == Written.OBJECT -> {
                        judgeObject(v.parsed!!, catalog.filterFields, filterKey, link)
                    }
                }
            }
        }
    }
    return judge.out
}

/** The function the client calls every action through. */
private const val DISPATCHER = "__action"

/**
 * The warnings for [js], the value of the attribute [name] that the client hands to `filtered`, judged as a signal filter is
 * in `filterSignals`; [link] is the attribute's documentation.
 */
internal fun filterValueIssues(
    catalog: Catalog,
    js: String,
    span: (Int, Int) -> Pair<Int, Int>,
    name: String,
    link: String,
): List<Issue> {
    val parsed = JsParser.value(js) ?: return emptyList()
    val judge = Judge(catalog, js, { it }, span)
    with(judge) {
        val v = value(parsed)
        if (!ArgumentKind.FILTER.takes(v) || v.written == Written.NULL) {
            wrong(ArgumentKind.FILTER, v, "", filterChanges = false, url = false)?.let {
                issue(v.start, v.end, "$name takes ${ArgumentKind.FILTER.phrase()}, and $it", "option-value", link)
            }
        } else if (v.written == Written.OBJECT) {
            judgeObject(parsed, catalog.filterFields, filterKey, link)
        }
    }
    return judge.out
}

private fun count(n: Int): String = if (n == 1) "1 argument" else "$n arguments"
