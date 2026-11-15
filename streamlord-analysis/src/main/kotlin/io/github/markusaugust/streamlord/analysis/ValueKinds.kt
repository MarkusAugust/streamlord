package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.groupRange
import io.github.markusaugust.streamlord.core.client.regex
import kotlin.concurrent.Volatile

/*
 * What the text shows a Kotlin value written into HTML to be: the author's own, a String, a number,
 * markup or Trusted, by its literal, its call, its shape or its declaration.
 */

// The callee is Trusted or an alias of it, which the caller has read.
private val TRUSTED_LITERAL =
    regex(
        "(`?)[\\p{L}_][\\p{L}\\p{Nd}_]*\\1\\s*\\(\\s*(?:html\\s*=\\s*)?(?:\"((?:[^\"\\\\$\\n]|\\\\.)*)\"|\"\"\"((?:[^\"$]|\"(?!\"\"))*)\"\"\")\\s*\\)",
    )
private val KOTLIN_ESCAPE = regex("""\\(u[0-9A-Fa-f]{4}|.)""")

/**
 * The text of `Trusted("...")` at [at], or of `Trusted("""...""")` or `Trusted(html = ...)`, with
 * a literal that holds no template, as the Kotlin compiler reads it; null for anything else.
 */
internal fun literalTrusted(
    src: String,
    at: Int,
    whole: Boolean = false,
): String? {
    if (at < 0) return null
    val m = TRUSTED_LITERAL.matchAt(src, at) ?: return null
    if (whole && m.range.last + 1 != src.length) return null
    // A raw string has no escapes.
    m.groups[3]?.let { return it.value }
    return KOTLIN_ESCAPE.replace(m.groupValues[2]) {
        val e = it.groupValues[1]
        when {
            e.length == 5 -> {
                e
                    .substring(1)
                    .toInt(16)
                    .toChar()
                    .toString()
            }

            e == "t" -> {
                "\t"
            }

            e == "b" -> {
                "\b"
            }

            e == "n" -> {
                "\n"
            }

            e == "r" -> {
                "\r"
            }

            else -> {
                e
            }
        }
    }
}

/**
 * The text a Trusted value writes, when the source shows it: `Trusted("...")` with a literal that
 * holds no template, or a `val` set to one. Null for any other value, `Trusted(name)` among them,
 * since the value of a `const val` is not followed.
 */
internal fun trustedLiteral(
    src: String,
    value: String,
    at: Int,
): String? {
    val t = value.trim()
    if (KOTLIN_NAME.matchEntire(t) != null) return declarationOf(src, t, at)?.takeIf { setToCore(src, it) }?.trustedText
    return literalTrusted(asCoreCall(src, t, "Trusted") ?: return null, 0, whole = true)
}

/** Whether [name] at [at] is declared a `Trusted`, or set to `Trusted(...)` and nothing more: what interpolate lets in anywhere. */
internal fun trustedName(
    src: String,
    name: String,
    at: Int,
): Boolean {
    val d = declarationOf(src, name, at) ?: return false
    val type = d.type
    if (type != null && isTrustedType(coreType(src, type))) return !markupTypeShadowed(src, type)
    return d.trusted && setToCore(src, d)
}

/**
 * Whether a value can be null as far as the text shows: `null` itself, a name declared with a
 * `?` type, or a safe call with no `?:` after it. A template writes null as `null`, and
 * `interpolate` writes nothing.
 */
internal fun mayBeNull(
    src: String,
    value: String,
    at: Int,
): Boolean =
    (branches(value) ?: listOf(value)).any { v ->
        val t = v.trim()
        when {
            t == "null" -> {
                true
            }

            KOTLIN_NAME.matchEntire(t) != null -> {
                declarationOf(src, t, at)?.type?.let { it.endsWith("?") || nullableTypeParameter(src, it) } ==
                    true
            }

            else -> {
                "?." in t && "?:" !in t
            }
        }
    }

private val TYPE_PARAMETERS = regex("""(?:\bfun\s*|\b(?:class|interface)\s+[\p{L}_][\p{L}\p{Nd}_]*\s*)<((?:[^<>]|<[^<>]*>)*)>""")
private val TYPE_PARAMETER =
    regex("""(?s)^\s*(?:(?:in|out|reified)\s+)*([\p{L}_][\p{L}\p{Nd}_]*)\s*(?::\s*(.+?))?\s*$""")

/**
 * Whether [type] is a type parameter a value of which may be null: `T` of `fun <T> f(q: T)`,
 * whose bound is `Any?`, and one bound by a nullable type. `T : Any` is not.
 */
private fun nullableTypeParameter(
    src: String,
    type: String,
): Boolean {
    val facts = fileFacts(src)
    val known =
        facts.typeParameters ?: HashMap<String, Boolean>().also { out ->
            for (m in TYPE_PARAMETERS.findAll(src)) {
                if (!facts.code[m.range.first]) continue
                for (p in splitTopLevel(m.groupValues[1])) {
                    val d = TYPE_PARAMETER.find(p) ?: continue
                    val bound = d.groups[2]?.value?.trim()
                    out[d.groupValues[1]] = out[d.groupValues[1]] == true || bound == null || bound.endsWith("?")
                }
            }
            facts.typeParameters = out
        }
    return known[type] == true
}

