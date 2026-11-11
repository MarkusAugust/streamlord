package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.groupRange
import io.github.markusaugust.streamlord.core.regex
import kotlin.concurrent.Volatile

/*
 * The pieces of the interpolation judgement that are about Kotlin rather than about Datastar:
 * which values are joined into an HTML string, which of them the analysis can see are safe or
 * are markup themselves, what the text looks like once `trimMargin` has run, and the rewrite to
 * `interpolate`. Where a placeholder lands is core's `holeLandings`.
 */

private val TRIM_MARGIN_AFTER = regex("""^\s*\.\s*trimMargin\s*\(\s*(?:"([^"\\\n]*)")?\s*\)""")
private val TRIM_CALL_AFTER = regex("""^\s*\.\s*trim(?:Indent|Margin)\s*\(""")
private val IDENT = regex("""[\p{L}_][\p{L}\p{Nd}_]*""")
private val LITERAL = regex("""(?:-?\d[\d_]*(?:\.\d+)?[LlFfDd]?|true|false|null)""")
private val STRING_LITERAL = regex(""""(?:[^"\\$]|\\.)*"""")

private val UNSIGNED_SUFFIX = regex("""[uU][lL]?$""")
private val MARKUP_CALL_START = regex("""^(?:interpolate|Trusted)\s*\(""")
private val UNQUOTED_VALUE = regex("""=[ \t\n\u000C\r]*([^ \t\n\u000C\r"'=<>`]*)\z""")
private val WORD_CHAR = regex("""[\p{L}\p{Nd}_]""")

/** What may end an operand of `+` at the top level: an argument or statement boundary, or an operator that binds less tightly. */
private val OPERAND_END = regex("""^(?:[,)\]};]|\?:|&&|\|\||==|!=|<=|>=|\.\.)""")

/** The words that end an operand, as words of their own: `photo` is a name, not `pho` and `to`. */
private val OPERAND_END_WORDS = listOf("to", "else")

/** An expression as a message quotes it: on one line, and cut short past 30 characters. */
internal fun shortened(text: String): String {
    val e = text.trim().replace(SPACES, " ")
    return if (e.length <= 30) e else e.substring(0, 29) + "…"
}

private val SPACES = regex("""\s+""")

/** How a hole is named in a message: `$name`, `${expr}` shortened, or an operand of `+` as the value it is. */
internal fun holeDisplay(
    ip: Interpolation,
    dollars: Int,
): String {
    if (ip.kind == InterpolationKind.SIMPLE) return "$".repeat(dollars) + ip.text
    if (ip.kind == InterpolationKind.OPERAND) return "the value `${shortened(ip.text)}`"
    return "$".repeat(dollars) + "{" + shortened(ip.text) + "}"
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
internal fun literalOnly(expr: String): Boolean =
    (branches(expr) ?: listOf(expr)).all { LITERAL.matchEntire(it.trim()) != null || plainString(it.trim()) }

private val QUOTE_OPENING = regex("""^\$*"""")

/** [t] read as one whole string literal, its templates and all, or null when it is anything more or less. */
private fun stringLiteral(t: String): KotlinString? {
    if (!QUOTE_OPENING.containsMatchIn(t)) return null
    return readKotlinStringAt(t, 0)?.takeIf { !it.unterminated && it.end == t.length }
}

/** Whether [t] is one string literal without a template: `"x"`, and `"$"` too, whose dollar opens none. */
private fun plainString(t: String): Boolean = STRING_LITERAL.matchEntire(t) != null || stringLiteral(t)?.interpolations?.isEmpty() == true

private fun wordAt(
    e: String,
    at: Int,
    w: String,
): Boolean =
    e.startsWith(w, at) &&
        WORD_CHAR.matchEntire(e.getOrNull(at - 1)?.toString() ?: "") == null &&
        WORD_CHAR.matchEntire(e.getOrNull(at + w.length)?.toString() ?: "") == null

/** Just past the string or character literal that opens at [at], templates and all. */
private fun literalEnd(
    e: String,
    at: Int,
): Int {
    if (e[at] == '\'') {
        val close = e.indexOf('\'', at + (if (e.getOrNull(at + 1) == '\\') 3 else 2))
        return if (close < 0) e.length else close + 1
    }
    val raw = e.startsWith("\"\"\"", at)
    var j = at + (if (raw) 3 else 1)
    while (j < e.length) {
        if (!raw && e[j] == '\\') {
            j += 2
        } else if (e.startsWith("\${", j)) {
            var depth = 1
            j += 2
            while (j < e.length && depth > 0) {
                if (e[j] == '"' || e[j] == '\'') {
                    j = literalEnd(e, j)
                    continue
                }
                if (e[j] == '{') {
                    depth++
                } else if (e[j] == '}') {
                    depth--
                }
                j++
            }
        } else if (if (raw) e.startsWith("\"\"\"", j) else e[j] == '"') {
            return j + (if (raw) 3 else 1)
        } else {
            j++
        }
    }
    return e.length
}

/** How deep in brackets each character of [e] stands, a bracket counting as outside itself; -1 inside a literal. */
private fun depths(e: String): IntArray {
    val out = IntArray(e.length) { -1 }
    var depth = 0
    var i = 0
    while (i < e.length) {
        val c = e[i]
        if (c == '"' || c == '\'') {
            i = literalEnd(e, i)
            continue
        }
        if (c == ')' || c == ']' || c == '}') depth--
        out[i] = depth
        if (c == '(' || c == '[' || c == '{') depth++
        i++
    }
    return out
}

/** Just past the bracket that closes the one at [open], or -1. */
private fun closing(
    e: String,
    d: IntArray,
    open: Int,
): Int {
    for (k in open + 1 until e.length) if (d[k] == d[open] && e[k] in ")]}") return k + 1
    return -1
}

/** A branch as the value it gives: a block of one expression is that expression. */
private fun unblock(v: String): String {
    val t = v.trim()
    return if (t.startsWith("{") && closing(t, depths(t), 0) == t.length) t.substring(1, t.length - 1).trim() else t
}

/**
 * The values an `if` or a `when` can result in, as written, a branch that is itself one read in
 * turn: `if (b) "x" else q` gives `"x"` and `q`. Null when the expression is neither, or one this
 * reading cannot take apart.
 */
internal fun branches(expr: String): List<String>? {
    val e = expr.trim()
    val d = depths(e)

    fun expand(v: String): List<String> {
        val t = unblock(v)
        return branches(t) ?: listOf(t)
    }

    fun skipSpace(from: Int): Int {
        var k = from
        while (k < e.length && e[k].isWhitespace()) k++
        return k
    }
    if (wordAt(e, 0, "if")) {
        val open = skipSpace(2)
        if (e.getOrNull(open) != '(') return null
        val close = closing(e, d, open)
        if (close < 0) return null
        for (k in close until e.length) {
            if (d[k] == 0 && wordAt(e, k, "else")) return expand(e.substring(close, k)) + expand(e.substring(k + 4))
        }
        return null
    }
    if (wordAt(e, 0, "when")) {
        var open = skipSpace(4)
        if (e.getOrNull(open) == '(') {
            open = closing(e, d, open)
            if (open < 0) return null
            open = skipSpace(open)
        }
        if (e.getOrNull(open) != '{' || closing(e, d, open) != e.length) return null
        val values = ArrayList<String>()
        var entry = open + 1
        var arrow = -1
        var k = open + 1
        while (k < e.length) {
            if (d[k] == 1 && arrow < 0 && e.startsWith("->", k)) {
                arrow = k
                k += 2
                continue
            }
            if (k == e.length - 1 || (d[k] == 1 && (e[k] == ';' || e[k] == '\n'))) {
                if (arrow >= 0) {
                    values += expand(e.substring(arrow + 2, k))
                } else if (e.substring(entry, k).isNotBlank()) {
                    return null
                }
                arrow = -1
                entry = k + 1
            }
            k++
        }
        return values.takeIf { it.isNotEmpty() }
    }
    return null
}

/** A name the file declares, and the stretch of it where the name means that declaration. */
internal class Declaration(
    /** Where the name is written. */
    val at: Int,
    /** Where the declaration can first be seen: the name for a local, the scope's opening bracket otherwise. */
    val start: Int,
    val type: String?,
    /** The number or boolean literal it is set to. */
    val literal: String? = null,
    /** The string literal a `const val` is set to, as written, when it holds no template. */
    val text: String? = null,
    val constant: Boolean = false,
    val html: Boolean = false,
    /** Whether it is set to a string literal without a template, which makes it a String. */
    val string: Boolean = false,
    /** Whether it is set to a call to `interpolate` or `Trusted` and nothing more: markup. */
    val markup: Boolean = false,
    /** What it is set to the `.text` of, `interpolate(...)` or a name: markup held as a String when that is an Html. */
    val textOf: String? = null,
    /** What the brackets after its type hold, `Html` of `List<Html>`. */
    val typeArguments: String? = null,
    /** Where the value a `val` is set to starts, when it is set to more than a literal; -1 otherwise. */
    val valueAt: Int = -1,
    /** Whether another file can mean it by its bare name: a `val` or `var` at top level, in a class or a constructor, and not private. */
    val shared: Boolean = false,
    /** Whether it is set to a call to `Trusted` and nothing more, which interpolate lets in wherever it lands. */
    val trusted: Boolean = false,
    /** The text a `val` set to `Trusted("...")` writes, the literal as the Kotlin compiler reads it. */
    val trustedText: String? = null,
    /** Whether the call it is set to names core's own under an alias the file imports, which no other import can take. */
    val aliased: Boolean = false,
) {
    /** Just past where it can last be seen. */
    var end: Int = Int.MAX_VALUE
}

/**
 * What the analysis needs of a whole file, read once: where the code is (template code included),
 * the names it declares with their scopes, and whether `interpolate` is imported.
 */
internal class FileFacts(
    val src: String,
    /** `true` where the character is code: outside literals and comments, or in a template's code. */
    val code: BooleanArray,
    val names: Map<String, List<Declaration>>,
) {
    /** The edit that imports each name the fixes write, by its qualified name, empty where the file has the import. */
    val importEdits: MutableMap<String, List<Edit>> = sharedMap()

    /** The helpers a hole may call that no import from elsewhere shadows, whether or not the file uses the DSL. */
    @Volatile
    var helpers: Set<String>? = null

    /** Where each call of a name of core's domain starts, by the name. */
    val coreCalls: MutableMap<String, List<Int>> = sharedMap()

    /** Whether something other than core's domain takes each name in this file, by the name ([shadowedInFile]). */
    val shadowed: MutableMap<String, Boolean> = sharedMap()

    /** The same with no star import counted, for what a name means here rather than what a fix may add ([shadowsCore]). */
    val taken: MutableMap<String, Boolean> = sharedMap()

    /** The names the file imports names of core's domain under, each with the name it stands for ([coreAliases]). */
    @Volatile
    var aliases: Map<String, String>? = null

    /** Whether the file's imports show it uses the DSL: what decides when the host does not know. */
    val usesDsl: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) { USES_HTML_DSL.containsMatchIn(src) }

    /** The type parameters the file declares, each with whether a value of it may be null. */
    @Volatile
    var typeParameters: Map<String, Boolean>? = null
}

@Volatile
private var lastFacts: FileFacts? = null

/** The facts of [src], kept for the last file read: every hole of a file asks for them. */
internal fun fileFacts(
    src: String,
    lex: KotlinLex? = null,
): FileFacts {
    lastFacts?.let { if (it.src == src) return it }
    val l = lex ?: lexKotlin(src)
    val code = codeMask(src, l.strings, l.mask)
    return FileFacts(src, code, declarations(src, code)).also { lastFacts = it }
}

/** The names a Kotlin file declares that another file can mean by the bare name: its `const val`s, and every other `val` or `var`. */
private class VisibleNames(
    val src: String,
    val constants: Set<String>,
    val others: Set<String>,
)

@Volatile
private var lastVisible: VisibleNames? = null

private fun visibleNames(src: String): VisibleNames {
    lastVisible?.let { if (it.src == src) return it }
    val names =
        lastFacts?.takeIf { it.src == src }?.names
            ?: lexKotlin(src).let { declarations(src, codeMask(src, it.strings, it.mask)) }
    val constants = HashSet<String>()
    val others = HashSet<String>()
    for ((name, list) in names) {
        for (d in list) if (d.shared) (if (d.constant) constants else others) += name
    }
    return VisibleNames(src, constants, others).also { lastVisible = it }
}

/**
 * The `const val` names a Kotlin file declares that other files can see: top level or in an
 * object or a class, and not private, `internal` counting as seen. What [projectConstants] gathers.
 */
public fun constantNames(src: String): Set<String> = visibleNames(src).constants

/**
 * The other names a Kotlin file declares that other files can see: a `val` or `var` that is not
 * a `const`, at top level, in a class or in a constructor, getters included. A parameter or a
 * local is seen in this file only.
 */
public fun otherDeclaredNames(src: String): Set<String> = visibleNames(src).others

/**
 * The constants of a project whose Kotlin files are [sources], to pass as [AnalyzeOptions.constants]:
 * each file's [constantNames], less every name some file declares as anything else another file
 * could mean by it.
 */
public fun projectConstants(sources: Iterable<String>): Set<String> {
    val out = HashSet<String>()
    val others = HashSet<String>()
    for (src in sources) {
        val v = visibleNames(src)
        out += v.constants
        others += v.others
    }
    return out - others
}

/** The lexer's mask with the code of every `${...}` template marked as code, its braces too. */
private fun codeMask(
    src: String,
    strings: List<KotlinString>,
    mask: BooleanArray,
): BooleanArray {
    val code = mask.copyOf()
    // Outermost first, so a literal inside a template is blanked before its own templates are marked.
    for (s in withNested(strings)) {
        for (ip in s.interpolations) {
            if (ip.kind != InterpolationKind.BRACED || src.getOrNull(ip.end - 1) != '}') continue
            val open = src.indexOf('{', ip.start)
            val inner = lexKotlin(src.substring(open + 1, ip.end - 1)).mask
            inner.copyInto(code, open + 1)
            code[open] = true
            code[ip.end - 1] = true
        }
    }
    return code
}

private const val NAME = """[\p{L}_][\p{L}\p{Nd}_]*"""

// A name in backticks too, as `` `Page` ``, which means the same name without them.
private const val TYPE = """(?:[\p{L}_][\p{L}\p{Nd}_.]*|`[^`\n]+`)(?:\.(?:[\p{L}_][\p{L}\p{Nd}_]*|`[^`\n]+`))*\??"""
private val VAL_AFTER =
    regex(
        """\s*(?:<[^<>]*>\s*)?((?:$NAME\s*\.\s*)*)($NAME)(?:\s*:\s*($TYPE))?""" +
            """(?:\s*=\s*(?:(-?\d[\d_]*(?:\.\d+)?(?:[uU][lL]?|[LlFfDd])?|true|false)|("(?:[^"\\$\n]|\\[^$])*"))(?=[ \t]*(?:[\n\r;})\],]|//|$)))?""",
    )
private val DESTRUCTURED = regex("""\s*\(([^()]*)\)""")
private const val TYPE_ARGUMENTS = """\s*<((?:[^<>]|<[^<>]*>)*)>"""
private val PARAMETER =
    regex("""^\s*(?:@[\p{L}_][\p{L}\p{Nd}_.]*(?:\s*\([^)]*\))?\s*)*((?:\p{L}+\s+)*?)($NAME)\s*:\s*($TYPE)?(?:$TYPE_ARGUMENTS)?""")
private val TYPE_ARGUMENTS_AFTER = regex(TYPE_ARGUMENTS)
private val VALUE_AFTER = regex("""\s*=(?!=)\s*""")
private val LAMBDA_PARAMETERS =
    regex(
        """\s*((?:\([^()]*\)|$NAME)(?:\s*:\s*[\p{L}\p{Nd}_.<>?`]+)?(?:\s*,\s*(?:\([^()]*\)|$NAME)(?:\s*:\s*[\p{L}\p{Nd}_.<>?`]+)?)*)\s*->""",
    )
private val FOR_VARIABLE = regex("""\s*(\([^()]*\)|$NAME)(?:\s*:\s*($TYPE))?\s+in\b""")
private val NAMES = regex("""($NAME)(?:\s*:\s*($TYPE))?""")
private val WHOLE_TYPE = regex(TYPE)
private val CONST_BEFORE = regex("""\bconst[ \t]+(?:\p{L}+[ \t]+)*$""")
private val PRIVATE_BEFORE = regex("""\bprivate[ \t]+(?:\p{L}+[ \t]+)*$""")
private val PROPERTY_MODIFIERS = regex("""\b(?:val|var)\b""")
private val PRIVATE_MODIFIER = regex("""\bprivate\b""")
private val HTML_BEFORE = regex("""@Language\(\s*"html"\s*\)\s*(?:\p{L}+\s+)*$""", RegexOption.IGNORE_CASE)
private val HTML_ANNOTATION = regex("""@Language\(\s*"html"\s*\)""", RegexOption.IGNORE_CASE)
private val VARARG = regex("""\bvararg\b""")
private val LAMBDA_NAME = regex("""^\S+?(?=\s*(?::|$))""")
private val LAMBDA_TYPE = regex(""":\s*(.+)$""")
private val LINE_GOES_ON_BEFORE = regex("""[-=+*/%&|?:.,(<>!]""")
private val LINE_GOES_ON_AFTER = regex("""^(?:[-.?+*/%&|:]|else\b)""")

/** The words that start a declaration of their own, ending a function's expression body. */
private val DECLARATION_WORDS = setOf("fun", "val", "var", "class", "interface", "object", "init", "constructor", "typealias")

/** Names whose declarations wait for their scope's end: a function's parameters until its body is found. */
private class PendingScope(
    val decls: List<Declaration>,
    val isFun: Boolean,
) {
    /** `after`: the parameters closed; `expr`: an expression body; `stmt`: a loop body without braces. */
    var state: String = "after"

    /** Whether an expression body has begun: `=` then a line break goes on with the next line. */
    var started: Boolean = false
}

private class ScopeFrame(
    val open: Int,
    var kind: String,
) {
    /** Declarations whose scope ends where this frame closes. */
    val ends = ArrayList<Declaration>()
    val commas = ArrayList<Int>()
    var pending: PendingScope? = null
    var fn = false
    var cls = false
    var whenBlock = false
    var loop = false

    /** The parameters of a class's constructor, waiting for its body. */
    var ctor: List<Declaration>? = null

    /** Whether the class declared here is private. */
    var privateClass = false

    /** Whether what this frame declares is out of other files' sight: a private class's body, or a local one's. */
    var hidden = false
}

private fun isWordPart(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

/** Whether the line break at [at] ends an expression: nothing before it or after it carries the expression on. */
private fun lineEnds(
    src: String,
    code: BooleanArray,
    at: Int,
): Boolean {
    var k = at - 1
    while (k >= 0 && (!code[k] || src[k].isWhitespace())) k--
    if (k >= 0 && LINE_GOES_ON_BEFORE.matchEntire(src[k].toString()) != null) return false
    k = at + 1
    while (k < src.length && (!code[k] || src[k].isWhitespace())) k++
    return !LINE_GOES_ON_AFTER.containsMatchIn(src.substring(k, minOf(src.length, k + 5)))
}

private val MARKUP_INITIALISER = regex("""\s*=\s*(`?)([\p{L}_][\p{L}\p{Nd}_]*)\1\s*\(""")
private val STATEMENT_END = regex("""^[ \t]*(?:[\n\r;})\],]|//|$)""")

/**
 * Which of `interpolate` and `Trusted` what follows a declared name at [at] calls as the whole
 * value, `val row = interpolate(...)`, under its name or one of [aliases], where the call starts,
 * and whether it is called by an alias; null for any other value.
 */
private fun markupInitialiser(
    src: String,
    at: Int,
    aliases: Map<String, String>,
): Triple<String, Int, Boolean>? {
    val m = MARKUP_INITIALISER.matchAt(src, at) ?: return null
    val callee = MARKUP_INITIALISER.groupAt(src, m, 2)!!
    val name = aliases[callee.value] ?: callee.value
    if (name != "interpolate" && name != "Trusted") return null
    val open = m.range.last
    val e = src.substring(open, minOf(src.length, open + 20_000))
    val close = closing(e, depths(e), 0)
    if (close <= 0 || !STATEMENT_END.containsMatchIn(e.substring(close, minOf(e.length, close + 8)))) return null
    return Triple(name, callee.range.first - m.groupValues[1].length, name != callee.value)
}

private val TEXT_OF_NAME = regex("""\s*=\s*($NAME)\s*\.\s*text(?=[ \t]*(?:[\n\r;})\],]|//|$))""")
private val TEXT_OF_CALL = regex("""\s*=\s*interpolate\s*\(""")
private val TEXT_AFTER = regex("""^\s*\.\s*text""")

/** What a declared name at [at] is set to the `.text` of, when that is its whole value: `name` of `= name.text`, or `interpolate(...)`. */
private fun textInitialiser(
    src: String,
    at: Int,
): String? {
    TEXT_OF_NAME.matchAt(src, at)?.let { return it.groupValues[1] }
    val call = TEXT_OF_CALL.matchAt(src, at) ?: return null
    val open = call.range.last
    val e = src.substring(open, minOf(src.length, open + 20_000))
    val close = closing(e, depths(e), 0)
    if (close < 0) return null
    val text = TEXT_AFTER.find(e.substring(close, minOf(e.length, close + 16))) ?: return null
    val after = close + text.value.length
    if (!STATEMENT_END.containsMatchIn(e.substring(after, minOf(e.length, after + 8)))) return null
    return src.substring(at + call.value.indexOf("interpolate"), open + close)
}

/** `text` cut at each comma outside brackets. */
private fun splitTopLevel(text: String): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var from = 0
    for ((k, c) in text.withIndex()) {
        when {
            c == '(' || c == '<' -> {
                depth++
            }

            c == ')' || c == '>' -> {
                depth--
            }

            c == ',' && depth == 0 -> {
                out += text.substring(from, k)
                from = k + 1
            }
        }
    }
    out += text.substring(from)
    return out
}

/**
 * Every name the file declares, read from the code alone: a `val` or `var` (destructured too), a
 * parameter of a function, a constructor or a lambda, and a loop variable, each with the stretch
 * of the file where it is the one the name means. A named argument, `list(page = 1)`, declares
 * nothing. Sorted by where each scope starts.
 */
private fun declarations(
    src: String,
    code: BooleanArray,
): Map<String, List<Declaration>> {
    val names = HashMap<String, ArrayList<Declaration>>()
    val aliases = aliasesIn(src, code)

    fun add(
        name: String,
        d: Declaration,
    ): Declaration {
        if (name != "_") names.getOrPut(name) { ArrayList() } += d
        return d
    }
    val stack = arrayListOf(ScopeFrame(-1, "top"))

    /** Whether the members of the class declared in [f] are out of other files' sight. */
    fun classHidden(f: ScopeFrame) = f.hidden || f.privateClass || (f.kind != "top" && f.kind != "class")

    fun settle(
        p: PendingScope,
        end: Int,
    ) {
        for (d in p.decls) d.end = end
    }

    fun endPending(
        f: ScopeFrame,
        at: Int,
    ) {
        f.pending?.let { settle(it, at) }
        f.pending = null
        f.ctor?.forEach { it.end = at }
        f.ctor = null
    }

    /** Each parameter between the brackets of a function or a constructor; [visible] for a constructor other files can see the properties of. */
    fun parameters(
        f: ScopeFrame,
        close: Int,
        visible: Boolean = false,
    ): List<Declaration> {
        val out = ArrayList<Declaration>()
        val cuts = listOf(f.open) + f.commas + close
        for (k in 0 until cuts.size - 1) {
            val text = src.substring(cuts[k] + 1, cuts[k + 1])
            val m = PARAMETER.find(text) ?: continue
            val vararg = VARARG.containsMatchIn(m.groupValues[1])
            val type = if (vararg) null else m.groups[3]?.value
            val arguments = if (vararg) null else m.groups[4]?.value
            out +=
                add(
                    m.groupValues[2],
                    Declaration(
                        cuts[k] + 1 + m.range.first,
                        f.open,
                        type,
                        html = HTML_ANNOTATION.containsMatchIn(text),
                        typeArguments = arguments,
                        shared =
                            visible &&
                                PROPERTY_MODIFIERS.containsMatchIn(m.groupValues[1]) &&
                                !PRIVATE_MODIFIER.containsMatchIn(m.groupValues[1]),
                    ),
                )
        }
        return out
    }

    /** The names of a list such as `a, b: Int`, and the type of each. */
    fun listed(list: String): List<Pair<String, String?>> = NAMES.findAll(list).map { it.groupValues[1] to it.groups[2]?.value }.toList()
    var i = 0
    while (i < src.length) {
        if (!code[i]) {
            i++
            continue
        }
        val c = src[i]
        val f = stack.last()
        if ((c.isLetter() || c == '_') && !isWordPart(src.getOrNull(i - 1))) {
            var end = i + 1
            while (end < src.length && isWordPart(src[end])) end++
            val word = src.substring(i, end)
            // `Row::class` names a class and declares none.
            val reference = src.startsWith("::", i - 2)
            val ctorOfClass = word == "constructor" && f.cls
            if (word in DECLARATION_WORDS && !reference && !ctorOfClass) {
                // A new declaration ends a function's expression body, a loop body without braces, or a class without one.
                endPending(f, i)
                f.cls = false
            }
            when {
                reference -> {}

                word == "fun" || (word == "constructor" && !ctorOfClass) -> {
                    f.fn = true
                }

                word == "class" || word == "interface" || word == "object" -> {
                    f.cls = true
                    f.privateClass = PRIVATE_BEFORE.containsMatchIn(src.substring(maxOf(0, i - 80), i))
                }

                word == "when" -> {
                    f.whenBlock = true
                }

                word == "for" -> {
                    f.loop = true
                }

                (word == "val" || word == "var") && f.kind != "params" && f.kind != "ctor" -> {
                    val constant = CONST_BEFORE.containsMatchIn(src.substring(maxOf(0, i - 80), i))
                    val html = HTML_BEFORE.containsMatchIn(src.substring(maxOf(0, i - 160), i))
                    val local = f.kind != "top" && f.kind != "class"
                    val shared = !local && !f.hidden && !PRIVATE_BEFORE.containsMatchIn(src.substring(maxOf(0, i - 80), i))
                    val scopeStart = { at: Int ->
                        if (local) {
                            at
                        } else if (f.kind == "top") {
                            0
                        } else {
                            f.open
                        }
                    }
                    // `when (val x = ...)`: the scope is the one the brackets stand in.
                    val owner = if (f.kind == "paren" || f.kind == "for") stack.getOrNull(stack.size - 2) ?: f else f
                    val destructured = DESTRUCTURED.matchAt(src, end)
                    if (destructured != null) {
                        val listStart = end + destructured.value.indexOf('(') + 1
                        for (n in NAMES.findAll(destructured.groupValues[1])) {
                            val at = listStart + n.range.first
                            val d =
                                add(
                                    n.groupValues[1],
                                    Declaration(at, scopeStart(at), null, constant = constant, html = html, shared = shared),
                                )
                            if (owner.kind != "top") owner.ends += d
                        }
                    } else {
                        val m = VAL_AFTER.matchAt(src, end)
                        // `val String.shout`: an extension, read on a receiver and never by its name alone.
                        if (m != null && m.groupValues[1].isEmpty()) {
                            val at = VAL_AFTER.groupRange(src, m, 2)!!.first
                            val bare = m.groups[4] == null && m.groups[5] == null
                            val initialiser = if (bare) markupInitialiser(src, m.range.last + 1, aliases) else null
                            val markup = initialiser != null
                            val trusted = initialiser?.first == "Trusted"
                            val trustedText = if (trusted && word == "val") literalTrusted(src, initialiser.second) else null
                            val arguments = VAL_AFTER.groupRange(src, m, 3)?.let { TYPE_ARGUMENTS_AFTER.matchAt(src, it.last + 1) }
                            // The value of a `val` set once, which a name then stands for.
                            val valueAt =
                                if (word == "val" && bare && !markup) {
                                    VALUE_AFTER
                                        .matchAt(
                                            src,
                                            arguments?.range?.last?.plus(1) ?: (m.range.last + 1),
                                        )?.let { it.range.last + 1 }
                                        ?: -1
                                } else {
                                    -1
                                }
                            val d =
                                add(
                                    m.groupValues[2],
                                    Declaration(
                                        at,
                                        scopeStart(at),
                                        m.groups[3]?.value,
                                        m.groups[4]?.value,
                                        if (constant) m.groups[5]?.value else null,
                                        constant,
                                        html,
                                        m.groups[5] != null,
                                        markup,
                                        if (bare && !markup) textInitialiser(src, m.range.last + 1) else null,
                                        arguments?.groupValues?.get(1),
                                        valueAt,
                                        shared,
                                        trusted,
                                        trustedText,
                                        aliased = initialiser?.third == true,
                                    ),
                                )
                            if (owner.kind != "top") owner.ends += d
                        }
                    }
                }
            }
            i = end
            continue
        }
        if (c == '(' || c == '[' || c == '{') {
            val g = ScopeFrame(i, if (c == '{') "block" else "paren")
            if (c == '(' && f.fn) {
                g.kind = "params"
                f.fn = false
            } else if (c == '(' && f.cls && f.ctor == null) {
                g.kind = "ctor"
            } else if (c == '(' && f.loop) {
                g.kind = "for"
                f.loop = false
                val m = FOR_VARIABLE.matchAt(src, i + 1)
                if (m != null) {
                    val first = m.groupValues[1]
                    val list =
                        if (first.startsWith("(")) {
                            listed(first.substring(1, first.length - 1)).map { it.first to null }
                        } else {
                            listOf(first to m.groups[2]?.value)
                        }
                    g.pending = PendingScope(list.map { (name, type) -> add(name, Declaration(i + 1, i, type)) }, false)
                }
            } else if (c == '{') {
                f.fn = false
                f.loop = false
                val p = f.pending
                if (p != null && p.state == "after") {
                    // The body of the function or the loop whose parameters just closed.
                    g.ends += p.decls
                    f.pending = null
                } else if (f.cls) {
                    g.kind = "class"
                    f.cls = false
                    g.hidden = classHidden(f)
                    f.ctor?.let { g.ends += it }
                    f.ctor = null
                } else if (f.whenBlock) {
                    g.kind = "when"
                    f.whenBlock = false
                } else {
                    val m = LAMBDA_PARAMETERS.matchAt(src, i + 1)
                    if (m != null && (i + 1 until i + 1 + m.value.length).all { code[it] }) {
                        g.kind = "lambda"
                        for (part in splitTopLevel(m.groupValues[1])) {
                            val p0 = part.trim()
                            val list =
                                if (p0.startsWith("(")) {
                                    listed(p0.substring(1, p0.length - 1)).map { it.first to null }
                                } else {
                                    listOf(
                                        (LAMBDA_NAME.find(p0)?.value ?: p0) to
                                            LAMBDA_TYPE
                                                .find(p0)
                                                ?.groupValues
                                                ?.get(1)
                                                ?.trim(),
                                    )
                                }
                            for ((name, type) in list) {
                                g.ends += add(name, Declaration(i + 1, i, type?.takeIf { WHOLE_TYPE.matchEntire(it) != null }))
                            }
                        }
                    }
                }
            }
            stack += g
            i++
            continue
        }
        if (c == ')' || c == ']' || c == '}') {
            if (stack.size == 1) {
                i++
                continue
            }
            val g = stack.removeAt(stack.size - 1)
            val outer = stack.last()

            fun handOver(p: PendingScope) {
                outer.pending?.let { settle(it, i + 1) }
                outer.pending = p
            }
            if (g.kind == "for") {
                g.pending?.let { handOver(it) }
                g.pending = null
            }
            endPending(g, i + 1)
            for (d in g.ends) d.end = i + 1
            if (g.kind == "params") {
                handOver(PendingScope(parameters(g, i), true))
            } else if (g.kind == "ctor") {
                outer.ctor = parameters(g, i, !classHidden(outer))
            }
            i++
            continue
        }
        if (c == ',') {
            f.commas += i
            i++
            continue
        }
        val p = f.pending
        if (p != null) {
            when (p.state) {
                "after" -> {
                    if (c == '=' && src.getOrNull(i + 1) != '=' && p.isFun) {
                        p.state = "expr"
                    } else if (!p.isFun && !c.isWhitespace()) {
                        p.state = "stmt"
                    }
                }

                "stmt" -> {
                    if (c == '\n' || c == ';') endPending(f, i)
                }

                else -> {
                    if (c == ';' || (c == '\n' && p.started && lineEnds(src, code, i))) {
                        endPending(f, i)
                    } else if (!c.isWhitespace()) {
                        p.started = true
                    }
                }
            }
        }
        i++
    }
    for (f in stack) endPending(f, src.length)
    for (list in names.values) list.sortWith(compareBy<Declaration>({ it.start }, { it.at }))
    return names
}

/**
 * The declaration [name] means at [at]: of those whose scope holds [at], the innermost, and of
 * two in one scope the later. Null when the file declares no such name there.
 */
private fun declarationOf(
    src: String,
    name: String,
    at: Int,
): Declaration? {
    val list = fileFacts(src).names[name] ?: return null
    var lo = 0
    var hi = list.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (list[mid].start <= at) lo = mid + 1 else hi = mid
    }
    for (k in lo - 1 downTo 0) if (list[k].end > at) return list[k]
    return null
}

/**
 * Whether [name] is a constant the author wrote, as far as this file tells: one of the
 * workspace's `const val` names that the file declares as nothing else, or a `const val` of its own.
 */
internal fun constantAt(
    src: String,
    name: String,
    at: Int,
    constants: Set<String>,
): Boolean {
    declarationOf(src, name, at)?.let { return it.constant }
    if (name !in constants) return false
    return fileFacts(src).names[name].orEmpty().all { it.constant }
}

/**
 * The type the declaration [name] means at [at] gives it: a number or a boolean that cannot be
 * null (`count: Int`, `n: Number`, `val n = 3`), one that can (`count: Int?`), an unsigned number
 * (`u: UInt`, `val u = 3u`), a string (`name: String`), which is what may come from outside, or
 * core's `Html`, which is markup. Anything else is not known. [nonNull] reads the value as the
 * left side of `?:` gives it.
 */
internal fun declaredKind(
    src: String,
    name: String,
    at: Int,
    nonNull: Boolean = false,
    core: Boolean = true,
): ValueFact? {
    val d = declarationOf(src, name, at) ?: return null
    val literal = d.literal
    if (literal != null) {
        return when {
            literal == "true" || literal == "false" -> ValueFact.BOOLEAN
            UNSIGNED_SUFFIX.containsMatchIn(literal) -> ValueFact.UNSIGNED
            else -> ValueFact.NUMBER
        }
    }
    // Without core, Html, Trusted and interpolate are not Streamlord's, and nothing says what they hold.
    if (d.markup) return if (core && setToCore(src, d)) ValueFact.MARKUP else null
    val declared = d.type ?: return null
    // An Html or a Trusted the file takes from another package is no markup the analysis knows.
    if (markupTypeShadowed(src, declared)) return null
    return declaredFact(coreType(src, declared), nonNull, core)
}

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
private fun literalTrusted(
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
    if (IDENT.matchEntire(t) != null) return declarationOf(src, t, at)?.takeIf { setToCore(src, it) }?.trustedText
    return literalTrusted(asCoreCall(src, t, "Trusted") ?: return null, 0, whole = true)
}

/** Whether [name] at [at] is declared a `Trusted`, or set to `Trusted(...)` and nothing more: what interpolate lets in anywhere. */
private fun trustedName(
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
            t == "null" -> true
            IDENT.matchEntire(t) != null -> declarationOf(src, t, at)?.type?.let { it.endsWith("?") || nullableTypeParameter(src, it) } == true
            else -> "?." in t && "?:" !in t
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

/** One value a hole can result in, and whether it stands left of `?:`, which never gives null. */
internal class HoleValue(
    val text: String,
    val nonNull: Boolean,
)

/** The values a hole can result in: each branch of an `if` or a `when`, each side of `?:`. */
internal fun holeValues(expr: String): List<HoleValue> {
    val t = unparen(expr)
    branches(t)?.let { b -> return b.flatMap { holeValues(it) } }
    val sides = splitTop(t, "?:")
    if (sides.size > 1) {
        return sides.flatMapIndexed { k, v -> holeValues(v).map { HoleValue(it.text, it.nonNull || k < sides.size - 1) } }
    }
    return listOf(HoleValue(t, false))
}

/** The expression without the brackets around all of it. */
private fun unparen(expr: String): String {
    var t = expr.trim()
    while (t.startsWith("(") && closing(t, depths(t), 0) == t.length) t = t.substring(1, t.length - 1).trim()
    return t
}

private val OPERATOR_BEFORE = regex("""[-+*/%=<>!&|?:(,]$""")

/** [e] cut at each [op] outside brackets and literals; a `+` that is part of `++` or `+=`, or a sign, does not cut. */
private fun splitTop(
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

/** The calls of Streamlord's DSL that a hole may hold: `signal("count")`, `post("/x")`, each returning an expression the author wrote. */
private val HOLE_HELPERS: Set<String> =
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
    sdk: Sdk,
): List<String>? {
    val m = CALL_START.find(t) ?: return null
    val name = m.groupValues[1]
    val facts = fileFacts(src)
    if (!(sdk.htmlDsl ?: facts.usesDsl)) return null
    val helpers = facts.helpers ?: unshadowed(src, HOLE_HELPERS).also { facts.helpers = it }
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
    sdk: Sdk,
): Boolean {
    val a = arg.replaceFirst(ARGUMENT_NAME, "").trim()
    if (a.startsWith("*")) return false
    val kind = weakest(holeValues(a).map { valueKind(src, it.text, at, constants, it.nonNull, sdk) })
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
    return name in constants || fileFacts(src).names[name].orEmpty().any { it.constant }
}

/**
 * What the text shows of one value, a branch of an `if` or a `when` or a whole hole: a literal or
 * a constant, the author's own; a string template, as weak as its weakest hole; a call to one of
 * Streamlord's expression helpers, the author's code when its arguments are the author's own and
 * a String otherwise; or what the name's declaration says. Null where it shows nothing.
 */
internal fun valueKind(
    src: String,
    value: String,
    at: Int,
    constants: Set<String>,
    nonNull: Boolean = false,
    sdk: Sdk = Sdk.DEFAULT,
): ValueFact? {
    val t = value.trim()
    if (LITERAL.matchEntire(t) != null || STRING_LITERAL.matchEntire(t) != null || CHAR_LITERAL.matchEntire(t) != null) return ValueFact.CONSTANT
    stringLiteral(t)?.let { literal ->
        return weakest(
            literal.interpolations.flatMap { ip ->
                val values = if (ip.kind == InterpolationKind.SIMPLE) listOf(HoleValue(ip.text, false)) else holeValues(ip.text)
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
    if (IDENT.matchEntire(t) == null) return null
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
    )
private val NON_NULL_ASSERTION = regex("""\s*!!$""")
private val OPERAND_CHAR = regex("""[\p{L}\p{Nd}_.?!()\[\]{}"']""")

/**
 * What a call shows its result to be, by its name and what it is called on: `u.toLong()` or
 * `(a + b).toInt()` a number, `x.toString()` a String, `query.trim()` or `query.ifBlank { "none" }`
 * a String when `query` is one, `query!!.trim()` too. Null for a safe call, `u?.toLong()`, which may be null, and for a call on only part of
 * the value, `s + u.toInt()`.
 */
private fun callKind(
    src: String,
    t: String,
    at: Int,
    constants: Set<String>,
    sdk: Sdk = Sdk.DEFAULT,
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
private val QUOTE_START = regex("""^\$*"""")
private val TEXT_OF_VALUE = regex("""^([\s\S]*?)\s*\.\s*text$""")
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
private val PROPERTY_CHAIN = regex("""^[\p{L}_][\p{L}\p{Nd}_]*(?:\s*\??\.\s*[\p{L}_][\p{L}\p{Nd}_]*)*(?:!!)?$""")
private val MARKUP_CHARS = regex("""[<&]""")

/** Whether [t] is one string literal of HTML, or one with a trim call after it. */
private fun htmlLiteral(t: String): Boolean {
    val quote = QUOTE_START.find(t) ?: return false
    val at = quote.value.length - 1
    val end = literalEnd(t, at)
    val rest = t.substring(end)
    if (rest.isNotBlank() && TRIM_CALL.matchEntire(rest) == null) return false
    val content = t.substring(minOf(end, at + (if (t.startsWith("\"\"\"", at)) 3 else 1)), end)
    return opensWithTag(content, trimMargin = "trimMargin" in rest)
}

/** The call that ends [t], `receiver.name(args) { lambda }`. */
private class Call(
    val name: String,
    val receiver: String?,
    val args: String,
    val lambda: String?,
)

/** The call that ends [t], or null when [t] does not end in one. */
private fun lastCall(t: String): Call? {
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
 * Whether a hole's whole result is markup the author built, which escaping would print as tags:
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
    sdk: Sdk = Sdk.DEFAULT,
): Boolean {
    val values = holeValues(value)
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
    if (sdk.core &&
        textOf != null &&
        (
            coreCall(src, textOf, "interpolate") ||
                (IDENT.matchEntire(textOf) != null && declaredKind(src, textOf, at).isMarkup())
        )
    ) {
        return true
    }
    if (IDENT.matchEntire(t) != null) {
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

/** How many names deep a value is followed to what it is set to. */
private const val VALUE_DEPTH = 4

/**
 * Whether every element of a collection is markup: `listOf(a, b)` of markup, a `map { }` to
 * markup, a name declared a `List<Html>`, or one set to such a collection.
 */
private fun markupElements(
    src: String,
    receiver: String,
    at: Int,
    depth: Int,
    sdk: Sdk,
): Boolean {
    val t = receiver.trim()
    if (IDENT.matchEntire(t) != null) {
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
private fun initialiser(
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

/** Whether a hole's value is markup the author built; see [markupValue]. */
internal fun isMarkup(
    src: String,
    s: KotlinString,
    ip: Interpolation,
    sdk: Sdk = Sdk.DEFAULT,
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
    holeValues(value).any { v ->
        val t = v.text
        when {
            LITERAL.matchEntire(t) != null || CHAR_LITERAL.matchEntire(t) != null -> {
                false
            }

            IDENT.matchEntire(t) != null && markupText(src, t, at) -> {
                true
            }

            IDENT.matchEntire(t) != null -> {
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
 * Whether markup in a hole is a value `interpolate` writes as it stands in element text, an
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
        if (IDENT.matchEntire(t) != null) {
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
    if (holeValues(value).all {
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
        (IDENT.matchEntire(of) != null && declaredKind(src, of, declarationOf(src, name, at)!!.at).isMarkup())
}

/** Whether every value a hole can give is a name set to the `.text` of an Html: markup held as a String. */
internal fun markupTextValue(
    src: String,
    value: String,
    at: Int,
): Boolean = holeValues(value).all { IDENT.matchEntire(it.text) != null && markupText(src, it.text, at) }

/**
 * The fix for a value written into an attribute value without quotes: quotes around the whole
 * value, which the browser reads the same, so a space in the value no longer starts an attribute.
 * Null when the value holds a quote of its own, or spans literals joined with `+`.
 */
internal fun quoteFix(
    s: KotlinString,
    view: String,
    ip: Interpolation,
): Fix? {
    if (s.interpolations.any { it.kind == InterpolationKind.OPERAND }) return null
    val m = UNQUOTED_VALUE.find(view.substring(0, ip.decodedStart)) ?: return null
    val from = ip.decodedStart - m.groupValues[1].length
    var to = ip.decodedStart
    while (to < view.length && view[to] !in " \t\n\u000C\r>") {
        if (view[to] in "\"'=<`") return null
        to++
    }
    val quote = if (s.raw) "\"" else "\\\""
    val start = s.map[from]
    val end = s.map[to]
    return Fix("Quote the value", start, start, quote, listOf(Edit(end, end, quote)))
}

/** Whether `to` or `else` stands at [at] as a word of its own. */
private fun operandEndWordAt(
    src: String,
    at: Int,
): Boolean =
    OPERAND_END_WORDS.any { w ->
        src.startsWith(w, at) &&
            WORD_CHAR.matchEntire(src.getOrNull(at - 1)?.toString() ?: "") == null &&
            WORD_CHAR.matchEntire(src.getOrNull(at + w.length)?.toString() ?: "") == null
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

private const val DOMAIN = "io.github.markusaugust.streamlord.core.domain"

/** The title of the rewrite to `interpolate` where the hole is a value it escapes. */
internal const val ESCAPES_TITLE = "Build it with interpolate(...), which escapes the values"

/** Its title where the hole starts a URL, which escaping does not help but interpolate refuses when it would run. */
internal const val REFUSES_URL_TITLE = "Build it with interpolate(...), which refuses a URL that runs"

/**
 * The literal rewritten as a call to `interpolate`: each template becomes `%s` and its value an
 * argument, and each `%` the literal writes, as itself or as an escape, is written twice, so
 * `interpolate` prints it once. Made the first time it is read, and once for all the holes of the
 * literal that offer it.
 */
internal fun interpolateText(
    src: String,
    s: KotlinString,
): Lazy<String> = lazy(LazyThreadSafetyMode.PUBLICATION) { rewriteToInterpolate(src, s) }

private fun rewriteToInterpolate(
    src: String,
    s: KotlinString,
): String {
    // Where the literal writes a per cent sign, and how many characters of source spell it.
    val percents = HashMap<Int, Int>()
    for (i in s.text.indices) if (s.text[i] == '%') percents[s.map[i]] = s.map[i + 1] - s.map[i]
    val format = StringBuilder()

    fun copy(
        from: Int,
        to: Int,
    ) {
        var k = from
        while (k < to) {
            val n = percents[k] ?: 0
            if (n > 0) {
                format.append(src, k, k + n).append(src, k, k + n)
                k += n
            } else {
                format.append(src[k++])
            }
        }
    }
    var at = s.start
    for (ip in s.interpolations) {
        copy(at, ip.start)
        format.append("%s")
        at = ip.end
    }
    copy(at, s.end)
    val args = s.interpolations.map { if (it.kind == InterpolationKind.SIMPLE) it.text else it.text.trim() }
    // `.text`, the String the literal was: interpolate returns Html, and a fix that changed the type
    // would break whatever took the String.
    return "interpolate(" + (listOf(format.toString()) + args).joinToString(", ") + ").text"
}

/**
 * Whether a fix may write [name] of core's domain, `interpolate` or `Trusted`, in [src]: core is
 * there, no call of the name is one the host knows is somebody else's ([foreignCalls]), and nothing
 * else in the file takes the name.
 */
internal fun coreNameUsable(
    src: String,
    name: String,
    sdk: Sdk,
    foreignCalls: Set<Int>,
): Boolean {
    if (!sdk.core) return false
    val facts = fileFacts(src)
    if (foreignCalls.isNotEmpty() &&
        facts.coreCalls.getOrPut(name) { findCallSites(src, setOf(name)).map { it.nameStart } }.any { it in foreignCalls }
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
private fun aliasesIn(
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

// `List<out Html>` reads its elements as `List<Html>` does.
private val OUT_VARIANCE = regex("""^\s*out\s+""")

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
private fun setToCore(
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

/** The rewrite of the literal to `interpolate`, whose text [text] makes, with the import when the file has none. */
internal fun interpolateFix(
    src: String,
    s: KotlinString,
    text: Lazy<String>,
    title: String,
    preferred: Boolean,
): Fix = Fix.later(title, s.start, s.end, importEdits(src, DOMAIN, "interpolate"), preferred) { text.value }

/**
 * The hole made a `Trusted` value, `${Trusted(body)}`: markup the author vouches for, which the
 * analysis then reads as the author's own. With the import, when the file has none. Never
 * preferred: whether the value is markup only the author knows.
 */
internal fun trustedFix(
    src: String,
    s: KotlinString,
    ip: Interpolation,
    shown: String,
): Fix {
    val expr = if (ip.kind == InterpolationKind.SIMPLE) ip.text else ip.text.trim()
    // A value joined with `+` is wrapped where it stands; a template keeps the dollars its literal opens one with.
    val text = if (ip.kind == InterpolationKind.OPERAND) "Trusted($expr)" else "$".repeat(s.dollars) + "{Trusted($expr)}"
    return Fix("Mark $shown as markup", ip.start, ip.end, text, importEdits(src, DOMAIN, "Trusted"), preferred = false)
}

/** The calls that make a List, and those whose result is one, whatever they are called on; not those a String has too. */
private val LIST_MAKERS =
    setOf("listOf", "mutableListOf", "arrayListOf", "emptyList", "buildList", "listOfNotNull", "List", "MutableList", "ArrayList")
private val LIST_RESULTS =
    setOf(
        "toList",
        "toMutableList",
        "map",
        "mapNotNull",
        "mapIndexed",
        "flatMap",
        "filterNotNull",
        "sorted",
        "sortedBy",
        "sortedDescending",
        "sortedWith",
        "distinct",
        "chunked",
        "windowed",
        "zip",
    )

/** A declared List, as a declaration reads its type: the name, without what the brackets after it hold. */
private val LIST_TYPE = regex("""(?:kotlin\.collections\.)?(?:List|MutableList|ArrayList)\??""")

/** Whether every value a hole can give is the result of a call, which may be markup the author built: `${badge(user)}`. */
internal fun callResult(value: String): Boolean = holeValues(value).all { lastCall(it.text) != null }

/** Whether a value handed to `interpolate` is visibly a List: made by a call that makes one, or a name declared one. */
internal fun visiblyList(
    src: String,
    value: String,
    at: Int,
): Boolean {
    val t = value.trim()
    if (IDENT.matchEntire(t) != null) return LIST_TYPE.matchEntire(declarationOf(src, t, at)?.type ?: "") != null
    val call = lastCall(t) ?: return false
    return if (call.receiver == null) call.name in LIST_MAKERS else call.name in LIST_RESULTS
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

/** Where each `%s` of `interpolate`'s format starts in [text], as core counts them: `%%` is one per cent sign and never a hole. */
internal fun percentHoles(text: String): List<Int> {
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
 * The format with each `%s` read as a hole like a Kotlin template, [PLACEHOLDER], mapped back to
 * the source, and where each `%s` now starts. `<%s` is then a tag whose name is a hole, not a
 * template engine's `<%`.
 */
internal fun withPercentHoles(
    s: KotlinString,
    holes: List<Int>,
): Pair<KotlinString, List<Int>> {
    val text = StringBuilder()
    val map = ArrayList<Int>()
    val at = ArrayList<Int>()
    val set = holes.toHashSet()
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

    fun shift(d: Int) = d + (PLACEHOLDER.length - 2) * countBelow(holes, d)
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

/** What a value handed to `interpolate` is, as far as the text shows: what core's `admits` decides by. */
internal enum class ArgKind { STRING, NUMBER, UNSIGNED, BOOLEAN, NULL, CHAR, TRUSTED, MARKUP }

private val NUMBER_LITERAL = regex("""-?(?:0[xX][0-9A-Fa-f_]+|0[bB][01_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?)[LlFfDdUu]*""")
private val CHAR_LITERAL = regex("""'(?:[^'\\]|\\.[^']*)'""")
private val IF_START = regex("""^if\s*\(""")
private val TEXT_OF = regex("""^([\s\S]*?)\s*\.\s*text$""")

/** Whether [text] is one call to [name] whose argument list closes at its end: `Trusted(x)`, not `Trusted(x).html`. */
private fun wholeCall(
    text: String,
    name: String,
): Boolean {
    if (!regex("""^$name\s*\(""").containsMatchIn(text) || !text.endsWith(")")) return false
    val mask = lexKotlin(text).mask
    var depth = 0
    for (i in text.indexOf('(') until text.length) {
        if (!mask[i]) continue
        if (text[i] == '(') {
            depth++
        } else if (text[i] == ')') {
            depth--
            if (depth == 0) return i == text.length - 1
        }
    }
    return false
}

/**
 * The kind of a value handed to `interpolate`: a literal, `Trusted(...)`, a call to
 * `interpolate` (its Html), or a name the enclosing declaration gives a type. Null when the text
 * does not show it.
 */
internal fun valueKind(
    src: String,
    text: String,
    literal: Boolean,
    at: Int,
): ArgKind? {
    val t = text.trim()
    if (literal) return ArgKind.STRING
    if (NUMBER_LITERAL.matchEntire(t) != null) return if (UNSIGNED_SUFFIX.containsMatchIn(t)) ArgKind.UNSIGNED else ArgKind.NUMBER
    if (t == "true" || t == "false") return ArgKind.BOOLEAN
    if (t == "null") return ArgKind.NULL
    if (CHAR_LITERAL.matchEntire(t) != null) return ArgKind.CHAR
    if (IF_START.containsMatchIn(t) && literalOnly(t)) return if ('"' in t) ArgKind.STRING else null
    if (coreCall(src, t, "Trusted") || (IDENT.matchEntire(t) != null && trustedName(src, t, at))) return ArgKind.TRUSTED
    if (coreCall(src, t, "interpolate")) return ArgKind.MARKUP
    // The String of the Html: interpolate(...).text.
    val html = TEXT_OF.find(t)?.groupValues?.get(1)
    if (html != null && coreCall(src, html, "interpolate")) return ArgKind.STRING
    callKind(src, t, at, emptySet())?.argKind()?.let { return it }
    if (IDENT.matchEntire(t) == null) return null
    // `val name = "Gorvek"`: a String, though no type is written.
    val declared = declaredKind(src, t, at) ?: return if (declarationOf(src, t, at)?.string == true) ArgKind.STRING else null
    return declared.argKind()
}
