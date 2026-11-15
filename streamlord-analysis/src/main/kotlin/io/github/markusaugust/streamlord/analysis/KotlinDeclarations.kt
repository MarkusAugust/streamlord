package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.groupRange
import io.github.markusaugust.streamlord.core.client.regex
import kotlin.concurrent.Volatile

/*
 * What a Kotlin file declares, read from its text: each name with the stretch where it means that
 * declaration, its type or initialiser where the text writes one, the constants and the functions.
 */

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
    /** Every function the file declares, by its name. */
    val functions: Map<String, List<FunctionDeclaration>> = emptyMap(),
) {
    /**
     * Where each literal starts that the file hands straight to a parameter marked `@Language("HTML")`
     * of the function the call means, as far as the text tells: one called without a receiver, a
     * member of the class the call is in before one of the top level.
     */
    val htmlArguments: Set<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val marked = functions.filterValues { list -> list.any { it.html.isNotEmpty() } }.keys
        val out = HashSet<Int>()
        for (site in if (marked.isEmpty()) emptyList() else findCallSites(src, marked, lexKotlin(src))) {
            if (src.lastNonSpaceBefore(site.nameStart) == '.') continue
            val candidates = functions[site.name].orEmpty()
            val meant =
                candidates.filter { f -> f.owner?.let { site.nameStart in it } == true }.minByOrNull { it.owner!!.last - it.owner.first }
                    ?: candidates.firstOrNull { it.owner == null }
                    ?: continue
            site.positional.forEachIndexed { k, a -> if (k in meant.html) a.string?.let { out += it.start } }
            for (a in site.args) if (a.named != null && a.named in meant.html) a.string?.let { out += it.start }
        }
        out
    }

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

/** A function the file declares: the body of the class it is a member of, and its parameters marked `@Language("HTML")`, by place and by name. */
internal class FunctionDeclaration(
    val owner: IntRange?,
    val html: Set<Any>,
)

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
    val functions = HashMap<String, MutableList<FunctionDeclaration>>()
    return FileFacts(src, code, declarations(src, code, functions), functions).also { lastFacts = it }
}

/** The names a Kotlin file declares that other files can see, as [declaredNames] reads them. */
public class DeclaredNames(
    /** The `const val`s: top level or in an object or a class, and not private, `internal` counting as seen. */
    public val constants: Set<String>,
    /** Every other `val` or `var`, at top level, in a class or in a constructor, getters included; a parameter or a local is seen in its file only. */
    public val others: Set<String>,
)

@Volatile
private var lastVisible: Pair<String, DeclaredNames>? = null

/** The names the Kotlin file [src] declares that another file can mean by the bare name. */
public fun declaredNames(src: String): DeclaredNames {
    lastVisible?.let { if (it.first == src) return it.second }
    val names =
        lastFacts?.takeIf { it.src == src }?.names
            ?: lexKotlin(src).let { declarations(src, codeMask(src, it.strings, it.mask)) }
    val constants = HashSet<String>()
    val others = HashSet<String>()
    for ((name, list) in names) {
        for (d in list) if (d.shared) (if (d.constant) constants else others) += name
    }
    return DeclaredNames(constants, others).also { lastVisible = src to it }
}

/**
 * The constants of a project whose Kotlin files declare [files], to pass as [Source.constants]:
 * each file's constants, less every name some file declares as anything else another file could
 * mean by it.
 */
