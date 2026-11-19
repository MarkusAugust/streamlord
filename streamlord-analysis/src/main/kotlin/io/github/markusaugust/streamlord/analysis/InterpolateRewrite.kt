package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.regex

/*
 * The rewrite of an HTML string to a call of `interpolate`, and the edits it brings: the core names
 * the file may use, Trusted for a value the author vouches for, the imports, and the per cent signs.
 */

private const val DOMAIN = "io.github.markusaugust.streamlord.core.domain"

/** How every title of the rewrite to `interpolate` starts. */
internal const val REWRITE_TITLE = "Mark the values with + in interpolate { }"

/** The title of the rewrite to `interpolate` where the value is one it escapes. */
internal const val ESCAPES_TITLE = "$REWRITE_TITLE, which escapes the values"

/** Its title where the value starts a URL, which escaping does not help but interpolate refuses when it would run. */
internal const val REFUSES_URL_TITLE = "$REWRITE_TITLE, which refuses a URL that runs"

/**
 * The rewrite of the literal or `+` chain to the block form of `interpolate`, over it and [trim], the trim
 * call after it, with the imports the file lacks: each value marked with `+` where it stands. Each value of
 * [trusted], by its place among the values, is marked as `Trusted(...)`, CSS in the body of a `<style>` that
 * the author vouches for, and the title names them; one not known to be a String, by its entry, as a template
 * of it, `"$v"`, the text a template writes of it. Each template of [kept] stays as it is written. The text
 * is made the first time it is read, and a copy of the fix under another title shares it.
 */
internal fun interpolateFix(
    src: String,
    s: KotlinString,
    trim: String,
    trusted: Map<Int, Boolean>,
    kept: Set<Int> = emptySet(),
): Fix {
    val named = trusted.keys.joinToString(" and ") { valueShown(s.interpolations[it]) }
    val title = if (trusted.isEmpty()) ESCAPES_TITLE else "$REWRITE_TITLE, with $named as Trusted(...)"
    val imports = coreImports(src, listOfNotNull("Trusted".takeIf { trusted.isNotEmpty() }, "interpolate"))
    return Fix.later(title, s.start, s.end + trim.length, imports, preferred = false, leads = true) {
        interpolateText(src, s, trim, trusted, kept)
    }
}

/**
 * The block that writes what the string did: the string as it is written, in `interpolate { }`, with each
 * template's value marked. A multi-dollar literal keeps its prefix, so a `$name` that was text stays text,
 * and [trim] stays on the string, which is trimmed before the values go in. A value joined with `+` is a
 * marked template at the end of the literal before it, and literals written alike become one. A single
 * literal is a chain of one.
 */
private fun interpolateText(
    src: String,
    s: KotlinString,
    trim: String,
    trusted: Map<Int, Boolean>,
    kept: Set<Int>,
): String {
    val literals = s.literals.ifEmpty { listOf(s) }
    val places = s.interpolations.withIndex().associate { (k, ip) -> ip.start to k }

    fun marked(
        k: Int,
        literal: KotlinString,
    ): String {
        val text = s.interpolations[k].text.trim()
        val value =
            when (trusted[k]) {
                null -> markOperand(text)

                true -> "Trusted($text)"

                // The text a template writes of it: a toString() of a value that is a String after all would be a warning.
                false -> "Trusted(\"" + (if (KOTLIN_NAME.matchEntire(text) != null) "\$$text" else "\${$text}") + "\")"
            }
        return "$".repeat(literal.dollars) + "{+" + value + "}"
    }
    val bodies =
        literals.map { literal ->
            val out = StringBuilder()
            var at = literal.contentStart
            while (at < literal.contentEnd) {
                val k = places[at]
                if (k == null) {
                    out.append(src[at++])
                    continue
                }
                val end = s.interpolations[k].end
                out.append(if (k in kept) src.substring(at, end) else marked(k, literal))
                at = end
            }
            out
        }
    for ((k, value) in s.interpolations.withIndex()) {
        if (value.kind != InterpolationKind.OPERAND) continue
        val before = literals.indexOfLast { it.end <= value.start }
        if (before >= 0) bodies[before].append(marked(k, literals[before])) else bodies[0].insert(0, marked(k, literals[0]))
    }
    val markup = StringBuilder()
    var form = "" to ""
    // What the literal being written holds so far, of every literal made one with it.
    val held = StringBuilder()
    literals.forEachIndexed { k, literal ->
        val written = src.substring(literal.start, literal.contentStart) to src.substring(literal.contentEnd, literal.end)
        when {
            k > 0 && written == form && joins(held, bodies[k], literal.raw) -> {
                markup.setLength(markup.length - form.second.length)
            }

            else -> {
                if (k > 0) markup.append(" + ")
                markup.append(written.first)
                held.setLength(0)
            }
        }
        held.append(bodies[k])
        markup.append(bodies[k]).append(written.second)
        form = written
    }
    // `.text`, the String the literal was: interpolate returns Html, and a fix that changed the type
    // would break whatever took the String.
    return "interpolate { $markup$trim }.text"
}