/** One value a placeholder can result in, and whether it stands left of `?:`, which never gives null. */
internal class TemplateValue(
    val text: String,
    val nonNull: Boolean,
)

/** The values a placeholder can result in: each branch of an `if` or a `when`, each side of `?:`. */
internal fun templateValues(expr: String): List<TemplateValue> {
    val t = unparen(expr)
    branches(t)?.let { b -> return b.flatMap { templateValues(it) } }
    val sides = splitTop(t, "?:")
    if (sides.size > 1) {
        return sides.flatMapIndexed { k, v -> templateValues(v).map { TemplateValue(it.text, it.nonNull || k < sides.size - 1) } }
    }
    return listOf(TemplateValue(t, false))
}

/** The expression without the brackets around all of it. */
internal fun unparen(expr: String): String {
    var t = expr.trim()
    while (t.startsWith("(") && closing(t, depths(t), 0) == t.length) t = t.substring(1, t.length - 1).trim()
    return t
}

private val OPERATOR_BEFORE = regex("""[-+*/%=<>!&|?:(,]$""")

/** [e] cut at each [op] outside brackets and literals; a `+` that is part of `++` or `+=`, or a sign, does not cut. */
internal fun splitTop(
    e: String,
    op: String,
): List<String> {
    val d = depths(e)
    val out = ArrayList<String>()
    var from = 0
    var k = 0
    while (k < e.length) {
        if (d[k] != 0 || !e.startsWith(op, k)) {
            k++
            continue
        }
        if (op == "+") {
            val before = e.substring(from, k).trim()
            if (e.getOrNull(k + 1) == '+' || e.getOrNull(k + 1) == '=' || e.getOrNull(k - 1) == '+' || before.isEmpty() ||
                OPERATOR_BEFORE.containsMatchIn(before)
            ) {
                k++
                continue
            }
        }
        out += e.substring(from, k)
        from = k + op.length
        k += op.length
    }
    out += e.substring(from)
    return out
}

private val CALL_START = regex("""^([\p{L}_][\p{L}\p{Nd}_]*)\s*\(""")

/** The calls of Streamlord's DSL that a placeholder may hold: `signal("count")`, `post("/x")`, each returning an expression the author wrote. */
private val VALUE_HELPERS: Set<String> =
    EXPRESSION_HELPERS +
        Catalog.default.actions
            .map { it.kotlin }
            .filter { it.isNotEmpty() } + "statements" + "js"

/**
 * The arguments of [t] when it is one call to a helper of Streamlord's DSL, in a file that uses
 * the DSL and imports no other function of that name; null when it is not one.
 */
private fun helperArguments(
    src: String,
    t: String,
    sdk: FileModules,
): List<String>? {
    val m = CALL_START.find(t) ?: return null
    val name = m.groupValues[1]
    val facts = fileFacts(src)
    if (!(sdk.htmlDsl ?: facts.usesDsl)) return null
    val helpers = facts.helpers ?: unshadowed(src, VALUE_HELPERS).also { facts.helpers = it }
    if (name !in helpers || !wholeCall(t, name)) return null
    val args = t.substring(m.value.length, t.length - 1)
    return if (args.isBlank()) emptyList() else splitTop(args, ",")
}

/** A name given to an argument, `value = `, which says nothing of the value. */
private val ARGUMENT_NAME = regex("""^\s*[\p{L}_][\p{L}\p{Nd}_]*\s*=(?!=)""")

/**
 * Whether a helper's argument is the author's own: a literal, a constant, a number or a boolean
 * the file declares, or a helper call whose arguments are. `signal("x;alert(1)")` returns code, so
 * a helper is only as much the author's own as what it is given.
 */
private fun ownArgument(
    src: String,
    arg: String,
    at: Int,
    constants: Set<String>,
    sdk: FileModules,
): Boolean {
    val a = arg.replaceFirst(ARGUMENT_NAME, "").trim()
    if (a.startsWith("*")) return false
    val kind = weakest(templateValues(a).map { valueKind(src, it.text, at, constants, it.nonNull, sdk) })
    return kind.writesItself()
}

private val QUALIFIED = regex("""^((?:[\p{L}_][\p{L}\p{Nd}_]*\s*\.\s*)+)([\p{L}_][\p{L}\p{Nd}_]*)$""")
private val SCREAMING = regex("""[A-Z][A-Z0-9_]*""")

/**
 * A constant reached through what holds it: `Routes.SAVE`, a `const val` of the workspace or of
 * this file in an object or a class, or an enum entry, `Tab.HOME`, whose text is its name. What
 * holds it is a type, whose name starts with a capital, and not a value the file declares.
 */
