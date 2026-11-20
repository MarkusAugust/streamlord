package io.github.markusaugust.streamlord.analysis

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
    /** Where the value a `val` is set to is written, when it is set to more than a number or a boolean. */
    val value: Span? = null,
    /** Whether another file can mean it by its bare name: a `val` or `var` at top level, in a class or a constructor, and not private. */
    val shared: Boolean = false,
    /** Whether it is set to a call to `Trusted` and nothing more, which interpolate lets in wherever it lands. */
    val trusted: Boolean = false,
    /** The text a `val` set to `Trusted("...")` writes, the literal as the Kotlin compiler reads it. */
    val trustedText: String? = null,
    /** Whether the call it is set to names core's own under an alias the file imports, which no other import can take. */
    val aliased: Boolean = false,
    /** Whether another declaration may stand in for it: see [overridable]. */
    val overridable: Boolean = false,
) {
    /** Just past where it can last be seen. */
    var end: Int = Int.MAX_VALUE
}

/** Where a value is written: from [start] to just past it, [end], -1 until its statement has ended; [getter] where a `val` has a getter after it. */
internal class Span(
    val start: Int,
    var end: Int = -1,
    var getter: Boolean = false,
)

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

    /**
     * Where each literal starts that is markup by where it stands: one of the block of core's `interpolate` that writes a
     * tag or marks a value, as the block's judging reads it, or the argument of core's `Trusted`.
     */
    val markupLiterals: Set<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val out = HashSet<Int>()
        val lex = lexKotlin(src)
        if (!shadowsCore(src, "interpolate")) {
            for (b in interpolateBlocks(src, code)) {
                for (s in lex.stringsIn(b.braces.first, b.braces.last)) {
                    if ('<' in s.text || s.interpolations.any { markedValue(it) != null }) out += s.start
                }
            }
        }
        if (!shadowsCore(src, "Trusted")) {
            for (site in findCallSites(src, setOf("Trusted"), lex)) {
                if (src.lastNonSpaceBefore(site.nameStart) == '.') continue
                (site.named("html") ?: site.positional.firstOrNull())?.string?.let { out += it.start }
            }
        }
        out
    }

    /** The edit that imports each name the fixes write, by its qualified name, empty where the file has the import. */
    val importEdits: MutableMap<String, List<Edit>> = sharedMap()

    /** The helpers a placeholder may call that no import from elsewhere shadows, whether or not the file uses the DSL. */
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

/**
 * A function the file declares: the body of the class it is a member of, its parameters marked `@Language("HTML")`, by
 * place and by name, where its parameters open, whether it is declared inside a function, where no other can call it,
 * whether it is marked `@Language("HTML")`, the type it is declared to return, and where each value it can give is
 * written: its expression, or what each `return` of its block gives. One that has no body the text shows, returns to a
 * label, or that another may stand in for ([overridable]), shows none of the three, and one that takes markup by a
 * marked parameter no values: what is handed to it is written as it is, and nothing judges it there.
 */
internal class FunctionDeclaration(
    val owner: IntRange?,
    val html: Set<Any>,
    val open: Int,
    val local: Boolean,
    val marked: Boolean,
    val type: String?,
    val values: List<Span>,
)

@Volatile
private var lastFacts: FileFacts? = null

/** The facts of [src], kept for the last file read: every placeholder of a file asks for them. */
internal fun fileFacts(
    src: String,
    lex: KotlinLex? = null,
): FileFacts {
    lastFacts?.let { if (it.src == src) return it }
    otherFacts?.let { if (it.src == src) return it }
    val l = lex ?: lexKotlin(src)
    val code = codeMask(src, l.strings, l.mask)
    val functions = HashMap<String, MutableList<FunctionDeclaration>>()
    return FileFacts(src, code, declarations(src, code, l.comments, functions), functions).also { lastFacts = it }
}

@Volatile
private var otherFacts: FileFacts? = null