/**
 * Whether two literals written alike read the same as one: not where the first ends in a dollar or a name that the
 * second would carry on, which would make a template or lengthen one, nor where quotes would meet in a raw literal.
 */
private fun joins(
    first: CharSequence,
    second: CharSequence,
    raw: Boolean,
): Boolean {
    val last = first.lastOrNull() ?: return true
    val next = second.firstOrNull() ?: return true
    val name = { c: Char -> c.isLetterOrDigit() || c == '_' || c == '`' }
    return !(last == '$' || (name(last) && (name(next) || next == '{')) || (raw && (last == '"' || next == '"')))
}

/** [expr] as what a `+` marks: the mark takes the nearest operand, so anything but a name, a call or a chain of them goes in parentheses. */
internal fun markOperand(expr: String): String =
    when {
        CHAIN.matchEntire(expr.replace(CHAIN_ARGUMENTS, "")) != null -> expr

        expr.startsWith("(") && closing(expr, depths(expr), 0) == expr.length -> expr

        // A line comment would take the parenthesis that closes after it.
        "//" in expr -> "($expr\n)"

        else -> "($expr)"
    }

private val CHAIN = regex("""[\p{L}_`][\p{L}\p{Nd}_`]*(?:\.[\p{L}_`][\p{L}\p{Nd}_`]*)*""")

/** The arguments of a call and of an index without a bracket inside them, and what else may follow a name in a chain. */
private val CHAIN_ARGUMENTS = regex("""\([^()]*\)|\[[^\[\]]*\]|!!|\?(?=\.)""")

/**
 * Whether a fix may write [name] of core's domain, `interpolate` or `Trusted`, in [src]: core is
 * there, no call of the name is one the host knows is somebody else's ([Modules.foreign]), and
 * nothing else in the file takes the name.
 */
internal fun coreNameUsable(
    src: String,
    name: String,
    sdk: FileModules,
): Boolean {
    if (!sdk.core) return false
    val facts = fileFacts(src)
    if (sdk.foreign !== NOT_FOREIGN &&
        facts.coreCalls.getOrPut(name) { findCallSites(src, setOf(name)).map { it.nameStart } }.any { sdk.foreign(name, it) }
    ) {
        return false
    }
    return !facts.shadowed.getOrPut(name) { shadowedInFile(src, facts.code, name) }
}

/**
 * Whether [name] in [src] means something other than core's own, read once per file by
 * [shadowedInFile]: an import of it from elsewhere, by name or as an alias, or a declaration of it.
 * A star import from elsewhere does not count, since nothing says the package has the name.
 */
internal fun shadowsCore(
    src: String,
    name: String,
): Boolean {
    val facts = fileFacts(src)
    return facts.taken.getOrPut(name) { shadowedInFile(src, facts.code, name, stars = false) }
}

/**
 * The names [src] imports names of core's domain under, each with the name it stands for:
 * `import io.github.markusaugust.streamlord.core.domain.Html as Page` gives `Page` to `Html`.
 */
internal fun coreAliases(src: String): Map<String, String> {
    val facts = fileFacts(src)
    return facts.aliases ?: aliasesIn(src, facts.code).also { facts.aliases = it }
}