private fun qualifiedConstant(
    src: String,
    t: String,
    at: Int,
    constants: Set<String>,
): Boolean {
    val m = QUALIFIED.find(t) ?: return false
    val holders =
        m.groupValues[1]
            .split(".")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    if (!holders.last()[0].isUpperCase() || declarationOf(src, holders.first(), at) != null) return false
    val name = m.groupValues[2]
    if (SCREAMING.matchEntire(name) != null) return true
    // The name of an entry, `Tab.HOME.name`, is the name the author gave it: of an enum the file shows, as `.name` of anything
    // else may be any text.
    if (name == "name" && holders.size > 1) return enumEntryShown(src, holders[holders.size - 2], holders.last())
    return name in constants || fileFacts(src).names[name].orEmpty().any { it.constant }
}

/** Whether the code of the file declares `enum class [type]` with the entry [entry]. */
private fun enumEntryShown(
    src: String,
    type: String,
    entry: String,
): Boolean {
    val code = fileFacts(src).code
    val m = regex("""\benum\s+class\s+${Regex.escape(type)}\b""").findAll(src).firstOrNull { code[it.range.first] } ?: return false
    val open = src.indexOf('{', m.range.last)
    if (open < 0) return false
    val entries = src.substring(open + 1).substringBefore(';').substringBefore('}')
    return regex("""(?:^|[\s,])${Regex.escape(entry)}\b""").containsMatchIn(entries)
}

/**
 * What the text shows of one value, a branch of an `if` or a `when` or a whole placeholder: a literal or
 * a constant, the author's own; a string template, as weak as its weakest placeholder; a call to one of
 * Streamlord's expression helpers, the author's code when its arguments are the author's own and
 * a String otherwise; or what the name's declaration says. Null where it shows nothing.
 */
internal fun valueKind(
    src: String,
    value: String,
    at: Int,
    constants: Set<String>,
    nonNull: Boolean = false,
    sdk: FileModules = FileModules.DEFAULT,
): ValueFact? {
    val t = value.trim()
    if (LITERAL.matchEntire(t) != null || STRING_LITERAL.matchEntire(t) != null ||
        CHAR_LITERAL.matchEntire(t) != null
    ) {
        return ValueFact.CONSTANT
    }
    stringLiteral(t)?.let { literal ->
        return weakest(
            literal.interpolations.flatMap { ip ->
                val values = if (ip.kind == InterpolationKind.SIMPLE) listOf(TemplateValue(ip.text, false)) else templateValues(ip.text)
                values.map { valueKind(src, it.text, at, constants, it.nonNull, sdk) }
            },
        )
    }
    helperArguments(src, t, sdk)?.let { args ->
        return if (args.all { ownArgument(src, it, at, constants, sdk) }) ValueFact.CONSTANT else ValueFact.STRING
    }
    // `"ab".repeat(3)`: a plain change to text the author wrote, made with what the author wrote.
    val call = lastCall(t)
    if (call?.receiver != null && call.lambda == null && call.name in TEXT_TRANSFORMS &&
        valueKind(src, call.receiver, at, constants, sdk = sdk).isConstant() &&
        (call.args.isBlank() || splitTop(call.args, ",").all { ownArgument(src, it, at, constants, sdk) })
    ) {
        return ValueFact.CONSTANT
    }
    callKind(src, t, at, constants, sdk)?.let { return it }
    if (qualifiedConstant(src, t, at, constants)) return ValueFact.CONSTANT
    shapeKind(src, t, at, constants, sdk)?.let { return it }
    if (KOTLIN_NAME.matchEntire(t) == null) return null
    if (constantAt(src, t, at, constants)) return ValueFact.CONSTANT
    return declaredKind(src, t, at, nonNull, sdk.core)
}

/** The conversions whose result is a `kotlin.Number` whatever they are called on. */
private val NUMBER_CONVERSIONS = setOf("toLong", "toInt", "toDouble", "toFloat", "toShort", "toByte")

/** The calls on a String whose result is a String, whatever their arguments. */
private val STRING_CHANGES =
    setOf(
        "trim",
        "trimStart",
        "trimEnd",
        "lowercase",
        "uppercase",
        "take",
        "drop",
        "takeLast",
        "dropLast",
        "replace",
        "substring",
        "removePrefix",
        "removeSuffix",
        "padStart",
        "padEnd",
        "repeat",
        "orEmpty",
    )
private val NON_NULL_ASSERTION = regex("""\s*!!$""")
private val OPERAND_CHAR = regex("""[\p{L}\p{Nd}_.?!()\[\]{}"']""")

/**
 * What a call shows its result to be, by its name and what it is called on: `u.toLong()` or
 * `(a + b).toInt()` a number, `x.toString()` a String, `query.trim()` or `query.ifBlank { "none" }`
 * a String when `query` is one, `query!!.trim()` too. Null for a safe call, `u?.toLong()`, which may be null, and for a call on only part of
 * the value, `s + u.toInt()`.
 */