public fun projectConstants(files: Iterable<DeclaredNames>): Set<String> {
    val out = HashSet<String>()
    val others = HashSet<String>()
    for (file in files) {
        out += file.constants
        others += file.others
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
private val HTML_BEFORE = regex("""$HTML_LANGUAGE\s*(?:\p{L}+\s+)*$""", RegexOption.IGNORE_CASE)
internal val HTML_ANNOTATION = regex(HTML_LANGUAGE, RegexOption.IGNORE_CASE)
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

    /** For a function's parameters: the class body it is declared in. */
    var owner: ScopeFrame? = null

    /** Just past the bracket that closes the frame, once it has. */
    var closeAt = -1
}

internal fun isWordPart(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

/** Whether the line break at [at] ends an expression: nothing before it or after it carries the expression on. */
internal fun lineEnds(
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

/** What may follow a value that is the whole of a declaration, read where the value ends: `$` is the end of the file. */
private val STATEMENT_END = regex("""[ \t]*(?:[\n\r;})\],]|//|$)""")

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
    if (close <= 0 || !STATEMENT_END.matchesAt(src, open + close)) return null
    return Triple(name, callee.range.first - m.groupValues[1].length, name != callee.value)
}

private val TEXT_OF_NAME = regex("""\s*=\s*($NAME)\s*\.\s*text""")
private val TEXT_OF_CALL = regex("""\s*=\s*interpolate\s*\(""")
private val TEXT_AFTER = regex("""\s*\.\s*text""")

/** What a declared name at [at] is set to the `.text` of, when that is its whole value: `name` of `= name.text`, or `interpolate(...)`. */
private fun textInitialiser(
    src: String,
    at: Int,
): String? {
    TEXT_OF_NAME.matchAt(src, at)?.let { if (STATEMENT_END.matchesAt(src, it.range.last + 1)) return it.groupValues[1] }
    val call = TEXT_OF_CALL.matchAt(src, at) ?: return null
    val open = call.range.last
    val e = src.substring(open, minOf(src.length, open + 20_000))
    val close = closing(e, depths(e), 0)
    if (close < 0) return null
    val text = TEXT_AFTER.matchAt(src, open + close) ?: return null
    if (!STATEMENT_END.matchesAt(src, text.range.last + 1)) return null
    return src.substring(at + call.value.indexOf("interpolate"), open + close)
}

/** `text` cut at each comma outside brackets. */
internal fun splitTopLevel(text: String): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var from = 0
    for ((k, c) in text.withIndex()) {
        if (c == '(' || c == '<') depth++
        if (c == ')' || c == '>') depth--
        if (c == ',' && depth == 0) {
            out += text.substring(from, k)
            from = k + 1
        }
    }
    out += text.substring(from)
    return out
}

private val NAME_BEFORE_BRACKET = regex("""($NAME)\s*$""")
private val PARAMETER_NAME = regex("""($NAME)\s*:""")

/**
 * The function whose parameters [g] holds, up to [close]: its name, the class it is declared in,
 * and each parameter marked `@Language("HTML")`, by place and by name. The parameters are cut at
 * commas outside brackets, the `<...>` of a type among them and the `->` of a function type not.
 */
private fun functionAt(
    src: String,
    g: ScopeFrame,
    close: Int,
): Triple<String, ScopeFrame?, Set<Any>>? {
    val name = NAME_BEFORE_BRACKET.find(src.substring(maxOf(0, g.open - 200), g.open))?.groupValues?.get(1) ?: return null
    val html = HashSet<Any>()
    var depth = 0
    var from = g.open + 1
    var k = 0
    for (i in g.open + 1..close) {
        val c = src[i]
        if (i < close && c in "([{<") depth++
        if (i < close && (c in ")]}" || (c == '>' && src[i - 1] != '-'))) depth--
        if (i == close || (c == ',' && depth == 0)) {
            val parameter = src.substring(from, i)
            if (HTML_ANNOTATION.containsMatchIn(parameter)) {
                html += k
                PARAMETER_NAME.find(parameter.replace(HTML_ANNOTATION, ""))?.let { html += it.groupValues[1] }
            }
            from = i + 1
            k++
        }
    }
    return Triple(name, g.owner, html)
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
    functions: MutableMap<String, MutableList<FunctionDeclaration>>? = null,
): Map<String, List<Declaration>> {
    // Each function's name, the class body it is declared in and its marked parameters, read once the classes have closed.
    val declared = ArrayList<Triple<String, ScopeFrame?, Set<Any>>>()
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
                g.owner = f.takeIf { it.kind == "class" }
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
            g.closeAt = i + 1
            if (g.kind == "params") {
                handOver(PendingScope(parameters(g, i), true))
                if (functions != null) functionAt(src, g, i)?.let { declared += it }
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
    for ((name, owner, html) in declared) {
        val body = owner?.let { it.open..(if (it.closeAt < 0) src.length else it.closeAt) }
        functions!!.getOrPut(name) { ArrayList() } += FunctionDeclaration(body, html)
    }
    return names
}

/**
 * The declaration [name] means at [at]: of those whose scope holds [at], the innermost, and of
 * two in one scope the later. Null when the file declares no such name there.
 */
internal fun declarationOf(
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