/** The aliases of [coreAliases], read from the imports in the [code] of [src]. */
internal fun aliasesIn(
    src: String,
    code: BooleanArray,
): Map<String, String> =
    HashMap<String, String>().also { out ->
        for (line in keywordLines(src, code, "import")) {
            val t =
                src
                    .substring(line.start, line.codeEnd)
                    .trim()
                    .removeSuffix(";")
                    .trim()
                    .let(::unticked)
            val m = CORE_ALIAS.matchEntire(t) ?: continue
            out[m.groupValues[2]] = m.groupValues[1]
        }
    }

private val CORE_ALIAS = regex("""${Regex.escape(DOMAIN)}\.([\p{L}_][\p{L}\p{Nd}_]*)\s+as\s+([\p{L}_][\p{L}\p{Nd}_]*)""")

/** [written], a type as a declaration writes it, with a bare alias of core's `Html` or `Trusted` written as the name it stands for: `Page?` as `Html?`. */
internal fun coreType(
    src: String,
    written: String,
): String {
    val t = unticked(written).trim()
    val bare = t.removeSuffix("?").trim()
    val name = coreAliases(src)[bare]?.takeIf { it == "Html" || it == "Trusted" } ?: return t
    return name + t.substring(bare.length)
}

/** [written] with its backticks dropped: `` `Page`? `` as `Page?`, the same name to Kotlin. */
internal fun unticked(written: String): String = written.replace("`", "")

/**
 * [text] written with core's [name] where it starts with a call to it, under that name or an alias
 * the file imports it by: `T(x)` as `Trusted(x)`. Null where [text] starts with no call to core's [name].
 */
internal fun asCoreCall(
    src: String,
    text: String,
    name: String,
): String? {
    val m = CALLEE.find(text) ?: return null
    val callee = m.groupValues[2]
    val rest = text.substring(CALLEE.groupRange(text, m, 2)!!.last + 1 + m.groupValues[1].length)
    return when {
        callee == name -> if (shadowsCore(src, name)) null else name + rest
        coreAliases(src)[callee] == name -> name + rest
        else -> null
    }
}

private val CALLEE = regex("""^(`?)([\p{L}_][\p{L}\p{Nd}_]*)\1\s*\(""")

/** Whether [text] is one call to core's [name], under that name or an alias: `Trusted(x)`, `T(x)`. */
internal fun coreCall(
    src: String,
    text: String,
    name: String,
): Boolean = asCoreCall(src, text, name)?.let { wholeCall(it, name) } == true

/** Whether the text shows [value] to be a `Trusted`: one call of core's `Trusted`, or a name declared or set to one. */
internal fun trustedValue(
    src: String,
    value: String,
    at: Int,
): Boolean = value.trim().let { coreCall(src, it, "Trusted") || (KOTLIN_NAME.matchEntire(it) != null && trustedName(src, it, at)) }

/** Whether [text] calls core's `Trusted` anywhere, under that name or an alias. */
internal fun callsTrusted(
    src: String,
    text: String,
): Boolean =
    TRUSTED_CALL.containsMatchIn(text) ||
        coreAliases(src).any { (alias, name) ->
            name == "Trusted" &&
                regex("""(?:^|[^\p{L}\p{Nd}_.`])(`?)${Regex.escape(alias)}\1\s*\(""").containsMatchIn(text)
        }

private val TRUSTED_CALL = regex("""(?:^|[^\p{L}\p{Nd}_.`])(`?)Trusted\1\s*\(""")

/** Whether [d] is set to a call to core's `interpolate` or `Trusted`: by an alias, or by a name nothing else in [src] takes. */
internal fun setToCore(
    src: String,
    d: Declaration,
): Boolean = d.markup && (d.aliased || !shadowsCore(src, if (d.trusted) "Trusted" else "interpolate"))

/** Whether [written], a type as a declaration writes it, is a bare `Html` or `Trusted` that means another type in [src]. */
internal fun markupTypeShadowed(
    src: String,
    written: String,
): Boolean {
    val name = unticked(written).filterNot { it.isWhitespace() }.removeSuffix("?")
    return (name == "Html" || name == "Trusted") && shadowsCore(src, name)
}