internal fun callKind(
    src: String,
    t: String,
    at: Int,
    constants: Set<String>,
    sdk: FileModules = FileModules.DEFAULT,
): ValueFact? {
    val call = lastCall(t) ?: return null
    val receiver = call.receiver ?: return null
    if (!wholeOperand(receiver) ||
        t
            .trim()
            .substring(receiver.length)
            .trimStart()
            .startsWith("?")
    ) {
        return null
    }
    if (call.name in NUMBER_CONVERSIONS) return if (call.lambda == null && call.args.isBlank()) ValueFact.NUMBER else null
    // Whatever it is called on, and `x!!.toString()` too.
    if (call.name == "toString") return if (call.lambda == null && call.args.isBlank()) ValueFact.STRING else null
    val lambda = call.name == "ifBlank" || call.name == "ifEmpty"
    if (!(call.name in STRING_CHANGES && call.lambda == null) && !(lambda && call.lambda != null && call.args.isBlank())) return null
    // `query!!.trim()`: what `!!` leaves of a `String?` is a String.
    if (!valueKind(src, receiver.replace(NON_NULL_ASSERTION, ""), at, constants, sdk = sdk).isString()) return null
    if (!lambda) return ValueFact.STRING
    val result = valueKind(src, lambdaResult(call.lambda!!), at, constants, sdk = sdk)
    return if (result.isString() || result.isConstant()) ValueFact.STRING else null
}

/** Whether [e] is one operand, a name, a chain of calls or a bracketed expression, with no operator outside its brackets. */
private fun wholeOperand(e: String): Boolean {
    val d = depths(e)
    return e.indices.all { d[it] != 0 || OPERAND_CHAR.matchEntire(e[it].toString()) != null }
}

/** Whether [name] means, at [at], a parameter or value marked `@Language("HTML")`. */
internal fun htmlNamed(
    src: String,
    name: String,
    at: Int,
): Boolean = declarationOf(src, name, at)?.html ?: false

private val TRIM_CALL = regex("""^\s*\.\s*trim(?:Indent|Margin)\s*\(\s*(?:"[^"\\\n]*"\s*)?\)\s*$""")

// Lines as `USES_HTML_DSL` reads them: they start after `\n` alone and are indented with spaces and tabs.
private val USES_ELEMENTS =
    regex(
        """(?:^|(?<=\n))[ \t]*(?:import[ \t]+io\.github\.markusaugust\.streamlord\.html\.(?:elements|\*)[ \t\r]*(?=\n|(?![\s\S]))|""" +
            """package[ \t]+io\.github\.markusaugust\.streamlord\.html(?![\p{L}\p{Nd}_]))""",
    )
internal val TEXT_OF_VALUE = regex("""^([\s\S]*?)\s*\.\s*(?:text|html)$""")
private val CREATE_HTML = regex("""^createHTML\s*\(""")
private val APPEND_HTML = regex("""\bappendHTML\s*\(""")
private val NAME_AT_END = regex("""([\p{L}_][\p{L}\p{Nd}_]*)\s*$""")
private val SAFE_CALL_DOT = regex("""\??\.$""")
private val LAMBDA_HEAD =
    regex(
        """^\s*(?:\([^()]*\)|[\p{L}_][\p{L}\p{Nd}_]*)(?:\s*:\s*[\p{L}\p{Nd}_.<>?`]+)?(?:\s*,\s*(?:\([^()]*\)|[\p{L}_][\p{L}\p{Nd}_]*)(?:\s*:\s*[\p{L}\p{Nd}_.<>?`]+)?)*\s*->""",
    )
private val ENDS_IN_OPERATOR = regex("""[-+*/%=<>&|?:.,(]$""")
private val STARTS_WITH_OPERATOR = regex("""^(?:[-+*/%&|?:.]|else\b)""")
internal val PROPERTY_CHAIN = regex("""^[\p{L}_][\p{L}\p{Nd}_]*(?:\s*\??\.\s*[\p{L}_][\p{L}\p{Nd}_]*)*(?:!!)?$""")
private val MARKUP_CHARS = regex("""[<&]""")

/** Whether [t] is one string literal of HTML, or one with a trim call after it. */
private fun htmlLiteral(t: String): Boolean {
    val quote = QUOTE_OPENING.find(t) ?: return false
    val at = quote.value.length - 1
    val end = literalEnd(t, at)
    val rest = t.substring(end)
    if (rest.isNotBlank() && TRIM_CALL.matchEntire(rest) == null) return false
    val content = t.substring(minOf(end, at + (if (t.startsWith("\"\"\"", at)) 3 else 1)), end)
    return opensWithTag(content, trimMargin = "trimMargin" in rest)
}

/** The call that ends [t], `receiver.name(args) { lambda }`. */
internal class Call(
    val name: String,
    val receiver: String?,
    val args: String,
    val lambda: String?,
)

