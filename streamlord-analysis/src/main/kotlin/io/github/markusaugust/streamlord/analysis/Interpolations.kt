package io.github.markusaugust.streamlord.analysis

/*
 * The pieces of the interpolation judgement that are about Kotlin rather than about Datastar:
 * which values are joined into an HTML string, which of them the analysis can see are safe or
 * are markup themselves, what the text looks like once `trimMargin` has run, and the rewrite to
 * `interpolate`. The twin of interpolation.ts in the VS Code extension; where a hole lands is
 * core's `holeLandings`.
 */

private val TRIM_MARGIN_AFTER = Regex("""^\s*\.\s*trimMargin\s*\(\s*(?:"([^"\\\n]*)")?\s*\)""")
private val TRIM_CALL_AFTER = Regex("""^\s*\.\s*trim(?:Indent|Margin)\s*\(""")
private val HTML_OPENING = Regex("""^\s*<(?:[A-Za-z]|!--|!doctype)""", RegexOption.IGNORE_CASE)
private val IDENT = Regex("""[\p{L}_][\p{L}\p{Nd}_]*""")
private val LITERAL = Regex("""(?:-?\d[\d_]*(?:\.\d+)?[LlFfDd]?|true|false|null)""")
private val STRING_LITERAL = Regex(""""(?:[^"\\$]|\\.)*"""")
private val LITERAL_BRANCHES =
    Regex(
        """if\s*\(.*?\)\s*("(?:[^"\\$]|\\.)*"|-?\d+|true|false)\s*else\s*("(?:[^"\\$]|\\.)*"|-?\d+|true|false)""",
        RegexOption.DOT_MATCHES_ALL,
    )
private val FUN_KEYWORD = Regex("""\bfun\b""")
private val NUMBER_TYPE = Regex("""(?:kotlin\.)?(?:Int|Long|Short|Byte|Double|Float|UInt|ULong|UShort|UByte)""")
private val BOOLEAN_TYPE = Regex("""(?:kotlin\.)?Boolean""")
private val STRING_TYPE = Regex("""(?:kotlin\.)?(?:String|CharSequence)\??""")
private val HTML_TYPE = Regex("""(?:[\p{L}_][\p{L}\p{Nd}_]*\.)*Html\??""")
private val INTERPOLATE_CALL = Regex("""(?:^|[^\p{L}\p{Nd}_.])interpolate\s*\(""")
private val WORD_CHAR = Regex("""[\p{L}\p{Nd}_]""")

/** What may end an operand of `+` at the top level: an argument or statement boundary, or an operator that binds less tightly. */
private val OPERAND_END = Regex("""^(?:[,)\]};]|\?:|&&|\|\||==|!=|<=|>=|\.\.)""")

/** The words that end an operand, as words of their own: `photo` is a name, not `pho` and `to`. */
private val OPERAND_END_WORDS = listOf("to", "else")

private const val INTERPOLATE_IMPORT = "io.github.markusaugust.streamlord.core.domain.interpolate"
private val INTERPOLATE_IMPORTED =
    Regex(
        """^\s*import\s+(?:${Regex.escape(INTERPOLATE_IMPORT)}|io\.github\.markusaugust\.streamlord\.core\.domain\.\*)\s*$""",
        setOf(RegexOption.MULTILINE, RegexOption.UNIX_LINES),
    )
private val IMPORT_LINE = Regex("""^import\s+[^\n]*$""", setOf(RegexOption.MULTILINE, RegexOption.UNIX_LINES))
private val PACKAGE_LINE = Regex("""^package\s+[^\n]*$""", setOf(RegexOption.MULTILINE, RegexOption.UNIX_LINES))

/** How a hole is named in a message: `$name`, `${expr}` shortened, or the operand as written. */
internal fun holeDisplay(
    ip: Interpolation,
    dollars: Int,
): String {
    if (ip.kind == InterpolationKind.SIMPLE) return "$".repeat(dollars) + ip.text
    if (ip.kind == InterpolationKind.OPERAND) return ip.text
    val e = ip.text.trim()
    return "$".repeat(dollars) + "{" + (if (e.length <= 30) e else e.substring(0, 29) + "…") + "}"
}

/**
 * The literal's text as `trimMargin` leaves it for the markup checks: each margin marker at the
 * start of a line becomes a space, so nothing moves and the map still holds.
 */
internal fun markupView(
    src: String,
    s: KotlinString,
): String {
    val m = TRIM_MARGIN_AFTER.find(src.substring(s.end, minOf(src.length, s.end + 64))) ?: return s.text
    val marker = m.groups[1]?.value ?: "|"
    if (marker.isEmpty()) return s.text
    val out = StringBuilder()
    var lineStart = true
    var i = 0
    while (i < s.text.length) {
        val c = s.text[i]
        if (lineStart && c != ' ' && c != '\t') {
            lineStart = false
            if (s.text.startsWith(marker, i)) {
                out.append(" ".repeat(marker.length))
                i += marker.length
                continue
            }
        }
        if (c == '\n') lineStart = true
        out.append(c)
        i++
    }
    return out.toString()
}

/** Whether a trim call follows the literal, which `interpolate`'s rewrite cannot move without changing the output. */
internal fun trimmed(
    src: String,
    s: KotlinString,
): Boolean = TRIM_CALL_AFTER.containsMatchIn(src.substring(s.end, minOf(src.length, s.end + 64)))

/** Every literal the lexer found, and those inside their templates. */
internal fun withNested(strings: List<KotlinString>): List<KotlinString> {
    val out = ArrayList<KotlinString>()

    fun visit(s: KotlinString) {
        out += s
        for (n in s.nested) visit(n)
    }
    for (s in strings) visit(s)
    return out
}

/**
 * A braced template whose every result is a literal the author wrote: `${if (n == 1) "error"
 * else "errors"}`, a number, `true`. Its value is markup the source spells out.
 */
internal fun literalOnly(expr: String): Boolean {
    val e = expr.trim()
    return LITERAL.matches(e) || STRING_LITERAL.matches(e) || LITERAL_BRANCHES.matches(e)
}

/** The name a hole reads, when it is one plain name: `$x`, `${x}`. */
internal fun plainName(ip: Interpolation): String? = ip.text.trim().takeIf { IDENT.matches(it) }

/** Where the declaration that encloses [at] starts: the nearest `fun` before it, or the file. */
private fun scopeStart(
    src: String,
    at: Int,
): Int =
    FUN_KEYWORD
        .findAll(src.substring(0, at))
        .lastOrNull()
        ?.range
        ?.first ?: 0

/**
 * Whether [name] is declared between the enclosing function and [at]: a parameter, a local, a
 * lambda parameter. Such a name is not the workspace's constant of the same name.
 */
internal fun shadowed(
    src: String,
    name: String,
    at: Int,
): Boolean {
    val scope = src.substring(scopeStart(src, at), at)
    val n = Regex.escape(name)
    return Regex(
        """\b(?:val|var)\s+$n\b|[(,]\s*(?:@\w+(?:\([^)]*\))?\s*)*$n\s*:|\b$n\s*(?:,\s*[\p{L}_][\p{L}\p{Nd}_]*\s*)*->|\bfor\s*\(\s*$n\b""",
    ).containsMatchIn(scope)
}

/**
 * The type the enclosing declaration gives [name]: a number or a boolean that cannot be null
 * (`count: Int`, `val n = 3`), a string (`name: String`), which is what may come from outside, or
 * core's `Html`, which is markup. The nearest declaration before [at] wins, as Kotlin's scoping
 * does; anything else is not known.
 */
internal fun declaredKind(
    src: String,
    name: String,
    at: Int,
): HoleFact? {
    val scope = src.substring(scopeStart(src, at), at)
    val n = Regex.escape(name)
    val declaration =
        Regex(
            """(?:[(,]\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:val|var)\s+)?|\b(?:val|var)\s+)$n\s*""" +
                """(?::\s*([\p{L}_][\p{L}\p{Nd}_.]*\??)|=\s*(-?\d[\d_]*(?:\.\d+)?[LlFfDd]?|true|false)\b|=|->|,)""",
        )
    val last = declaration.findAll(scope).lastOrNull() ?: return null
    val literal = last.groups[2]?.value
    if (literal != null) return if (literal == "true" || literal == "false") HoleFact.BOOLEAN else HoleFact.NUMBER
    val type = last.groups[1]?.value ?: return null
    return when {
        NUMBER_TYPE.matches(type) -> HoleFact.NUMBER
        BOOLEAN_TYPE.matches(type) -> HoleFact.BOOLEAN
        STRING_TYPE.matches(type) -> HoleFact.STRING
        HTML_TYPE.matches(type) -> HoleFact.MARKUP
        else -> null
    }
}

/** Whether [name] is a parameter or value marked `@Language("HTML")` in the declaration that encloses [at]. */
internal fun htmlNamed(
    src: String,
    name: String,
    at: Int,
): Boolean {
    val scope = src.substring(scopeStart(src, at), at)
    return Regex("""@Language\(\s*"html"\s*\)\s*(?:(?:val|var)\s+)?${Regex.escape(name)}\b""", RegexOption.IGNORE_CASE)
        .containsMatchIn(scope)
}

/**
 * Whether a hole holds markup the author built: a template with an HTML literal inside it, a
 * call to `interpolate`, or a name marked `@Language("HTML")`. Escaping it would print the tags.
 */
internal fun isMarkup(
    src: String,
    s: KotlinString,
    ip: Interpolation,
): Boolean {
    if (ip.kind != InterpolationKind.SIMPLE) {
        val inner = s.nested.filter { it.start >= ip.start && it.end <= ip.end }
        if (inner.any { HTML_OPENING.containsMatchIn(it.text) }) return true
        if (INTERPOLATE_CALL.containsMatchIn(ip.text)) return true
    }
    val name = plainName(ip)
    return name != null && htmlNamed(src, name, s.start)
}

/** Whether `to` or `else` stands at [at] as a word of its own. */
private fun operandEndWordAt(
    src: String,
    at: Int,
): Boolean =
    OPERAND_END_WORDS.any { w ->
        src.startsWith(w, at) &&
            !WORD_CHAR.matches(src.getOrNull(at - 1)?.toString() ?: "") &&
            !WORD_CHAR.matches(src.getOrNull(at + w.length)?.toString() ?: "")
    }

/** One part of a `+` chain: a literal, or the value between two of them. */
private class ChainPart(
    val literal: KotlinString?,
    val start: Int,
    val end: Int,
)

/**
 * The `+` chain that starts at the literal [first], read as one string: each literal's text, and
 * a hole for each value between them, mapped back to the source. Null when no `+` follows.
 * [mask] is the lexer's: `false` inside literals and comments.
 */
internal fun chainFrom(
    src: String,
    first: KotlinString,
    mask: BooleanArray,
): KotlinString? {
    val parts = arrayListOf(ChainPart(first, first.start, first.end))
    var i = first.end

    fun spaces(
        from: Int,
        newlines: Boolean,
    ): Int {
        var k = from
        while (k < src.length && (src[k] == ' ' || src[k] == '\t' || (newlines && (src[k] == '\n' || src[k] == '\r')))) k++
        return k
    }
    while (true) {
        // A literal followed by a call (`"…".trimIndent()`) is the receiver of that call: the chain ends.
        val plus = spaces(i, false)
        if (src.getOrNull(plus) != '+' || src.getOrNull(plus + 1) == '+' || src.getOrNull(plus + 1) == '=') break
        var k = spaces(plus + 1, true)
        if (k < src.length && (src[k] == '"' || src[k] == '$') && isStringStart(src, k)) {
            val lit = readKotlinStringAt(src, k)
            if (lit == null || lit.unterminated) break
            parts += ChainPart(lit, lit.start, lit.end)
            i = lit.end
            continue
        }
        val start = k
        var depth = 0
        while (k < src.length) {
            if (!mask[k]) {
                // A comment ends the operand; a string inside it is part of it.
                if (depth == 0 && (src.startsWith("//", k) || src.startsWith("/*", k))) break
                k++
                continue
            }
            val c = src[k]
            if (c == '(' || c == '[' || c == '{') {
                depth++
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) break
                depth--
            } else if (depth == 0) {
                if (c == '\n' || c == '\r') break
                if (c == '+' && src.getOrNull(k + 1) != '+' && src.getOrNull(k + 1) != '=') break
                if (OPERAND_END.containsMatchIn(src.substring(k, minOf(src.length, k + 4))) || operandEndWordAt(src, k)) break
            }
            k++
        }
        var end = k
        while (end > start && src[end - 1].isWhitespace()) end--
        if (end <= start) break
        parts += ChainPart(null, start, end)
        i = end
    }
    if (parts.size < 2) return null
    val text = StringBuilder()
    val map = ArrayList<Int>()
    val interpolations = ArrayList<Interpolation>()
    val nested = ArrayList<KotlinString>()
    for (p in parts) {
        val lit = p.literal
        if (lit != null) {
            for (ip in lit.interpolations) interpolations += ip.copy(decodedStart = ip.decodedStart + text.length)
            nested += lit.nested
            text.append(lit.text)
            for (c in 0 until lit.text.length) map += lit.map[c]
        } else {
            interpolations += Interpolation(p.start, p.end, InterpolationKind.OPERAND, src.substring(p.start, p.end), text.length)
            repeat(PLACEHOLDER.length) { map += p.start }
            text.append(PLACEHOLDER)
        }
    }
    val last = parts.last()
    val contentEnd = last.literal?.contentEnd ?: last.end
    map += contentEnd
    return KotlinString(
        start = first.start,
        end = last.end,
        raw = first.raw,
        dollars = 1,
        contentStart = first.contentStart,
        contentEnd = contentEnd,
        text = text.toString(),
        map = map.toIntArray(),
        interpolations = interpolations,
        unterminated = false,
        nested = nested,
    )
}