/**
 * Whether something other than core's domain takes [name] in [src]: an import of it from elsewhere,
 * by name or as an alias, or a `fun`, `class`, `object`, `interface` or `typealias` of that name at
 * the top of the file. An import of core's own name settles it, since an explicit import wins.
 * With [stars], as a fix asks it, a star import from outside Streamlord counts where the file
 * already uses the name: an explicit import the fix adds wins over a star import, so the one risk is
 * that it takes the name from uses that meant the star import's. With no use there is nothing to
 * take, and the import is unambiguous.
 */
private fun shadowedInFile(
    src: String,
    code: BooleanArray,
    name: String,
    stars: Boolean = true,
): Boolean {
    val targets =
        keywordLines(src, code, "import").map {
            src
                .substring(it.start, it.codeEnd)
                .trim()
                .removeSuffix(";")
                .trim()
                .let(::unticked)
        }
    if ("$DOMAIN.$name" in targets) return false
    for (t in targets) {
        val alias = t.substringAfter(" as ", "").trim()
        if (alias.isNotEmpty()) {
            if (alias == name) return true
            continue
        }
        if (t.endsWith(".$name")) return true
        if (stars && t.endsWith(".*") && t != "$DOMAIN.*" && !t.startsWith("io.github.markusaugust.streamlord.") &&
            usedInCode(src, code, name)
        ) {
            return true
        }
    }
    val declaration = regex("""\b(?:fun|class|object|interface|typealias)\s+(?:<[^>]*>\s*)?(`?)${Regex.escape(name)}\1(?![\p{L}\p{Nd}_])""")
    for (m in declaration.findAll(src)) {
        if (!code[m.range.first]) continue
        var depth = 0
        for (i in 0 until m.range.first) {
            if (!code[i]) continue
            if (src[i] == '{') {
                depth++
            } else if (src[i] == '}') {
                depth--
            }
        }
        if (depth == 0) return true
    }
    return false
}

/**
 * Whether the code of [src] uses [name] as a bare name, templates included: a call of
 * `interpolate`, or `Trusted` called or named as a type. A qualified name, an import's among them,
 * is not one.
 */
private fun usedInCode(
    src: String,
    code: BooleanArray,
    name: String,
): Boolean {
    val call = name == "interpolate"
    var k = src.indexOf(name)
    while (k >= 0) {
        val before = src.getOrNull(k - 1)
        val after = k + name.length
        val word =
            code[k] &&
                (before == null || !(before.isLetterOrDigit() || before == '_' || before == '.')) &&
                src.getOrNull(after)?.let { it.isLetterOrDigit() || it == '_' } != true
        if (word && (!call || src.substring(after).trimStart().startsWith("("))) return true
        k = src.indexOf(name, k + 1)
    }
    return false
}

/** The imports of [names] of core's domain the file lacks, as one edit, so that two new ones stand on lines next to each other. */
private fun coreImports(
    src: String,
    names: List<String>,
): List<Edit> {
    val missing = names.map { it to importEdits(src, DOMAIN, it) }.filter { it.second.isNotEmpty() }
    if (missing.size < 2) return missing.flatMap { it.second }
    val (first, edits) = missing.first()
    val lines = missing.joinToString(lineBreak(src)) { "import $DOMAIN.${it.first}" }
    return edits.map { it.copy(text = it.text.replace("import $DOMAIN.$first", lines)) }
}

/**
 * The placeholder made a `Trusted` value, `${Trusted(body)}`: markup the author vouches for, which the
 * analysis then reads as the author's own. With the import, when the file has none. Never
 * preferred: whether the value is markup only the author knows.
 */
internal fun trustedFix(
    src: String,
    ip: Interpolation,
    shown: String,
): Fix {
    val expr = ip.text.trim()
    // A value joined with `+` is wrapped where it stands; a template keeps the dollars its literal opens one with.
    val text = if (ip.kind == InterpolationKind.OPERAND) "Trusted($expr)" else "$".repeat(ip.dollars) + "{Trusted($expr)}"
    return Fix("Mark $shown as markup", ip.start, ip.end, text, importEdits(src, DOMAIN, "Trusted"), preferred = false)
}