/** The call that ends [t], or null when [t] does not end in one. */
internal fun lastCall(t: String): Call? {
    val e = t.trim()
    val d = depths(e)

    fun opening(close: Int): Int {
        for (k in close - 1 downTo 0) if (d[k] == d[close] && e[k] in "([{") return k
        return -1
    }
    var end = e.length
    var lambda: String? = null
    if (e.endsWith("}") && d[end - 1] != -1) {
        val open = opening(end - 1)
        if (open < 0) return null
        lambda = e.substring(open + 1, end - 1)
        end = open
        while (end > 0 && e[end - 1].isWhitespace()) end--
    }
    var args = ""
    if (end > 0 && e[end - 1] == ')' && d[end - 1] != -1) {
        val open = opening(end - 1)
        if (open < 0) return null
        args = e.substring(open + 1, end - 1)
        end = open
    } else if (lambda == null) {
        return null
    }
    val name = NAME_AT_END.find(e.substring(0, end)) ?: return null
    val before = e.substring(0, end - name.value.length).trimEnd()
    if (before.isEmpty()) return Call(name.groupValues[1], null, args, lambda)
    val dot = SAFE_CALL_DOT.find(before) ?: return null
    return Call(name.groupValues[1], before.substring(0, before.length - dot.value.length), args, lambda)
}

/** What a lambda gives: its last expression, without its parameters. */
private fun lambdaResult(body: String): String {
    val b = body.replaceFirst(LAMBDA_HEAD, "")
    val d = depths(b)
    var from = 0
    for (k in b.indices) {
        if (d[k] != 0 || (b[k] != '\n' && b[k] != ';')) continue
        val before = b.substring(from, k).trim()
        val after = b.substring(k + 1).trimStart()
        // A line that ends in an operator, or one that starts with one, goes on with the next.
        if (b[k] == '\n' &&
            (ENDS_IN_OPERATOR.containsMatchIn(before) || STARTS_WITH_OPERATOR.containsMatchIn(after) || before.isEmpty())
        ) {
            continue
        }
        from = k + 1
    }
    return b.substring(from).trim()
}

/** The argument given as `name = { ... }`, as its lambda's body. */
private fun namedLambda(
    args: String,
    name: String,
): String? {
    val re = regex("""^\s*$name\s*=\s*\{([\s\S]*)\}\s*$""")
    for (a in splitTop(args, ",")) re.find(a)?.let { return it.groupValues[1] }
    return null
}

/**
 * Whether a placeholder's whole result is markup the author built, which escaping would print as tags:
 * an HTML literal, trimmed or not, or a `+` chain that opens with one; a call to `interpolate` or
 * `Trusted`, or the text of an `Html`; a name declared `Html` or marked `@Language("HTML")`;
 * kotlinx.html's `createHTML()`, `appendHTML()` in `buildString`, or Streamlord's `elements { }`;
 * `joinToString` of markup or of a collection declared one of Html; a `val` set to a call that is
 * markup; or an `if`, a `when` or an `?:` every one of whose results is markup.
 */
internal fun markupValue(
    src: String,
    value: String,
    at: Int,
    depth: Int = 0,
    sdk: FileModules = FileModules.DEFAULT,
): Boolean {
    val values = templateValues(value)
    // `if (admin) "<b>admin</b>" else ""`: every result is markup or a literal the author wrote.
    if (values.size > 1) {
        val markup = values.map { markupValue(src, it.text, at, depth, sdk) }
        return markup.any { it } &&
            values.indices.all { k ->
                val v = values[k].text
                markup[k] || LITERAL.matchEntire(v) != null || plainString(v) || CHAR_LITERAL.matchEntire(v) != null
            }
    }
    val t = values[0].text
    if (htmlLiteral(t)) return true
    val sum = splitTop(t, "+")
    if (sum.size > 1) return htmlLiteral(sum[0].trim())
    if (sdk.core && (coreCall(src, t, "interpolate") || coreCall(src, t, "Trusted"))) return true
    val textOf = TEXT_OF_VALUE.find(t)?.groupValues?.get(1)
    // The text of an Html or the html of a Trusted, the markup each holds; neither has the other.
    if (sdk.core &&
        textOf != null &&
        (
            coreCall(src, textOf, "interpolate") || coreCall(src, textOf, "Trusted") ||
                (KOTLIN_NAME.matchEntire(textOf) != null && declaredKind(src, textOf, at).isMarkup())
        )
    ) {
        return true
    }
    if (KOTLIN_NAME.matchEntire(t) != null) {
        if (declaredKind(src, t, at, core = sdk.core).isMarkup() || htmlNamed(src, t, at)) return true
        // `val e = elements { }`: the name stands for the call it is set to; a literal it is set to is judged where it is written.
        val d = declarationOf(src, t, at) ?: return false
        val init = initialiser(src, d) ?: return false
        return depth < VALUE_DEPTH && lastCall(init) != null && markupValue(src, init, d.at, depth + 1, sdk)
    }
    if (CREATE_HTML.containsMatchIn(t)) return true
    val call = lastCall(t) ?: return false
    val lambda = call.lambda
    if (call.name == "buildString" && call.receiver == null && lambda != null) return APPEND_HTML.containsMatchIn(lambda)
    val elements = call.name == "elements" && call.receiver == null && lambda != null
    if (elements) return sdk.htmlDsl != false && USES_ELEMENTS.containsMatchIn(src)
    if (call.name != "joinToString") return false
    val transform = lambda ?: namedLambda(call.args, "transform")
    if (transform != null) return markupValue(src, lambdaResult(transform), at, depth, sdk)
    return call.receiver != null && markupElements(src, call.receiver, at, depth, sdk)
}

