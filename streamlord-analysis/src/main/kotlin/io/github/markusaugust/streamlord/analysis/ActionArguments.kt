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
    THROWS("a BigInt under a unary plus, which throws a TypeError before the action is called"),
    EXPRESSION(""),
}

/** A value from [start] to [end] of the script: what it is, and a string's text where it has no escape. */
internal class Value(
    val start: Int,
    val end: Int,
    val written: Written,
    val text: String?,
)

/** The longest value and the longest key a message shows as written. */
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

/** The words that are a value as written; `this` and any other name compute. */
private val WORDS = mapOf("true" to Written.BOOLEAN, "false" to Written.BOOLEAN, "null" to Written.NULL, "undefined" to Written.UNDEFINED)

/** The kinds a literal written so is one of, besides [ArgumentKind.ANY]. */
private val TAKEN =
    mapOf(
        Written.STRING to setOf(ArgumentKind.STRING, ArgumentKind.PATTERN),
        Written.NUMBER to setOf(ArgumentKind.NUMBER, ArgumentKind.BOOLEAN),
        Written.BOOLEAN to setOf(ArgumentKind.BOOLEAN),
        Written.OBJECT to setOf(ArgumentKind.OBJECT, ArgumentKind.OPTIONS, ArgumentKind.FILTER),
        Written.REGEX to setOf(ArgumentKind.PATTERN),
        Written.FUNCTION to setOf(ArgumentKind.FUNCTION),
        Written.THROWS to emptySet(),
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
 * The judging of the literals of one script, [js], by the tree of what was parsed of it; [at] says where in [js] an
 * offset of the parsed text stands, and [span] maps a stretch of [js] back to the text.
 */
private class Judge(
    val catalog: Catalog,
    val js: String,
    val at: (Int) -> Int,
    val span: (Int, Int) -> Pair<Int, Int>,
) {
    /** What [node] is: a literal, a function, or an expression, which only running it tells. */
    fun value(node: JsNode): Value {
        val s = at(node.start)
        val e = at(node.end)
        val written =
            when (node.type) {
                JsKind.STRING -> Written.STRING
                JsKind.TEMPLATE -> if (node.items.isEmpty()) Written.STRING else Written.EXPRESSION
                JsKind.REGEX -> Written.REGEX
                JsKind.NUMBER, JsKind.UNARY -> signed(node)
                JsKind.WORD, JsKind.IDENT -> WORDS[node.name] ?: Written.EXPRESSION
                JsKind.OBJECT -> Written.OBJECT
                JsKind.ARRAY -> Written.ARRAY
                JsKind.FUNCTION, JsKind.ARROW -> Written.FUNCTION
                else -> Written.EXPRESSION
            }
        // A value a Kotlin template was read in is the code's, not written; an object or an array literal is written
        // whatever a template inside it computes, and each of its values is judged on its own.
        val literal = written != Written.EXPRESSION && written != Written.OBJECT && written != Written.ARRAY
        if (literal && PLACEHOLDER in js.substring(s, e)) return Value(s, e, Written.EXPRESSION, null)
        val content = if (written == Written.STRING) js.substring(s + 1, e - 1).takeIf { '\\' !in it } else null
        return Value(s, e, written, content)
    }

    /** A number under any signs: a BigInt under a `+` throws, and a sign before anything else computes. */
    private fun signed(node: JsNode): Written {
        var operand = node
        var plus = false
        while (operand.type == JsKind.UNARY && (operand.name == "+" || operand.name == "-")) {
            plus = plus || operand.name == "+"
            operand = operand.inner!!
        }
        if (operand.type != JsKind.NUMBER) return Written.EXPRESSION
        return if (plus && js[at(operand.end) - 1] == 'n') Written.THROWS else Written.NUMBER
    }

    val out = ArrayList<Issue>()
    val filterKey = { key: String ->
        "A signal filter has only ${catalog.filterFields.joinToString(" and ") { it.name }}, so Datastar ignores $key."
    }

    fun issue(
        from: Int,
        to: Int,
        message: String,
        code: IssueCode,
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

                // A Pro action's code is not read here, so what it does with the value is not said; a value that throws never reaches it.
                !known || v.written == Written.THROWS -> {
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

    /** The keys of the object literal [node], held to [fields]; [unknown] says what Datastar does with a key that is none. */
    fun judgeObject(
        node: JsNode,
        fields: List<ValueField>,
        unknown: (String) -> String,
        link: String,
    ) {
        val byName = fields.associateBy { it.name }
        for (property in node.items) {
            val named = property?.key
            // A spread, a computed key, a number, a method and an accessor are not judged.
            if (named?.name == null || (!property.plain && property.inner?.type != JsKind.IDENT)) continue
            val keyRange = at(named.start) until at(named.end)
            if (keyRange.last - keyRange.first >= SHORT) continue
            val keyText = js.substring(keyRange.first, keyRange.last + 1)
            val quoted = !named.isIdent
            val key = if (quoted) keyText.substring(1, keyText.length - 1) else keyText
            // A key written with an escape and a key the code writes are not judged.
            if ('\\' in keyText || PLACEHOLDER in key) continue
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
                    IssueCode.UNKNOWN_OPTION,
                    link,
                    listOfNotNull(near?.let { Fix("Change to $it", inner, inner + key.length, it) }),
                )
                continue
            }
            if (!property.plain) continue
            val v = value(property.inner!!)
            val enum = field.values.isNotEmpty()
            when {
                // The client compares the option with each of its strings, so any other literal takes the branch of a string it never compares with.
                enum && v.written !in setOf(Written.EXPRESSION, Written.UNDEFINED) &&
                    (v.written != Written.STRING || (v.text != null && v.text !in field.values)) -> {
                    issue(
                        v.start,
                        v.end,
                        "${field.name} takes ${field.type}, and with ${shown(v)} ${field.otherwise}.",
                        IssueCode.OPTION_VALUE,
                        link,
                    )
                }

                !field.kind.takes(v) || v.written == Written.NULL -> {
                    wrong(field.kind, v, field.otherwise, filterChanges = false, url = false)?.let {
                        issue(v.start, v.end, "${field.name} takes ${field.kind.phrase()}, and $it", IssueCode.OPTION_VALUE, link)
                    }
                }

                field.kind == ArgumentKind.FILTER && v.written == Written.OBJECT -> {
                    judgeObject(property.inner!!, catalog.filterFields, filterKey, link)
                }
            }
        }
    }
}

/**
 * The warnings for the arguments of every action called in [js], which the client rewrites to [rewritten] and
 * [parsed] is the tree of; [span] maps a stretch of [js] back to the text.
 */
@OptIn(InternalStreamlordApi::class)
internal fun actionArgumentIssues(
    catalog: Catalog,
    js: String,
    rewritten: ClientRewrite,
    parsed: Parsed,
    span: (Int, Int) -> Pair<Int, Int>,
): List<Issue> {
    // Where the client wrote each call of an action it knows the parameters of, in the text that was parsed.
    val written = HashMap<Int, String>()
    val at = rewritten.writtenAt()
    for ((n, r) in rewritten.rewrites.withIndex()) {
        if (!r.signal && catalog.actionKinds[r.name]?.isNotEmpty() == true) written[at[n]] = r.name
    }
    if (written.isEmpty()) return emptyList()
    val judge = Judge(catalog, js, rewritten::toExpression, span)
    with(judge) {
        // A call in a comment, a string or a regular expression is no node, and a call of what the action gives,
        // @get('/x').then(f), starts where the action does and calls no name; the calls come as the text has them.
        val calls = parsed.nodes.filter { it.type == JsKind.CALL && it.inner?.type == JsKind.IDENT && it.start in written }
        for (call in calls.sortedBy { it.start }) {
            val name = written.getValue(call.start)
            val spec = catalog.actionsByName.getValue(name)
            val kinds = catalog.actionKinds.getValue(name)
            val values = catalog.actionValues[name].orEmpty()
            val link = "${Docs.ACTIONS}#${spec.name.lowercase()}"
            val parameters = spec.parameters
            // The client hands the dispatcher the action's name and the event before what the author wrote.
            val pieces = call.items.drop(2).filterNotNull()
            if (pieces.any { it.type == JsKind.SPREAD }) continue
            val required = parameters.count { !it.endsWith("?") }
            if (pieces.size < required) {
                issue(
                    at(call.start),
                    at(call.end),
                    "@${spec.name} needs ${count(required)}, as in ${spec.signature}, and is given ${pieces.size}.",
                    IssueCode.ACTION_ARITY,
                    link,
                )
            } else if (pieces.size > parameters.size) {
                issue(
                    at(pieces[parameters.size].outerStart),
                    at(pieces.last().outerEnd),
                    "@${spec.name} takes at most ${count(parameters.size)}, as in ${spec.signature}, and Datastar ignores the rest.",
                    IssueCode.ACTION_ARITY,
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
                        )?.let { issue(v.start, v.end, takes + it, IssueCode.ACTION_ARGUMENT, link) }
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
                            IssueCode.ACTION_ARGUMENT,
                            link,
                            listOfNotNull(near?.let { Fix("Change to '$it'", v.start, v.end, js[v.start] + it + js[v.start]) }),
                        )
                    }

                    kind == ArgumentKind.OPTIONS && v.written == Written.OBJECT -> {
                        judgeObject(
                            piece,
                            catalog.optionFields,
                            { "@${spec.name} has no option $it, so Datastar ignores it." },
                            "${Docs.ACTIONS}#options",
                        )
                    }

                    kind == ArgumentKind.FILTER && v.written == Written.OBJECT -> {
                        judgeObject(piece, catalog.filterFields, filterKey, link)
                    }
                }
            }
        }
    }
    return judge.out
}

/**
 * The warnings for [js], the value of the attribute [name] that the client hands to `filtered`, judged as a signal filter is
 * in `filterSignals`; [parsed] is what [JsParser.expression] read of it, and [link] the attribute's documentation.
 */
internal fun filterValueIssues(
    catalog: Catalog,
    js: String,
    parsed: Parsed,
    span: (Int, Int) -> Pair<Int, Int>,
    name: String,
    link: String,
): List<Issue> {
    val root = parsed.nodes.lastOrNull() ?: return emptyList()
    val judge = Judge(catalog, js, { it }, span)
    with(judge) {
        val v = value(root)
        if (!ArgumentKind.FILTER.takes(v) || v.written == Written.NULL) {
            wrong(ArgumentKind.FILTER, v, "", filterChanges = false, url = false)?.let {
                issue(v.start, v.end, "$name takes ${ArgumentKind.FILTER.phrase()}, and $it", IssueCode.OPTION_VALUE, link)
            }
        } else if (v.written == Written.OBJECT) {
            judgeObject(root, catalog.filterFields, filterKey, link)
        }
    }
    return judge.out
}

private fun count(n: Int): String = if (n == 1) "1 argument" else "$n arguments"