/** The calls that make a collection, and those whose result is a List, whatever they are called on; not those a String has too. */
private val COLLECTION_MAKERS =
    (
        "listOf mutableListOf arrayListOf emptyList buildList listOfNotNull List MutableList ArrayList " +
            "setOf mutableSetOf hashSetOf linkedSetOf sortedSetOf emptySet buildSet " +
            "mapOf mutableMapOf hashMapOf linkedMapOf sortedMapOf emptyMap buildMap " +
            "arrayOf emptyArray arrayOfNulls intArrayOf longArrayOf doubleArrayOf booleanArrayOf charArrayOf sequenceOf"
    ).split(" ").toSet()
private val LIST_RESULTS =
    (
        "toList toMutableList toSet toMutableSet toTypedArray map mapNotNull mapIndexed flatMap filterNotNull " +
            "sorted sortedBy sortedDescending sortedWith distinct chunked windowed zip"
    ).split(" ").toSet()

/** The calls whose result is a collection when what they are called on is one. */
private val COLLECTION_KEPT = setOf("orEmpty", "filter", "filterNot", "take", "drop", "reversed", "plus", "minus")

/** A collection as a declaration reads its type: the name, without what the brackets after it hold. */
private val COLLECTION_TYPE =
    regex(
        """(?:kotlin\.collections\.|java\.util\.)?(?:(?:Mutable)?(?:List|Set|Map|Collection|Iterable)|ArrayList|HashSet|""" +
            """LinkedHashSet|HashMap|LinkedHashMap|Sequence|(?:Int|Long|Short|Byte|Double|Float|Boolean|Char)?Array)\??""",
    )

/** A cast at the end of a value, `v as List<*>`, and the type it names. */
private val CAST = regex("""\sas\??\s+([\p{L}_][\p{L}\p{Nd}_.]*)\s*(?:<.*>)?\??$""")

/** Whether every value a placeholder can give is the result of a call, which may be markup the author built: `${badge(user)}`. */
internal fun callResult(value: String): Boolean = templateValues(value).all { lastCall(it.text) != null }

/**
 * The type the file shows [value] to have: a name's declared type, or, for a property reached
 * through what holds it, `u.tags`, the one type every property of that name the file declares has.
 */
internal fun shownType(
    src: String,
    value: String,
    at: Int,
): String? {
    val t = value.trim()
    if (KOTLIN_NAME.matchEntire(t) != null) return declarationOf(src, t, at)?.type
    if (PROPERTY_CHAIN.matchEntire(t) == null || '.' !in t) return null
    val name = t.substringAfterLast('.').removeSuffix("!!").trim()
    val types =
        fileFacts(src)
            .names[name]
            .orEmpty()
            .filter { it.shared }
            .map { it.type }
    return types.firstOrNull()?.takeIf { first -> types.all { it == first } }
}

/**
 * Whether the text shows every value a placeholder can give to be a collection, a List, a Set, a Map or
 * an array: declared one, set to one, made by a call that makes one, or cast to one. A template
 * writes it in brackets, which no CSS is.
 */
internal fun collectionShown(
    src: String,
    value: String,
    at: Int,
    depth: Int = 0,
): Boolean =
    depth < VALUE_DEPTH &&
        templateValues(value).all { v ->
            val t = unparen(v.text)
            val cast = CAST.find(t)
            val sum = splitTop(t, "+")
            val call = lastCall(t)
            when {
                cast != null -> {
                    COLLECTION_TYPE.matchEntire(cast.groupValues[1]) != null
                }

                sum.size > 1 -> {
                    collectionShown(src, sum[0], at, depth + 1)
                }

                shownType(src, t, at) != null -> {
                    COLLECTION_TYPE.matchEntire(shownType(src, t, at)!!) != null
                }

                KOTLIN_NAME.matchEntire(t) != null -> {
                    val d = declarationOf(src, t, at)
                    val init = d?.takeIf { it.valueAt >= 0 }?.let { valueFrom(src, it.valueAt)?.text }
                    init != null && collectionShown(src, init, d.at, depth + 1)
                }

                call == null -> {
                    false
                }

                call.receiver == null -> {
                    call.name in COLLECTION_MAKERS
                }

                call.name in LIST_RESULTS -> {
                    true
                }

                else -> {
                    call.name in COLLECTION_KEPT && collectionShown(src, call.receiver, at, depth + 1)
                }
            }
        }