/**
 * Whether [value] is a name for a collection of Html or Trusted, `rows: List<Html>`: markup the
 * author built, which a template writes in brackets and interpolate would escape.
 */
internal fun markupCollection(
    src: String,
    value: String,
    at: Int,
    sdk: FileModules,
): Boolean = KOTLIN_NAME.matchEntire(value.trim()) != null && markupElements(src, value.trim(), at, 0, sdk)

/** How many names deep a value is followed to what it is set to. */
internal const val VALUE_DEPTH = 4

/**
 * Whether every element of a collection is markup: `listOf(a, b)` of markup, a `map { }` to
 * markup, a name declared a `List<Html>`, or one set to such a collection.
 */
private fun markupElements(
    src: String,
    receiver: String,
    at: Int,
    depth: Int,
    sdk: FileModules,
): Boolean {
    val t = receiver.trim()
    if (KOTLIN_NAME.matchEntire(t) != null) {
        val d = declarationOf(src, t, at) ?: return false
        val type = d.type
        if (type != null) {
            return sdk.core &&
                d.typeArguments?.replace(OUT_VARIANCE, "")?.let {
                    !markupTypeShadowed(src, it) && isMarkupCollection("${type.removeSuffix("?")}<${coreType(src, it)}>")
                } == true
        }
        val init = initialiser(src, d) ?: return false
        return depth < VALUE_DEPTH && markupElements(src, init, d.at, depth + 1, sdk)
    }
    val r = lastCall(t) ?: return false
    val lambda = r.lambda
    if ((r.name == "listOf" || r.name == "arrayOf") && r.receiver == null && lambda == null) {
        return r.args.isNotBlank() && splitTop(r.args, ",").all { markupValue(src, it, at, depth, sdk) }
    }
    if ((r.name == "map" || r.name == "mapIndexed" || r.name == "mapNotNull") &&
        lambda != null
    ) {
        return markupValue(src, lambdaResult(lambda), at, depth, sdk)
    }
    return false
}

/**
 * The value a `val` is set to, as written: from after its `=` to where the statement ends, at a
 * line break that ends it, a `;`, a comment, or the bracket that closes what holds it. Null for a
 * `var`, a literal, or a name declared in any other way.
 */
internal fun initialiser(
    src: String,
    d: Declaration,
): String? {
    if (d.valueAt < 0) return null
    val code = fileFacts(src).code
    var depth = 0
    var k = d.valueAt
    while (k < src.length) {
        if (code[k]) {
            val c = src[k]
            if (c == '(' || c == '[' || c == '{') {
                depth++
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) break
                depth--
            } else if (depth == 0 && (c == ';' || (c == '\n' && lineEnds(src, code, k)))) {
                break
            }
        } else if (depth == 0 && (src.startsWith("//", k) || src.startsWith("/*", k))) {
            break
        }
        k++
    }
    return src.substring(d.valueAt, k).trim().takeIf { it.isNotEmpty() }
}

/** Whether a placeholder's value is markup the author built; see [markupValue]. */
internal fun isMarkup(
    src: String,
    s: KotlinString,
    ip: Interpolation,
    sdk: FileModules = FileModules.DEFAULT,
): Boolean = markupValue(src, ip.text, s.start, sdk = sdk)

/** The calls whose result is the text they were given, changed but no more markup than it was. */
private val TEXT_TRANSFORMS =
    setOf(
        "trim",
        "trimStart",
        "trimEnd",
        "lowercase",
        "uppercase",
        "toLowerCase",
        "toUpperCase",
        "capitalize",
        "decapitalize",
        "replaceFirstChar",
        "take",
        "takeLast",
        "drop",
        "dropLast",
        "substring",
        "substringBefore",
        "substringAfter",
        "substringBeforeLast",
        "substringAfterLast",
        "removePrefix",
        "removeSuffix",
        "removeSurrounding",
        "padStart",
        "padEnd",
        "orEmpty",
        "toString",
        "reversed",
        "repeat",
    )

/** Whether a string literal in [e] holds `<` or `&`, which markup is made of. */
private fun markupInLiteral(e: String): Boolean {
    var k = 0
    while (k < e.length) {
        if (e[k] != '"') {
            k++
            continue
        }
        val end = literalEnd(e, k)
        if (MARKUP_CHARS.containsMatchIn(e.substring(k, end))) return true
        k = end
    }
    return false
}