/**
 * The literal rewritten as a call to `interpolate`: each template becomes `%s` and its value an
 * argument, each `%` of the markup `%%`. With the import, when the file has none.
 */
internal fun interpolateFix(
    src: String,
    s: KotlinString,
): Fix {
    val format = StringBuilder()
    var at = s.start
    for (ip in s.interpolations) {
        format.append(src.substring(at, ip.start).replace("%", "%%")).append("%s")
        at = ip.end
    }
    format.append(src.substring(at, s.end).replace("%", "%%"))
    val args = s.interpolations.map { if (it.kind == InterpolationKind.SIMPLE) it.text else it.text.trim() }
    val call = "interpolate(" + (listOf(format.toString()) + args).joinToString(", ") + ")"
    val title = "Build it with interpolate(...), which escapes the values"
    if (INTERPOLATE_IMPORTED.containsMatchIn(src)) return Fix(title, s.start, s.end, call)
    val imports = IMPORT_LINE.findAll(src).lastOrNull()
    val pkg = PACKAGE_LINE.find(src)
    val anchor = (imports ?: pkg)?.let { it.range.last + 1 } ?: -1
    val edit =
        if (anchor < 0) {
            Edit(0, 0, "import $INTERPOLATE_IMPORT\n\n")
        } else {
            Edit(anchor, anchor, "\n" + (if (imports != null) "" else "\n") + "import $INTERPOLATE_IMPORT")
        }
    return Fix(title, s.start, s.end, call, listOf(edit))
}