/** The package of streamlord-html, whose helpers the expression fixes write. */
internal const val HTML_PACKAGE = "io.github.markusaugust.streamlord.html"

/**
 * The edit that imports [name] of [pkg]: after the last import, else after the package line, else
 * after what heads the file ([fileHeaderEnd]), in the file's own line break. None when the file has
 * the import, imports the whole package, or is in it. Only code counts, so an `import` line in a
 * raw string is not one.
 */
internal fun importEdits(
    src: String,
    pkg: String,
    name: String,
): List<Edit> {
    val facts = fileFacts(src)
    val fq = "$pkg.$name"
    return facts.importEdits.getOrPut(fq) {
        val imports = keywordLines(src, facts.code, "import")
        val pkgLine = keywordLines(src, facts.code, "package").firstOrNull()
        val target = { line: KeywordLine ->
            src
                .substring(line.start, line.codeEnd)
                .trim()
                .removeSuffix(";")
                .trim()
        }
        val imported = imports.any { target(it) == fq || target(it) == "$pkg.*" } || pkgLine?.let(target) == pkg
        if (imported) return@getOrPut emptyList()
        val nl = lineBreak(src)
        val last = imports.lastOrNull()
        if (last != null) return@getOrPut listOf(Edit(last.end, last.end, "${nl}import $fq"))
        if (pkgLine != null) return@getOrPut listOf(Edit(pkgLine.end, pkgLine.end, "$nl${nl}import $fq"))
        val head = fileHeaderEnd(src, facts.code)
        if (head == 0) return@getOrPut listOf(Edit(0, 0, "import $fq$nl$nl"))
        // A blank line after the header and one before the code that follows, as Kotlin style has it.
        var k = head
        var breaks = 0
        while (k < src.length && isSpace(src[k])) if (src[k++] == '\n') breaks++
        listOf(Edit(head, head, "$nl${nl}import $fq" + if (breaks < 2 && k < src.length) nl else ""))
    }
}

/** The line break [src] writes: `\r\n` when its first line ends so, else `\n`. */
private fun lineBreak(src: String): String {
    val at = src.indexOf('\n')
    return if (at > 0 && src[at - 1] == '\r') "\r\n" else "\n"
}

/** Where a keyword's line goes on: just past the keyword, where its code ends before a comment, and where the line ends before its break. */
private class KeywordLine(
    val start: Int,
    val codeEnd: Int,
    val end: Int,
)

/**
 * Each place [keyword], a hard keyword such as `import` or `package`, stands in the code of [src]
 * as a word, wherever on its line: after a block comment or a file annotation too. A line ends at `\n` alone,
 * as Kotlin reads it, and before the `\r` of a `\r\n`.
 */
private fun keywordLines(
    src: String,
    code: BooleanArray,
    keyword: String,
): List<KeywordLine> {
    val out = ArrayList<KeywordLine>()
    var k = src.indexOf(keyword)
    while (k >= 0) {
        val after = k + keyword.length
        val before = src.getOrNull(k - 1)
        val word = before == null || !(before.isLetterOrDigit() || before == '_' || before == '.' || before == '`')
        if (code[k] && word && src.getOrNull(after)?.let(::isSpace) == true) {
            var end = src.indexOf('\n', after).let { if (it < 0) src.length else it }
            if (end > after && src[end - 1] == '\r') end--
            var codeEnd = after
            while (codeEnd < end && code[codeEnd]) codeEnd++
            out += KeywordLine(after, codeEnd, end)
        }
        k = src.indexOf(keyword, k + 1)
    }
    return out
}

/**
 * Where what heads a file with no package line and no import ends: a `#!` line, `@file:`
 * annotations, and comments with a blank line after them or more of the header, such as a
 * licence. Comments with no blank line between them are one, so a licence in `//` lines stays
 * whole; one right above code documents that code and is not part of it. 0 when there is none.
 */