/** The calls whose result is a number or a boolean, whatever they are called on. */
private val NUMBER_CALLS =
    NUMBER_CONVERSIONS +
        "dec inc count sum compareTo hashCode indexOf lastIndexOf isEmpty isNotEmpty isBlank isNotBlank contains startsWith endsWith equals any all none"
            .split(" ")
private val NUMBER_PROPERTY = regex("""[\p{L}\p{Nd}_)\]]\s*\??\.\s*(?:size|length|lastIndex)$""")

/** Outside brackets: an operator whose result is a Boolean, which binds looser than any that may give a String. */
private val BOOLEAN_OPERATOR = regex("""&&|\|\||[=!]==?|\s(?:<=|>=|<|>|!?in|!?is)\s""")

/** Outside brackets: an operator that may give anything, or a String: a cast, an elvis, a range or an infix call. */
private val OTHER_OPERATOR = regex("""\?:|\.\.|\s[\p{L}_][\p{L}\p{Nd}_]*\??\s""")

/** Outside brackets: `*`, `/`, `%` or a `-` between operands, which Kotlin defines for numbers. */
private val ARITHMETIC = regex("""[*/%]|[\p{L}\p{Nd}_)\]]\s*-(?![-=>])""")

/**
 * What the operators of [value] show it to be: a comparison or a test is a Boolean, whatever is
 * compared, and arithmetic of numbers alone, each operand a number the text shows or a number
 * literal, is a number. Null where they show nothing: an operand of another kind may have an
 * operator of its own.
 */
private fun shapeKind(
    src: String,
    value: String,
    at: Int,
    constants: Set<String>,
    sdk: FileModules,
): ValueFact? {
    val e = unparen(value)
    val d = depths(e)
    val top = CharArray(e.length) { if (d[it] == 0) e[it] else ' ' }.concatToString()
    if (BOOLEAN_OPERATOR.containsMatchIn(top)) return ValueFact.BOOLEAN
    if (OTHER_OPERATOR.containsMatchIn(top)) return null
    // Cut at each binary operator: one after an operand, so a sign stays with what it signs.
    val operands = ArrayList<String>()
    var from = 0
    for (k in top.indices) {
        if (top[k] !in "+-*/%" || top.getOrNull(k + 1).let { it == '=' || it == top[k] }) continue
        val before = top.substring(from, k).trimEnd()
        if (before.isEmpty() || (!isWordPart(before.last()) && before.last() != ')' && before.last() != ']')) continue
        operands += e.substring(from, k)
        from = k + 1
    }
    if (operands.isEmpty()) return null
    operands += e.substring(from)
    return ValueFact.NUMBER.takeIf {
        operands.all { o ->
            val operand = o.trim()
            KOTLIN_NUMBER.matchEntire(operand.removePrefix("-")) != null ||
                valueKind(src, operand, at, constants, sdk = sdk) == ValueFact.NUMBER
        }
    }
}

/**
 * Whether the shape of [value] says it is a number or a boolean, whatever its type: arithmetic, a
 * comparison, a negation, or a call or a property such as `.dec()`, `.count()` or `.size`. A sum
 * may be a String, and a call of the author's anything.
 */
internal fun numberShaped(value: String): Boolean {
    val e = unparen(value)
    if (e.isEmpty()) return false
    if (KOTLIN_NUMBER.matchEntire(e) != null) return true
    val d = depths(e)
    val top = CharArray(e.length) { if (d[it] == 0) e[it] else ' ' }.concatToString()
    if (BOOLEAN_OPERATOR.containsMatchIn(top)) return true
    if (OTHER_OPERATOR.containsMatchIn(top) || splitTop(e, "+").size > 1) return false
    if (ARITHMETIC.containsMatchIn(top)) return true
    // One operand from here on, spaces before a lambda aside.
    if (top.trimStart('!', '-').any { !it.isWhitespace() && OPERAND_CHAR.matchEntire(it.toString()) == null }) return false
    if ((e.startsWith("!") && !e.startsWith("!!")) || (e.startsWith("-") && !e.startsWith("--"))) return true
    if (NUMBER_PROPERTY.containsMatchIn(e)) return true
    val call = lastCall(e) ?: return false
    return call.receiver != null && call.name in NUMBER_CALLS
}

/**
 * Whether a value in element text may hold markup that `interpolate` would escape: a constant
 * whose value this file does not show or shows with a tag, a literal with one, or the result of a
 * call that is not a plain change to text (`items(xs)`, a `joinToString` with tags in it). A
 * rewrite to `interpolate` would then print the tags.
 */