/** The facts of [text], a declaration of another file, kept beside those of the file being judged, which stay. */
internal fun <T> besideFile(
    text: String,
    read: (FileFacts) -> T,
): T {
    val judged = lastFacts
    val facts = fileFacts(text)
    if (judged != null && facts !== judged) {
        otherFacts = facts
        lastFacts = judged
    }
    return read(facts)
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
            ?: lexKotlin(src).let { declarations(src, codeMask(src, it.strings, it.mask), it.comments) }
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
    regex(
        """^\s*(?:@(?:[a-z]+:)?[\p{L}_][\p{L}\p{Nd}_.]*(?:\s*\([^)]*\))?\s*)*((?:\p{L}+\s+)*?)($NAME)\s*:\s*($TYPE)?(?:$TYPE_ARGUMENTS)?""",
    )
private val TYPE_ARGUMENTS_AFTER = regex(TYPE_ARGUMENTS)
private val VALUE_AFTER = regex("""\s*=(?!=)\s*""")
private val LAMBDA_PARAMETERS =
    regex(
        """\s*((?:\([^()]*\)|$NAME)(?:\s*:\s*[\p{L}\p{Nd}_.<>?`]+)?(?:\s*,\s*(?:\([^()]*\)|$NAME)(?:\s*:\s*[\p{L}\p{Nd}_.<>?`]+)?)*)\s*->""",
    )
private val FOR_VARIABLE = regex("""\s*(\([^()]*\)|$NAME)(?:\s*:\s*($TYPE))?\s+in\b""")
private val NAMES = regex("""($NAME)(?:\s*:\s*($TYPE))?""")
private val WHOLE_TYPE = regex(TYPE)
private val PROPERTY_MODIFIERS = regex("""\b(?:val|var)\b""")
internal val HTML_ANNOTATION = regex(HTML_LANGUAGE, RegexOption.IGNORE_CASE)
private val VARARG = regex("""\bvararg\b""")
private val LAMBDA_NAME = regex("""^\S+?(?=\s*(?::|$))""")
private val LAMBDA_TYPE = regex(""":\s*(.+)$""")
private val LINE_GOES_ON_BEFORE = regex("""[-=+*/%&|?:.,(<>!]""")
private val LINE_GOES_ON_AFTER = regex("""^(?:[.?*/%&|:]|else\b)""")

/** The words that start a declaration of their own, ending a function's expression body. */
private val DECLARATION_WORDS = setOf("fun", "val", "var", "class", "interface", "object", "init", "constructor", "typealias")

/** Names whose declarations wait for their scope's end: a function's parameters until its body is found. */
private class PendingScope(
    val decls: List<Declaration>,
    /** For a function: its parameters. */
    val fn: ScopeFrame? = null,
) {
    /** `after`: the parameters closed; `expr`: an expression body; `stmt`: a loop body without braces. */
    var state: String = "after"

    /** Whether an expression body has begun: `=` then a line break goes on with the next line. */
    var started: Boolean = false

    /** Whether the function's expression is still being written. */
    val unended get() = state == "expr" && fn?.body?.end == -1
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

    /** For a function's parameters: whether the function is declared inside another. */
    var local = false

    /** For a class's body: whether the class can be extended, and whether it is an interface. */
    var extended = false
    var contract = false

    /** The same of the class last declared in this frame, until its body opens. */
    var declares = false to false

    /** What is written before the declaration being read: each of [MODIFIER_WORDS] and the HTML mark, until anything else is. */
    var modifiers = 0

    /** The same of the `fun` last read in this frame, where a `fun interface` has its own, and for a function's parameters of its function. */
    var funModifiers = 0

    /** The values that just ended in this frame, one in another, while only modifiers follow: a `get()` then is their getter. */
    var lastValues = emptyList<Span>()

    /** The values written in this frame whose statements have not ended. */
    val values = ArrayList<Span>()

    /** For a function's parameters: where its body is written, none once it returns to a label, and what each `return` of a block gives. */
    var body: Span? = null
    val returns = ArrayList<Span>()

    /** For a function's block: its parameters. */
    var function: ScopeFrame? = null

    /** The word last read in this frame with nothing but space after it, and for a function's parameters the function's name. */
    var word: String? = null
    var name: String? = null

    /** What a modifier's word is here: 0 where a declaration may begin, 1 after a value, which a line break ends, 2 after what carries on. */
    var after = 0

    fun reset(after: Int = 1) {
        modifiers = 0
        lastValues = emptyList()
        word = null
        this.after = after
    }

    fun begin(value: Span) = value.also { values += it }

    /** Ends each value written before [at], where a statement of this frame ends. */
    fun endValues(at: Int) {
        val ended = values.filter { it.start <= at }
        for (v in ended) v.end = at
        lastValues = ended.ifEmpty { return }
        values.removeAll(ended)
    }
}

internal fun isWordPart(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

/** Whether the line break at [at] ends an expression: nothing before it or after it carries the expression on. */
internal fun lineEnds(
    src: String,
    code: BooleanArray,
    at: Int,
): Boolean {
    var k = at - 1
    while (k >= 0 && (!code[k] || src[k].isWhitespace())) {
        if (!code[k] && !src[k].isWhitespace()) {
            var from = k
            while (from > 0 && !code[from - 1]) from--
            // A literal the line ends in is the end of a value; a comment is passed over.
            if (!src.startsWith("//", from) && !src.startsWith("/*", from)) break
            k = from
        }
        k--
    }
    if (k >= 0 && code[k] && LINE_GOES_ON_BEFORE.matchEntire(src[k].toString()) != null) return false
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

private const val PRIVATE = 1
private const val CONST = 2
private const val EXTENDED = 4
private const val OVERRIDES = 8
private const val HTML = 16
private const val COMPANION = 32

private val UNNAMED = mapOf("object" to regex("""\s*[:{]"""), "fun" to regex("""\s*\("""))
private val RECEIVER_DOT = regex("""\s*\??\s*\.""")

/** The words that may stand before a declaration, each with what it says of it. */
private val MODIFIER_WORDS: Map<String, Int> =
    "public protected internal final lateinit inline suspend tailrec operator infix external data enum annotation inner value actual expect"
        .split(" ")
        .associateWith { 0 } + listOf("open", "abstract", "sealed").associateWith { EXTENDED } +
        mapOf("private" to PRIVATE, "const" to CONST, "override" to OVERRIDES, "companion" to COMPANION)

private val QUOTED = regex("`[^`\n]+`")
private val ANNOTATION = regex("""@(?:[a-z]+:)?(?:\[|[\p{L}_][\p{L}\p{Nd}_.]*)""")

/** What stands between a modifier and the declaration on its line: words and annotations, twelve at most, so that no line is too long to read. */
private val DECLARES_AFTER = regex("""(?:[ \t]+(?:[a-z]+|@[^\s(]+(?:\([^()\n]*\))?)){0,12}[ \t]+(?:va[lr]|fun|class|interface|object)\b""")

/** Just past the annotation written at [at], its arguments too where they close, or [at] where none is. */
private fun annotationEnd(
    src: String,
    code: BooleanArray,
    at: Int,
): Int {
    val name = (ANNOTATION.matchAt(src, at) ?: return at).range.last + 1
    // `@[A B]` holds its annotations in the bracket.
    val open = if (src[name - 1] == '[') name - 1 else name
    val depth = IntArray(3)
    for (k in open until if (src.getOrNull(open) == '(' || open < name) minOf(src.length, open + 5_000) else open) {
        if (code[k] && src[k] in "([{") depth["([{".indexOf(src[k])]++
        val closes = if (code[k]) ")]}".indexOf(src[k]) else -1
        // A bracket that closes none of its kind is past an annotation left open.
        if (closes >= 0 && --depth[closes] < 0) break
        if (closes >= 0 && depth.sum() == 0) return k + 1
    }
    return open
}

/**
 * Whether another declaration may stand in for one with these [modifiers], a member of a class that can be [extended]
 * and is a [contract], an interface, each null of a class the text does not show: it is `open` or `abstract`, a member
 * of an interface, or an `override` in a class that can be extended or that the text does not show.
 */
private fun overridable(
    modifiers: Int,
    extended: Boolean?,
    contract: Boolean?,
): Boolean = modifiers and EXTENDED != 0 || contract == true || (modifiers and OVERRIDES != 0 && extended != false)

private val PARAMETER_NAME = regex("""($NAME)\s*:""")

/** [src] with each comment blank, one space for each of its characters but a line break, so that every offset holds. */
private fun withoutComments(
    src: String,
    comments: BooleanArray,
): String = CharArray(src.length) { if (comments.getOrElse(it) { false } && src[it] != '\n') ' ' else src[it] }.concatToString()

/**
 * The function whose parameters [g] holds, up to [close]: its name, the class it is declared in, each parameter marked
 * `@Language("HTML")`, by place and by name, and what the scanner kept of its result. The parameters are cut at commas
 * outside brackets, the `<...>` of a type among them and the `->` of a function type not.
 */
private fun functionAt(
    src: String,
    code: BooleanArray,
    g: ScopeFrame,
    close: Int,
): Pair<String, FunctionDeclaration>? {
    val name = g.name ?: return null
    val html = HashSet<Any>()
    var depth = 0
    var from = g.open + 1
    var k = 0
    for (i in g.open + 1..close) {
        // A comma or a bracket in a string cuts nothing.
        val c = if (code[i]) src[i] else ' '
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
    val owner = g.owner?.let { it.open..(if (it.closeAt < 0) src.length else it.closeAt) }
    val body = g.body?.takeIf { it.end >= 0 && !overridable(g.funModifiers, g.owner?.extended, g.owner?.contract) }
    // Between the parameters and the body stands the type or nothing, or the function is not read.
    val type = body?.takeIf { it.start - close < 1_000 }?.let { RETURN_TYPE.matchEntire(src.substring(close + 1, it.start - 1)) }
    val results = if (body != null && src[body.start - 1] == '{') g.returns else listOfNotNull(body)
    val values = if (type == null || html.isNotEmpty()) emptyList() else results
    return name to
        FunctionDeclaration(owner, html, g.open, g.local, type != null && g.funModifiers and HTML != 0, type?.groups?.get(1)?.value, values)
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
    comments: BooleanArray,
    functions: MutableMap<String, MutableList<FunctionDeclaration>>? = null,
): Map<String, List<Declaration>> {
    // Each function's name, the class body it is declared in and its marked parameters, read once the classes have closed.
    val declared = ArrayList<Pair<ScopeFrame, Int>>()
    val names = HashMap<String, ArrayList<Declaration>>()
    val aliases = aliasesIn(src, code)

    // Parameters are read in the code alone: a comment among them, `a: String, // the a`, is blank.
    val blank by lazy { withoutComments(src, comments) }

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
        holder: ScopeFrame? = null,
    ): List<Declaration> {
        val out = ArrayList<Declaration>()
        val cuts = listOf(f.open) + f.commas + close
        for (k in 0 until cuts.size - 1) {
            val text = blank.substring(cuts[k] + 1, cuts[k + 1])
            val m = PARAMETER.find(text) ?: continue
            val modifiers = m.groupValues[1].split(' ', '\t', '\n', '\r').fold(0) { all, word -> all or (MODIFIER_WORDS[word] ?: 0) }
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
                        overridable = holder != null && overridable(modifiers, holder.declares.first, holder.declares.second),
                        typeArguments = arguments,
                        shared =
                            visible && PROPERTY_MODIFIERS.containsMatchIn(m.groupValues[1]) && modifiers and PRIVATE == 0,
                    ),
                )
        }
        return out
    }

    /** The names of a list such as `a, b: Int`, and the type of each. */
    fun listed(list: String): List<Pair<String, String?>> = NAMES.findAll(list).map { it.groupValues[1] to it.groups[2]?.value }.toList()
    var i = 0
    while (i < src.length) {
        val f = stack.last()
        if (src[i] == '\n' && f.after == 1) f.after = 0
        if (!code[i]) {
            // A literal is a value, and what stands before it is written before no declaration; a comment is passed over.
            if ((i == 0 || code[i - 1]) && !src.startsWith("//", i) && !src.startsWith("/*", i)) f.reset()
            i++
            continue
        }
        val c = src[i]
        if ((c.isLetter() || c == '_') && !isWordPart(src.getOrNull(i - 1))) {
            var end = i + 1
            while (end < src.length && isWordPart(src[end])) end++
            val word = src.substring(i, end)
            // `Row::class` names a class and declares none.
            val reference = src.startsWith("::", i - 2)
            val ctorOfClass = word == "constructor" && f.cls
            // In a function's expression an `object` or a `fun` without a name is a value, and the parameters are in sight in it.
            val unnamed = if (f.pending?.unended == true && f.modifiers and COMPANION == 0) UNNAMED[word]?.matchAt(src, end) else null
            // A receiver in brackets has a dot after it, the parameters of a function without a name none.
            val after = if (word == "fun") unnamed?.let { src.substring(it.range.last, minOf(src.length, it.range.last + 2_000)) } else null
            val value =
                unnamed != null && (after == null || RECEIVER_DOT.matchAt(after, maxOf(0, closing(after, depths(after), 0))) == null)
            if (word in DECLARATION_WORDS && !reference && !ctorOfClass && !value) {
                // A new declaration ends a function's expression body, a loop body without braces, or a class without one.
                endPending(f, i)
                f.cls = false
            }
            when {
                reference -> {}

                word == "fun" || (word == "constructor" && !ctorOfClass) -> {
                    f.fn = true
                    f.funModifiers = f.modifiers
                }

                word == "return" -> {
                    // The `return` of a function declared in another counts as the outer one's too, which can only make it less.
                    val into = stack.mapNotNull { it.function }
                    val value = if (src.getOrNull(end) == '@' || into.isEmpty()) null else f.begin(Span(end))
                    for (g in into) if (value == null) g.body = null else g.returns.add(value)
                }

                // A getter takes nothing: `get("/")` after a value is a call.
                word == "get" -> {
                    if (GETTER_AFTER.matchAt(src, end) != null) for (v in f.lastValues) v.getter = true
                }

                word == "class" || word == "interface" || word == "object" -> {
                    f.cls = true
                    f.privateClass = (if (f.word == "fun") f.funModifiers else f.modifiers) and PRIVATE != 0
                    val contract = word == "interface"
                    f.declares = (contract || (word == "class" && f.modifiers and EXTENDED != 0)) to contract
                }

                word == "when" -> {
                    f.whenBlock = true
                }

                word == "for" -> {
                    f.loop = true
                }

                (word == "val" || word == "var") && f.kind != "params" && f.kind != "ctor" -> {
                    val constant = f.modifiers and CONST != 0
                    val html = f.modifiers and HTML != 0
                    val local = f.kind != "top" && f.kind != "class"
                    val cls = f.takeIf { it.kind == "class" }
                    val overridable = !local && overridable(f.modifiers, cls?.extended, cls?.contract)
                    val shared = !local && !f.hidden && f.modifiers and PRIVATE == 0
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
                            val set = VALUE_AFTER.matchAt(src, arguments?.range?.last?.plus(1) ?: (m.range.last + 1))?.range?.last
                            val valueAt = if (bare && !markup) set?.plus(1) else VAL_AFTER.groupRange(src, m, 5)?.first
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
                                        valueAt?.takeIf { word == "val" }?.let { f.begin(Span(it)) },
                                        shared,
                                        trusted,
                                        trustedText,
                                        aliased = initialiser?.third == true,
                                        overridable = overridable,
                                    ),
                                )
                            if (owner.kind != "top") owner.ends += d
                        }
                    }
                }
            }
            // `open` in `door.open` is a name, and says nothing of what the next line declares.
            val says = MODIFIER_WORDS[word]?.takeIf { f.after == 0 || DECLARES_AFTER.matchAt(src, end) != null }
            if (says == null) f.reset()
            f.modifiers = f.modifiers or (says ?: 0)
            f.word = word
            i = end
            continue
        }
        // A name in backticks is a name whatever it holds, and `@` after a word or `super<A>` names a label.
        val quoted = if (c == '`') QUOTED.matchAt(src, i)?.also { f.reset() } else null
        val label = c != '@' || src.getOrNull(i - 1)?.let { isWordPart(it) || it == '>' } == true
        val annotated = if (label) i + (quoted?.value?.length ?: 0) else annotationEnd(src, code, i)
        if (annotated > i) {
            // What an annotation stands first in has begun: a loop's statement, a function's expression.
            if (f.pending?.fn == null) f.pending?.state = "stmt"
            if (f.pending?.state == "expr") f.pending?.started = true
            if (HTML_ANNOTATION.matchAt(src, i)?.range?.last == annotated - 1) f.modifiers = f.modifiers or HTML
            i = annotated
            continue
        }
        if (c == '(' || c == '[' || c == '{') {
            val g = ScopeFrame(i, if (c == '{') "block" else "paren")
            if (c == '(' && f.fn) {
                g.kind = "params"
                g.owner = f.takeIf { it.kind == "class" }
                g.local = f.kind != "class" && f.kind != "top"
                g.funModifiers = f.funModifiers
                g.name = f.word
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
                    g.pending = PendingScope(list.map { (name, type) -> add(name, Declaration(i + 1, i, type)) })
                }
            } else if (c == '{') {
                f.fn = false
                f.loop = false
                val p = f.pending
                if (p != null && p.state == "after") {
                    // The body of the function or the loop whose parameters just closed.
                    g.ends += p.decls
                    f.pending = null
                    g.function = p.fn?.also { it.body = Span(i + 1) }
                } else if (f.cls) {
                    g.kind = "class"
                    f.cls = false
                    g.extended = f.declares.first
                    g.contract = f.declares.second
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
            f.reset()
            stack += g
            i++
            continue
        }
        if (!c.isWhitespace()) f.reset(if (c in "=.,") 2 else 1)
        if (c == ';') f.after = 0
        if (c == ')' || c == ']' || c == '}') {
            if (stack.size == 1) {
                f.endValues(i)
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
            g.endValues(i)
            // `fun a() {} open` then a line break: a declaration may begin after a block.
            if (c == '}') outer.after = 0
            g.function?.body?.end = i + 1
            for (d in g.ends) d.end = i + 1
            g.closeAt = i + 1
            if (g.kind == "params") {
                val around = outer.pending?.takeIf { it.unended }?.decls ?: emptyList()
                handOver(PendingScope(parameters(g, i) + around, g))
                if (functions != null) declared.add(g to i)
            } else if (g.kind == "ctor") {
                outer.ctor = parameters(g, i, !classHidden(outer), outer)
            }
            i++
            continue
        }
        if (c == ',') {
            f.commas += i
            i++
            continue
        }
        if (f.values.isNotEmpty() && (c == ';' || (c == '\n' && lineEnds(src, code, i)))) f.endValues(i)
        val p = f.pending
        if (p != null) {
            when (p.state) {
                "after" -> {
                    if (c == '=' && src.getOrNull(i + 1) != '=' && p.fn != null) {
                        p.state = "expr"
                        p.fn?.body = f.begin(Span(i + 1))
                    } else if (p.fn == null && !c.isWhitespace()) {
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
    for (f in stack) {
        endPending(f, src.length)
        f.endValues(src.length)
    }
    for (list in names.values) list.sortWith(compareBy<Declaration>({ it.start }, { it.at }))
    for ((g, close) in declared) functionAt(blank, code, g, close)?.let { (name, f) -> functions!!.getOrPut(name) { ArrayList() } += f }
    return names
}

/** A value as the file writes it, comments left out, from [start] to [end]. */
internal class ValueText(
    val text: String,
    val start: Int,
    val end: Int,
)

/** The type an extension is declared on, with the dot after it: `List<T>.` of `fun <T> List<T>.joined()`. */
internal const val RECEIVER = """[\p{L}_][\p{L}\p{Nd}_.]*(?:<(?:[^<>]|<[^<>]*>)*>)?\??\s*\.\s*"""

private val GETTER_AFTER = regex("""\s*\(\s*\)""")
private val RETURN_TYPE = regex("""\s*(?::\s*($TYPE(?:$TYPE_ARGUMENTS)?))?\s*""")

/**
 * The functions [name] can mean at [at] where it is called without a receiver: the members of the class the call is in,
 * before those of the top level. Null where the text cannot say which it means: the file declares a function of that
 * name inside another, a value of that name is in sight, or a class the call is not in has a member of that name, which
 * a companion or a class it extends may give it.
 */
internal fun functionsNamed(
    src: String,
    name: String,
    at: Int,
): List<FunctionDeclaration>? {
    val all = fileFacts(src).functions[name].orEmpty()
    if (all.any { it.local } || declarationOf(src, name, at) != null) return null
    val members = all.filter { f -> f.owner?.let { at in it } == true }
    val innermost = members.minOfOrNull { it.owner!!.last - it.owner.first } ?: return all.takeIf { f -> f.all { it.owner == null } }
    return members.filter { it.owner!!.last - it.owner.first == innermost }
}

/** Whether a call of [name] without a receiver at [at] may mean a function of the file: one of the top level, of a class the call is in, or declared inside another. */
internal fun callsFileFunction(
    src: String,
    name: String,
    at: Int,
): Boolean = fileFacts(src).functions[name].orEmpty().any { f -> f.local || f.owner?.let { at in it } != false }

/** The value [src] writes in [at], each comment in it left out, or null where that is nothing. */
internal fun valueText(
    src: String,
    at: Span,
): ValueText? {
    val text = uncommented(src.substring(at.start, maxOf(at.start, at.end))).trim()
    return if (text.isEmpty()) null else ValueText(text, at.start, at.end)
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