private fun fileHeaderEnd(
    src: String,
    code: BooleanArray,
): Int {
    var end = 0
    if (src.startsWith("#!")) {
        end = src.indexOf('\n').let { if (it < 0) src.length else it }
        if (end > 0 && src[end - 1] == '\r') end--
    }
    val comment = { at: Int -> !code[at] && (src.startsWith("//", at) || src.startsWith("/*", at)) }
    var k = end
    while (true) {
        while (k < src.length && isSpace(src[k])) k++
        if (k >= src.length) return end
        if (code[k] && src.startsWith("@file:", k)) {
            k = annotationEnd(src, code, k + 6)
            end = k
            continue
        }
        if (!comment(k)) return end
        var c = k
        var n: Int
        var breaks: Int
        while (true) {
            // A line comment's mask ends at its line break; a block comment's after its `*/`.
            while (c < src.length && !code[c]) c++
            while (c > k && isSpace(src[c - 1])) c--
            n = c
            breaks = 0
            while (n < src.length && isSpace(src[n])) if (src[n++] == '\n') breaks++
            if (n < src.length && breaks < 2 && comment(n)) {
                c = n
                continue
            }
            break
        }
        if (breaks < 2 && n < src.length && !src.startsWith("@file:", n)) return end
        end = c
        k = c
    }
}

/** The spaces and line breaks the head of a file is read past: ASCII only. */
private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

/** Just past the annotation whose name starts at [from], after `@file:`: a name and its arguments, or a bracketed list. */
private fun annotationEnd(
    src: String,
    code: BooleanArray,
    from: Int,
): Int {
    var k = from
    while (k < src.length && (src[k] == ' ' || src[k] == '\t')) k++
    if (k < src.length && src[k] != '[') {
        while (k < src.length && (src[k].isLetterOrDigit() || src[k] == '_' || src[k] == '.')) k++
        var a = k
        while (a < src.length && (src[a] == ' ' || src[a] == '\t')) a++
        if (a >= src.length || src[a] != '(') return k
        k = a
    }
    if (k >= src.length) return k
    var depth = 0
    while (k < src.length) {
        if (code[k]) {
            if (src[k] == '(' || src[k] == '[') {
                depth++
            } else if (src[k] == ')' || src[k] == ']') {
                if (--depth == 0) return k + 1
            }
        }
        k++
    }
    return k
}

/** Where each `%s` of `interpolate`'s format starts in [text], as core counts them: `%%` is one per cent sign and never a placeholder. */
internal fun percentPlaceholders(text: String): List<Int> {
    val found = ArrayList<Int>()
    var i = 0
    while (i < text.length - 1) {
        if (text[i] == '%' && (text[i + 1] == 's' || text[i + 1] == '%')) {
            if (text[i + 1] == 's') found += i
            i += 2
            continue
        }
        i++
    }
    return found
}

/**
 * The format with each `%s` read as a placeholder like a Kotlin template, [PLACEHOLDER], mapped back to
 * the source, and where each `%s` now starts. `<%s` is then a tag whose name is a placeholder, not a
 * template engine's `<%`.
 */
internal fun withPercentPlaceholders(
    s: KotlinString,
    placeholders: List<Int>,
): Pair<KotlinString, List<Int>> {
    val text = StringBuilder()
    val map = ArrayList<Int>()
    val at = ArrayList<Int>()
    val set = placeholders.toHashSet()
    var i = 0
    while (i < s.text.length) {
        if (i in set) {
            at += text.length
            text.append(PLACEHOLDER)
            repeat(PLACEHOLDER.length) { map += s.map[i] }
            i += 2
            continue
        }
        text.append(s.text[i])
        map += s.map[i]
        i++
    }
    map += s.map[s.text.length]

    fun shift(d: Int) = d + (PLACEHOLDER.length - 2) * countBelow(placeholders, d)
    val format =
        s.copy(
            text = text.toString(),
            map = map.toIntArray(),
            interpolations = s.interpolations.map { it.copy(decodedStart = shift(it.decodedStart)) },
        )
    return format to at
}

/** How many of the ascending [sorted] are below [d]. */
internal fun countBelow(
    sorted: List<Int>,
    d: Int,
): Int {
    var lo = 0
    var hi = sorted.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (sorted[mid] < d) lo = mid + 1 else hi = mid
    }
    return lo
}