internal fun mayHoldMarkup(
    src: String,
    value: String,
    at: Int,
    constants: Set<String>,
): Boolean =
    templateValues(value).any { v ->
        val t = v.text
        when {
            LITERAL.matchEntire(t) != null || CHAR_LITERAL.matchEntire(t) != null || numberShaped(t) -> {
                false
            }

            KOTLIN_NAME.matchEntire(t) != null && markupText(src, t, at) -> {
                true
            }

            KOTLIN_NAME.matchEntire(t) != null -> {
                if (!constantAt(src, t, at, constants)) {
                    false
                } else {
                    val text = declarationOf(src, t, at)?.text
                    text == null || MARKUP_CHARS.containsMatchIn(text)
                }
            }

            markupInLiteral(t) -> {
                true
            }

            t.startsWith("\"") || PROPERTY_CHAIN.matchEntire(t) != null -> {
                false
            }

            else -> {
                val sum = splitTop(t, "+")
                if (sum.size > 1) {
                    sum.any { mayHoldMarkup(src, it, at, constants) }
                } else {
                    val call = lastCall(t)
                    if (call == null) {
                        true
                    } else {
                        val plain =
                            call.name in TEXT_TRANSFORMS ||
                                (call.name == "joinToString" && call.lambda == null && namedLambda(call.args, "transform") == null)
                        !plain || call.receiver == null || mayHoldMarkup(src, call.receiver, at, constants)
                    }
                }
            }
        }
    }

/**
 * Whether markup in a placeholder is a value `interpolate` writes as it stands in element text, an
 * `Html` or a `Trusted`, rather than a String it would escape.
 */
internal fun markupValued(
    src: String,
    s: KotlinString,
    ip: Interpolation,
): Boolean = heldAsMarkup(src, ip.text, s.start)

private fun heldAsMarkup(
    src: String,
    value: String,
    at: Int,
): Boolean =
    (branches(value) ?: listOf(value)).all { v ->
        val t = v.trim()
        if (KOTLIN_NAME.matchEntire(t) != null) {
            declaredKind(src, t, at).isMarkup()
        } else {
            val call = MARKUP_CALL_START.find(t)
            call != null && closing(t, depths(t), call.value.length - 1) == t.length
        }
    }

/**
 * Whether a value handed to `interpolate` is markup held as a String: `h.text`, an HTML literal,
 * `elements { }`, a `joinToString` of markup. In element text `interpolate` escapes it, so the
 * tags are printed; an `Html` or a `Trusted` it writes as it stands.
 */
internal fun markupHeldAsString(
    src: String,
    value: String,
    at: Int,
): Boolean {
    // A literal is text the author chose to have escaped, `"<the oath>"`, whatever it looks like.
    if (templateValues(value).all {
            htmlLiteral(it.text) || stringLiteral(it.text) != null || LITERAL.matchEntire(it.text) != null ||
                CHAR_LITERAL.matchEntire(it.text) != null
        }
    ) {
        return false
    }
    return (markupValue(src, value, at) && !heldAsMarkup(src, value, at)) || markupTextValue(src, value, at)
}

/** Whether [name] means, at [at], a value set to the `.text` of an Html: `val row = interpolate(...).text`. */
private fun markupText(
    src: String,
    name: String,
    at: Int,
): Boolean {
    val of = declarationOf(src, name, at)?.textOf ?: return false
    return wholeCall(of, "interpolate") ||
        (KOTLIN_NAME.matchEntire(of) != null && declaredKind(src, of, declarationOf(src, name, at)!!.at).isMarkup())
}

/** Whether every value a placeholder can give is a name set to the `.text` of an Html: markup held as a String. */
internal fun markupTextValue(
    src: String,
    value: String,
    at: Int,
): Boolean = templateValues(value).all { KOTLIN_NAME.matchEntire(it.text) != null && markupText(src, it.text, at) }

/**
 * Where the unquoted attribute value that runs up to [end] starts, after its `=` and any white space,
 * or null when [end] is not in one: read back from [end], so that each value costs its own length.
 */
private fun unquotedValueStart(
    view: String,
    end: Int,
): Int? {
    var k = end
    while (k > 0 && view[k - 1] !in " \t\n\u000C\r\"'=<>`") k--
    val start = k
    while (k > 0 && view[k - 1] in " \t\n\u000C\r") k--
    return if (k > 0 && view[k - 1] == '=') start else null
}

/**
 * The fix for a value written into an attribute value without quotes: quotes around the whole
 * value, which the browser reads the same, so a space in the value no longer starts an attribute.
 * Null when the value holds a quote of its own, or spans literals joined with `+`, which [operands]
 * says of the string, asked once for all its values.
 */
internal fun quoteFix(
    s: KotlinString,
    view: String,
    ip: Interpolation,
    operands: Boolean,
): Fix? {
    if (operands) return null
    val from = unquotedValueStart(view, ip.decodedStart) ?: return null
    var to = ip.decodedStart
    while (to < view.length && view[to] !in " \t\n\u000C\r>") {
        if (view[to] in "\"'=<`") return null
        to++
    }
    val quote = if (s.rawAt(ip)) "\"" else "\\\""
    val start = s.map[from]
    val end = s.map[to]
    return Fix("Quote the value", start, start, quote, listOf(Edit(end, end, quote)))
}
