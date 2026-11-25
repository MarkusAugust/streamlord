package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.codePointFrom
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
    /** Where the call it is set to starts, where it is [markup]: its qualifier's first letter, or its name's. */
    val markupAt: Int = -1,
) {
    /** Just past where it can last be seen. */
    var end: Int = Int.MAX_VALUE
}

/**
 * Where a value is written: from [start] to just past it, [end], -1 until its statement has ended; [getter] where a `val`
 * has a getter after it; [html] where what it is the value of is marked `@Language("HTML")`; and [returned] where a
 * `return` gives it.
 */
internal class Span(
    val start: Int,
    var end: Int = -1,
    var getter: Boolean = false,
    var html: Boolean = false,
    val returned: Boolean = false,
) {
    /** For a `return`'s value, the `if`s in it still without their `else`: an `else` beyond them ends it. */
    var ifs = 0

    /** Whether it is what a `val` or `var` is set to where it is declared. */
    var initialiser = false
}

/**
 * What the analysis needs of a whole file, read once: where the code is (template code included),
 * the names it declares with their scopes, and whether `interpolate` is imported.
 */
internal class FileFacts(
    val src: String,
    /** `true` where the character is code: outside literals and comments, or in a template's code. */
    val code: BooleanArray,
    /** `true` where the character is in a comment. */
    val comments: BooleanArray,
    /** Each type's brackets, as [typeBrackets] reads them. */
    val types: IntArray,
    val names: Map<String, List<Declaration>>,
    /** Every function the file declares, by its name. */
    val functions: Map<String, List<FunctionDeclaration>> = emptyMap(),
    /** Where each literal starts that `@Language("HTML")` or `// language=HTML` reaches, as the Kotlin plugin reads a mark. */
    val languageMarks: Set<Int> = emptySet(),
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
            if (src.getOrNull(codeBefore(src, code, site.nameStart)) == '.') continue
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

    /** Each annotation, `@Deprecated("<b>x</b>")`, to its end: its arguments say something of the code and hold no markup. */
    private val annotations: List<IntRange> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val out = ArrayList<IntRange>()
        var at = src.indexOf('@')
        while (at >= 0) {
            // `this@Page` and `return@forEach` name a label; an annotation in another's arguments lies in it.
            val label = src.getOrNull(at - 1)?.let { isWordPart(it) || it in ">.`" } == true
            val end = if (code[at] && !label) annotationEnd(src, code, types, at) else at
            if (end > at) out += at until end
            at = src.indexOf('@', maxOf(end, at + 1))
        }
        out
    }

    /** Whether [at] stands in an annotation: its name, its type arguments or its arguments. */
    fun inAnnotation(at: Int): Boolean {
        val k =
            annotations.binarySearch {
                if (it.last < at) {
                    -1
                } else if (it.first > at) {
                    1
                } else {
                    0
                }
            }
        return k >= 0
    }

    /**
     * Where each literal starts that is markup by where it stands: one of the block of core's `interpolate` that writes a
     * tag or marks a value and stands in no call's arguments, where a Datastar expression or a log message would, or one
     * of [trustedLiterals] that writes a tag.
     */
    val markupLiterals: Set<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val out = HashSet<Int>()
        val lex = lexKotlin(src)
        if ("interpolate" in src && !shadowsCore(src, "interpolate")) {
            for (b in interpolateBlocks(src, code)) {
                val literals = lex.stringsIn(b.braces.first, b.braces.last)
                val written = literals.filter { TAG.containsMatchIn(it.text) || it.interpolations.any { ip -> markedValue(ip) != null } }
                out += outsideArguments(src, code, b.braces.first, written)
            }
        }
        val lexed = withNested(lex.strings).associateBy { it.start }
        out += trustedLiterals.filter { lexed[it]?.text?.let(TAG::containsMatchIn) == true }
        out
    }

    /**
     * Where the argument of each call of core's `Trusted` stands, under its name, an alias or its package, in code or in a
     * template's code: markup the author vouches for, whatever it is built of.
     */
    val trustedArguments: List<IntRange> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val out = ArrayList<IntRange>()
        val names = coreAliases(src).filterValues { it == "Trusted" }.keys + "Trusted"
        if (names.none { it in src }) return@lazy out
        val lex = lexKotlin(src)
        val own = !shadowsCore(src, "Trusted")
        for (site in findCallSites(src, names, KotlinLex(lex.strings, code, lex.comments))) {
            val dot = codeBefore(src, code, site.nameStart)
            // Called on a receiver it is somebody else's, unless the receiver is core's package written out.
            val qualified = src.getOrNull(dot) == '.' && site.name == "Trusted" && qualifiedByCore(src, code, dot)
            if (!qualified && (src.getOrNull(dot) == '.' || (site.name == "Trusted" && !own))) continue
            val arg = site.named("html") ?: site.positional.firstOrNull() ?: continue
            out += arg.start until arg.end
        }
        out
    }

    // The blocks of core's interpolate in the file.
    private val blocks: List<InterpolateBlock> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if ("interpolate" in src && !shadowsCore(src, "interpolate")) interpolateBlocks(src, code) else emptyList()
    }

    /** Where each literal starts that is [trustedArguments]' text: the argument or a literal of a sum that is, called in it by nothing else. */
    val trustedLiterals: Set<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        // The literals in a template's code too: `Trusted("<p>${"<b>$x</b>"}</p>")`, or Trusted called in a template.
        val all = withNested(lexKotlin(src).strings)
        trustedArguments.flatMapTo(HashSet()) { arg ->
            outsideArguments(src, code, arg.first - 1, all.filter { it.start >= arg.first && it.end <= arg.last + 1 })
        }
    }

    /**
     * The argument of core's `Trusted` that [at] stands in, where nothing stands between: a block of `interpolate` in the
     * argument, the one a fix writes, `Trusted(interpolate { ... }.text)`, is that block's own markup.
     */
    fun trustedAt(at: Int): IntRange? {
        val arg = trustedArguments.filter { at in it }.maxByOrNull { it.first } ?: return null
        val block = blocks.filter { at in it.braces }.maxByOrNull { it.braces.first }
        return arg.takeIf { block == null || block.braces.first < arg.first }
    }

    /** Whether [at] stands in the argument of core's `Trusted`, with no block of `interpolate` between: see [trustedAt]. */
    fun inTrusted(at: Int): Boolean = trustedAt(at) != null

    /**
     * Whether the literal at [at] stands in the value of a `val` declared in the block of `interpolate` whose braces are
     * [block] (the `val` itself not in a lambda or a function of the block; the literal anywhere in its value): where that
     * string goes, to the markup or to a log, only the uses of the `val` tell, which the text cannot follow.
     */
    fun inBlockValue(
        at: Int,
        block: IntRange,
    ): Boolean {
        val (_, d) = valued.owner.getOrNull(at)?.let { valued.declarations.getOrNull(it) } ?: return false
        return d.at in block && d.end >= block.last && d.end <= block.last + 1
    }

    // The `val`s of the file with a value, and at each offset the innermost whose value stands there, worked out once:
    // each value painted over the ones it stands in, which start before it.
    private val valued: Valued by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val declarations =
            names
                .flatMap { (name, list) -> list.filter { it.value != null }.map { name to it } }
                .sortedBy { it.second.value!!.start }
        val owner = IntArray(src.length) { -1 }
        for ((k, entry) in declarations.withIndex()) {
            val v = entry.second.value!!
            val end = (if (v.end < 0) v.start + 1 else v.end).coerceAtMost(src.length)
            for (j in v.start until end) owner[j] = k
        }
        Valued(declarations, owner)
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
    val (code, comments) = codeMask(src, l)
    val types = typeBrackets(src, code, comments)
    val functions = HashMap<String, MutableList<FunctionDeclaration>>()
    val marks = HashSet<Int>()
    val names = declarations(src, code, comments, types, functions, marks)
    return FileFacts(src, code, comments, types, names, functions, marks).also { lastFacts = it }
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
            ?: codeMask(src, lexKotlin(src)).let { (code, comments) ->
                declarations(src, code, comments, typeBrackets(src, code, comments))
            }
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

/**
 * The lexer's mask with the code of every `${...}` template marked as code, its braces too, and its
 * comment map with the comments in that code: where the code is, and which of the rest is comment.
 */
internal fun codeMask(
    src: String,
    lex: KotlinLex,
): Pair<BooleanArray, BooleanArray> {
    val code = lex.mask.copyOf()
    val comments = lex.comments.copyOf()
    // Outermost first, so a literal inside a template is blanked before its own templates are marked.
    for (s in withNested(lex.strings)) {
        for (ip in s.interpolations) {
            if (ip.kind != InterpolationKind.BRACED || src.getOrNull(ip.end - 1) != '}') continue
            val open = src.indexOf('{', ip.start)
            val inner = lexKotlin(src.substring(open + 1, ip.end - 1))
            inner.mask.copyInto(code, open + 1)
            inner.comments.copyInto(comments, open + 1)
            code[open] = true
            code[ip.end - 1] = true
        }
    }
    return code to comments
}

private const val NAME = """[\p{L}_][\p{L}\p{Nd}_]*"""

// A name in backticks too, as `` `Page` ``, which means the same name without them.
private const val TYPE = """(?:[\p{L}_][\p{L}\p{Nd}_.]*|`[^`\n]+`)(?:\.(?:[\p{L}_][\p{L}\p{Nd}_]*|`[^`\n]+`))*\??"""
private val VAL_AFTER =
    regex(
        """\s*((?:$NAME\s*\.\s*)*)($NAME)(?:\s*:\s*($TYPE))?""" +
            """(?:\s*=\s*(-?\d[\d_]*(?:\.\d+)?(?:[uU][lL]?|[LlFfDd])?|true|false)$VALUE_END)?""",
    )
private val DESTRUCTURED = regex("""\s*\(([^()]*)\)""")

// What may follow a literal a declaration is set to, for the literal to be all it is set to.
private const val VALUE_END = """(?=[ \t]*(?:[\n\r;})\],]|//|$))"""
private val VALUE_ENDS = regex(VALUE_END)
private val PARAMETER =
    regex(
        """^\s*(?:@(?:[a-z]+:)?[\p{L}_][\p{L}\p{Nd}_.]*(?:\s*\([^)]*\))?\s*)*((?:\p{L}+\s+)*?)($NAME)\s*:\s*($TYPE)?""",
    )
private val VALUE_AFTER = regex("""\s*=(?!=)\s*""")
private val SET_TO = regex("""\s*=\s*""")

/*
 * A list of names, a type's arguments and a literal are read by a loop and not by a pattern that repeats a choice: the
 * JVM's patterns take a frame of the stack for each time round, and thousands of them overflow it.
 */

/** What a loop read: the [text] it found, and [end], just past where it was written. */
private class Read(
    val text: String,
    val end: Int,
)

/** The brackets of the type arguments after [at], [blanks] before them, as [typeBrackets] reads them, from `<` to `>`. */
private fun typeArgumentsAt(
    src: String,
    types: IntArray,
    at: Int,
    blanks: String = " \t\r\n",
): IntRange? {
    var k = at
    while (k < src.length && src[k] in blanks) k++
    return if (src.getOrNull(k) != '<' || types[k] < 0) null else k..types[k]
}

/** The range of the string literal without a template a declaration is set to just after [at], `= "x"`, and nothing more. */
private fun plainLiteralAt(
    src: String,
    at: Int,
): IntRange? {
    val set = SET_TO.matchAt(src, at) ?: return null
    val open = set.range.last + 1
    if (src.getOrNull(open) != '"') return null
    var k = open + 1
    while (k < src.length && src[k] != '"') {
        val c = src[k]
        if (c == '$' || c == '\n' || (c == '\\' && (k + 1 >= src.length || src[k + 1] == '$'))) return null
        k += if (c == '\\') 2 else 1
    }
    if (k >= src.length || VALUE_ENDS.matchAt(src, k + 1) == null) return null
    return open..k
}

/** Whether only blanks stand before [at] on its line. */
private fun firstOnLine(
    src: String,
    at: Int,
): Boolean {
    var k = at - 1
    while (k >= 0 && (src[k] == ' ' || src[k] == '\t')) k--
    return k < 0 || src[k] == '\n' || src[k] == '\r'
}

/**
 * How many characters the letter, digit or `_` of a name at [at] takes, 0 where none stands there: a letter past the first
 * plane takes two. The [first] of a name is no digit.
 */
private fun nameCharAt(
    src: String,
    at: Int,
    first: Boolean,
): Int {
    val c = src.getOrNull(at) ?: return 0
    if (c == '_' || c.isLetter() || (!first && c.isDigit())) return 1
    if (!c.isHighSurrogate() || at + 1 >= src.length) return 0
    val point = src.codePointFrom(at)
    return if (if (first) startsName(point) else continuesName(point)) 2 else 0
}

/** The first offset from [at] that is no white space. */
private fun pastWhiteSpace(
    src: String,
    at: Int,
): Int {
    var k = at
    while (k < src.length && src[k].isWhitespace()) k++
    return k
}

/** The parameters a lambda's block names before its arrow, from [at]: what they are written as, and just past the arrow. */
private fun lambdaParameters(
    src: String,
    types: IntArray,
    at: Int,
): Read? {
    val start = pastWhiteSpace(src, at)
    var k = start
    var end = -1
    var arrow = -1
    while (arrow < 0) {
        // A name, or a destructuring list in brackets.
        if (src.getOrNull(k) == '(') {
            val close = src.indexOf(')', k)
            if (close < 0 || src.indexOf('(', k + 1) in 0 until close) return null
            k = close + 1
        } else if (nameCharAt(src, k, first = true) > 0) {
            k += nameCharAt(src, k, first = true)
            while (nameCharAt(src, k, first = false) > 0) k += nameCharAt(src, k, first = false)
        } else {
            return null
        }
        end = k
        val colon = pastWhiteSpace(src, k)
        if (src.getOrNull(colon) == ':') {
            val from = pastWhiteSpace(src, colon + 1)
            var to = from
            while (to < src.length && (src[to] in ".?`" || types[to] > to || nameCharAt(src, to, first = false) > 0)) {
                to = if (types[to] > to) types[to] + 1 else to + (if (src[to] in ".?`") 1 else nameCharAt(src, to, first = false))
            }
            if (to > from) end = to
        }
        val next = pastWhiteSpace(src, end)
        if (src.getOrNull(next) != ',' && !src.startsWith("->", next)) return null
        k = if (src.startsWith("->", next)) next else pastWhiteSpace(src, next + 1)
        if (src.startsWith("->", k)) arrow = k
    }
    return Read(src.substring(start, end), arrow + 2)
}

/** How far back from its closing bracket a `catch` is read for its variable and the type. */
private const val CATCH_VARIABLE_LENGTH = 500

private val FOR_VARIABLE = regex("""\s*(\([^()]*\)|$NAME)(?:\s*:\s*($TYPE))?\s+in\b""")
private val NAMES = regex("""($NAME)(?:\s*:\s*($TYPE))?""")
private val WHOLE_TYPE = regex(TYPE)
private val PROPERTY_MODIFIERS = regex("""\b(?:val|var)\b""")
internal val HTML_ANNOTATION = regex(HTML_LANGUAGE, RegexOption.IGNORE_CASE)
private val VARARG = regex("""\bvararg\b""")
private val LAMBDA_NAME = regex("""^\S+?(?=\s*(?::|$))""")
private val LAMBDA_TYPE = regex(""":\s*(.+)$""")

/**
 * Whether the line goes on after [k], its last character of code: an operator that wants what follows, `else`, an infix
 * word, or the
 * condition of an `if`, a `when` or a loop, whose branch follows. `!!`, a `?` of a type and the `>` that closes type
 * arguments opened on the line end it, `List<Int>` or `String?` at the end of a value; `->` points on.
 */
private fun goesOn(
    src: String,
    code: BooleanArray,
    comments: BooleanArray,
    types: IntArray,
    k: Int,
): Boolean =
    when (src[k]) {
        // `n++` and `n--` end a statement.
        '-', '+' -> {
            var from = k
            while (from > 0 && src[from - 1] == src[k]) from--
            (k - from) % 2 == 0
        }

        // The `*` of `import a.*` ends its line.
        '*' -> {
            src.getOrNull(k - 1) != '.'
        }

        '=', '/', '%', '&', '|', ':', '.', ',', '(', '<' -> {
            true
        }

        '!' -> {
            src.getOrNull(k - 1) != '!'
        }

        '>' -> {
            src.getOrNull(k - 1) == '-' || types[k] < 0
        }

        ')' -> {
            branchFollows(src, code, comments, k)
        }

        // `x as?` wants its type.
        '?' -> {
            src.startsWith("as", k - 2) && !isWordPart(src.getOrNull(k - 3))
        }

        else -> {
            isWordPart(src[k]) &&
                (WORD_THAT_GOES_ON.containsMatchIn(src.substring(maxOf(0, k - 8), k + 1)) || infixWaits(src, code, comments, k))
        }
    }

/** The keywords after which Kotlin reads on, which no name can be: `else`, `x as`, `x is`, `x in`. */
private val WORD_THAT_GOES_ON = regex("""(?:^|[^\p{L}\p{Nd}_.`])(?:else|as|is|in|finally|try|when)$""")

/** Words that stand before a name or a value and are no operand of an infix call. */
private val NO_OPERAND =
    setOf(
        "return",
        "throw",
        "else",
        "in",
        "is",
        "as",
        "val",
        "var",
        "fun",
        "class",
        "object",
        "interface",
        "typealias",
        "import",
        "package",
        "if",
        "when",
        "while",
        "for",
        "do",
        "try",
        "catch",
        "finally",
        "by",
        "where",
        "get",
        "set",
    )

/**
 * Whether the name the line ends in at [k] is an infix function's, still wanting its right operand: `a to`, where the
 * names and operands standing one after another back to an operator or a bracket are an even number. `a to b` ends.
 */
private fun infixWaits(
    src: String,
    code: BooleanArray,
    comments: BooleanArray,
    k: Int,
): Boolean {
    if (!src[k].isLetter() && src[k] != '_' && src[k] != '`') return false
    var run = 0
    var j = k
    while (j >= 0) {
        var start = j
        while (start > 0 && isWordPart(src[start - 1])) start--
        if (src.getOrNull(start - 1) == '@') break
        if (src.substring(start, j + 1) in NO_OPERAND) break
        run++
        j = start - 1
        // A comment between two names is space.
        while (j >= 0) {
            if (src[j] == ' ' || src[j] == '\t') {
                j--
                continue
            }
            if (code[j] || src[j] == '\n') break
            var from = j
            while (from > 0 && !code[from - 1]) from--
            if (!src.startsWith("//", from) && !src.startsWith("/*", from)) break
            j = from - 1
        }
        if (j < 0 || src[j] == '\n' || j == start - 1) break
        if (!code[j] || src[j] == ']' || src[j] == '}') {
            run++
            break
        }
        if (src[j] == ')') {
            // `@Suppress("x") n`: an annotation is no operand.
            val open = opening(src, code, j, '(', ')')
            val head = open?.let { codeBefore(src, code, it) } ?: -1
            var name = head
            while (name >= 0 && isWordPart(src[name])) name--
            if (!branchFollows(src, code, comments, j) && !(head >= 0 && isWordPart(src[head]) && src.getOrNull(name) == '@')) run++
            break
        }
        // `x!! to` and `n++ to`: what ends an operand.
        if ((src[j] == '!' || src[j] == '+' || src[j] == '-') && src.getOrNull(j - 1) == src[j]) {
            run++
            break
        }
        if (!isWordPart(src[j])) break
    }
    return run % 2 == 0 && run > 0
}

private val DO_BEFORE = regex("""(?:^|[^\p{L}\p{Nd}_.])do(?:\s|\{)""")

/** The frames a mark on a declaration reaches through: brackets of a call, a condition, a loop or a `catch`, a `when`'s branches, the file. */
private val REACHED_THROUGH = setOf("paren", "when", "top", "for", "catch")

/** A `// language=` or `/* language= */` comment and the language it names. */
private val LANGUAGE_COMMENT = regex("""(?://|/\*)\s*language=([\w-]+)(?=\s|\*/|$)""")

/** How far past a `// language=` comment what it marks may begin, as far as the Kotlin plugin looks for it. */
private const val LANGUAGE_COMMENT_REACH = 120

private val CONDITION_HEAD = regex("""(?:^|[^\p{L}\p{Nd}_])(?:if|when|for|while|catch)\s*$""")

/** Whether the `)` at [k] closes the condition of an `if`, a `when` or a loop, whose branch follows on the next line. */
private fun branchFollows(
    src: String,
    code: BooleanArray,
    comments: BooleanArray,
    k: Int,
): Boolean {
    var depth = 0
    var j = k
    while (j >= maxOf(0, k - 2_000)) {
        if (code[j] && src[j] == ')') depth++
        if (code[j] && src[j] == '(' && --depth == 0) break
        j--
    }
    if (j <= 0 || depth != 0) return false
    val from = maxOf(0, j - 12)
    val head = CONDITION_HEAD.find(src.substring(from, j)) ?: return false
    val word = from + head.range.first + head.value.indexOfFirst { it.isLetter() }
    // A handler's `catch (e: E)` follows the `}` of a block; `flow.catch(f)` and `catch(f)` are calls.
    if (src.startsWith("catch", word)) return src.getOrNull(pastComments(src, comments, word)) == '}'
    if (!src.startsWith("while", word)) return true
    // `do { } while (c)`, and `do x while (c)` on one line, have their branch before it.
    if (src.getOrNull(pastComments(src, comments, word)) == '}') return false
    val line = src.substring(src.lastIndexOf('\n', word - 1) + 1, word)
    return !DO_BEFORE.containsMatchIn(line)
}

private val LINE_GOES_ON_AFTER = regex("""^(?:[.?*/%&|:]|else\b)""")
private val HANDLER_NEXT = regex("""^(?:catch\s*\(|finally\b)""")
private val COMMENT = regex("""//[^\n]*|/\*[\s\S]*?\*/""")

/** Whether the `catch` or `finally` at [at] goes on from the block that [close] ends: a `try`'s or a handler's, not a lambda's. */
private fun handlerFollows(
    src: String,
    code: BooleanArray,
    at: Int,
    close: Int,
): Boolean {
    if (!HANDLER_NEXT.containsMatchIn(src.substring(at, minOf(src.length, at + 64)).replace(COMMENT, " "))) return false
    val open = opening(src, code, close, '{', '}') ?: return true
    val before = codeBefore(src, code, open)
    if (before < 0) return false
    if (src[before] == ')') {
        val paren = opening(src, code, before, '(', ')') ?: return false
        val word = codeBefore(src, code, paren)
        return word >= 4 && src.startsWith("catch", word - 4) && !isWordPart(src.getOrNull(word - 5))
    }
    return listOf("try", "finally").any { w ->
        before + 1 >= w.length && src.startsWith(w, before + 1 - w.length) && !isWordPart(src.getOrNull(before - w.length))
    }
}

/** Where the bracket [close] closes opens, read back at most 20,000 characters; `null` when it is farther. */
private fun opening(
    src: String,
    code: BooleanArray,
    close: Int,
    open: Char,
    shut: Char,
): Int? {
    var depth = 0
    var j = close
    while (j >= maxOf(0, close - 20_000)) {
        if (code[j] && src[j] == shut) depth++
        if (code[j] && src[j] == open && --depth == 0) return j
        j--
    }
    return null
}

/**
 * The last character of code before [at], past white space, comments and literals; -1 when there
 * is none. The full stop that ends a comment on the line above is not a receiver's dot.
 */
internal fun codeBefore(
    src: String,
    code: BooleanArray,
    at: Int,
): Int {
    var j = at - 1
    while (j >= 0 && (src[j].isWhitespace() || !code[j])) j--
    return j
}

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

    /** How many `if`s of a loop's statement still want their `else`, which may stand on a line of its own. */
    var ifs = 0

    /**
     * What was written next: a loop's statement without braces has begun where the bracket after the loop is closed,
     * and a function's expression where its `=` stands. A brace after the loop is its block, still to be read.
     */
    fun begun(brace: Boolean) {
        if (state == "after" && fn == null && !brace) {
            state = "stmt"
        } else if (state == "expr") {
            started = true
        }
    }

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

    /** Just past the `fun` last read in this frame, and for a function's parameters whether they are surely its own ([FUN_HEAD]). */
    var funAt = -1
    var own = false
    var cls = false

    /** Whether the `object` just read has no name and is a value, `val o = object { }`. */
    var anonymous = false

    /** Whether a declaration may begin after this frame's `}` on its line: a named class's body or an `init` block. */
    var member = false
    var whenBlock = false
    var loop = false

    /** The variable the brackets of a `catch` named, in sight up to where they closed until the block after them takes it. */
    var caught: Declaration? = null

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

    /** How many of [values] are what a `val` or `var` is being set to. */
    var initialising = 0

    /** The values written in this frame whose statements have not ended. */
    val values = ArrayList<Span>()

    /** For a block: whether it may be a lambda's, being no branch's, loop's or function's body. */
    var lambda = false

    /** The `// language=` comment last read here, just past it and whether it names HTML, until what follows it begins. */
    var comment: Pair<Int, Boolean>? = null

    /** Whether the element a `// language=` comment stands before, to the end of its statement, is HTML. */
    var element: Boolean? = null

    /** The same of the frames around this one when it opened: their element holds in it too. */
    var outerElement: Boolean? = null

    /** Whether the statement [element] stands in has ended: what begins next is in it only if it is a property's getter. */
    var elementEnded = false

    /** For parameters: where each literal in them starts, the mark of its parameter read when they close. */
    val literals = ArrayList<Int>()

    /** For a function's parameters: where its body is written, none once it returns to a label, and what each `return` of a block gives. */
    var body: Span? = null
    val returns = ArrayList<Span>()

    /** For a function's block: its parameters, and whether the block is surely that function's, with nothing but its type before it. */
    var function: ScopeFrame? = null
    var sure = false

    /** Whether the statement has an `if` or a `when` that is an operand, `"a" + if (c) ...`: none of its branches is a whole value. */
    var branchOperand = false

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
        initialising -= ended.count { it.initialiser }
        lastValues = ended.ifEmpty { return }
        values.removeAll { it.start <= at }
    }
}

/**
 * Whether a value stands before the word at [at], past blanks and comments: a name, a literal, a call's or an index's
 * bracket or a lambda's brace. A modifier the declaration already has, [said], and the arguments of an annotation that
 * ends at [annotationClosed] are none.
 */
private fun afterValue(
    src: String,
    code: BooleanArray,
    comments: BooleanArray,
    at: Int,
    said: Boolean,
    annotationClosed: Int,
): Boolean {
    var k = at - 1
    // A line break outside a comment is one the line carried on past, or the word would begin a declaration: no further
    // back than the line, and not past what any line holds.
    while (k >= 0 && at - k < 2_000 && (src[k] == ' ' || src[k] == '\t' || comments[k])) k--
    if (k < 0) return false
    if (src[k] == '\n' || src[k] == '\r' || at - k >= 2_000 || !code[k]) return true
    if (isWordPart(src[k])) {
        var from = k
        while (from > 0 && isWordPart(src[from - 1])) from--
        return !(said && MODIFIER_WORDS.containsKey(src.substring(from, k + 1)))
    }
    return if (src[k] == ')') k + 1 != annotationClosed else src[k] == ']' || src[k] == '}'
}

/**
 * What the line breaks of a file say: whether a line carries on at its break, and whether an expression ends there. The
 * breaks are asked in order, and each character is looked at once however many of them ask: what lies before a break is
 * read back to the break asked before, and what follows it forward once for all the breaks it follows.
 */
private class LineBreaks(
    private val src: String,
    private val code: BooleanArray,
    private val comments: BooleanArray,
    private val types: IntArray,
) {
    private var asked = -1
    private var last = -1
    private var next = -1

    /** The last character before [at] that is neither blank nor a comment's, -1 when there is none. */
    fun before(at: Int): Int {
        var k = at - 1
        while (k > asked && (src[k].isWhitespace() || comments[k])) k--
        // A line of nothing but blanks and comments leaves the last code where it was.
        if (k > asked) last = k
        asked = at
        return last
    }

    /** The first character after [at] that is neither blank nor a comment's, the length when there is none. */
    fun after(at: Int): Int {
        if (next > at) return next
        var k = at + 1
        while (k < src.length && (src[k].isWhitespace() || comments[k])) k++
        next = k
        return k
    }

    fun carriesOn(at: Int): Boolean {
        val k = before(at)
        return k >= 0 && code[k] && goesOn(src, code, comments, types, k)
    }

    fun ends(
        at: Int,
        elseGoesOn: Boolean = true,
    ): Boolean = lineEnds(src, code, comments, types, at, elseGoesOn, before(at), after(at))
}

internal fun isWordPart(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

/**
 * The last offset before [at] past white space and comments, -1 when there is none. A literal
 * stops it, as it is the end of a value: the offset is then the literal's last character, even
 * where its text after a template reads like a comment, `"${dir}// y"`.
 */
private fun pastComments(
    src: String,
    comments: BooleanArray,
    at: Int,
): Int {
    var k = at - 1
    while (k >= 0 && (src[k].isWhitespace() || comments[k])) k--
    return k
}

/** The first offset after [at] past white space and comments; a comment at the next line's start is passed over. */
private fun pastBlanks(
    src: String,
    comments: BooleanArray,
    at: Int,
): Int {
    var k = at + 1
    while (k < src.length && (src[k].isWhitespace() || comments[k])) k++
    return k
}

/** Whether the line break at [at] ends an expression: nothing before it or after it carries the expression on. */
internal fun lineEnds(
    src: String,
    code: BooleanArray,
    comments: BooleanArray,
    types: IntArray,
    at: Int,
    elseGoesOn: Boolean = true,
    // What the break follows and what follows it, past blanks and comments: a literal stops both, as it is a value.
    last: Int = pastComments(src, comments, at),
    k: Int = pastBlanks(src, comments, at),
): Boolean {
    if (last >= 0 && code[last] && goesOn(src, code, comments, types, last)) return false
    val next = src.substring(k, minOf(src.length, k + 12))
    // An `else` on the next line is the statement's own only where it has an `if`; else an outer `if`'s.
    if (!elseGoesOn && elseAt(src, k)) return true
    // `try { }` goes on with a `catch` or `finally` after its block; `catchUp(x)` is a call of its own.
    if (last >= 0 && src[last] == '}' && handlerFollows(src, code, k, last)) return false
    return !LINE_GOES_ON_AFTER.containsMatchIn(next)
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

/** [text], written at [offset] of the file, cut at each comma outside brackets, a type's of [types] among them. */
internal fun splitTopLevel(
    text: String,
    types: IntArray,
    offset: Int,
): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var from = 0
    for ((k, c) in text.withIndex()) {
        // [types] holds the `>` at a `<` that opens a type's brackets, and the `<` at its `>`.
        val pair = types[offset + k]
        if (c in "([{" || pair > offset + k) depth++
        if (c in ")]}" || pair in 0 until offset + k) depth--
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

/**
 * What stands between `fun`, past its type parameters, and a function's own brackets, on its line: a receiver and the
 * name, or nothing. Brackets after anything else, or after a word that begins a statement, are not its parameters.
 */
private val FUN_HEAD = regex("""[ \t]*(?:[\p{L}_`][^\s()]*\.[ \t]*)?(?:($NAME)|`[^`\n]+`)?[ \t]*""")
private const val FUN_HEAD_LENGTH = 300
private val STATEMENT_WORDS = "if for while when do try return throw else in is as super this typeof".split(" ").toSet()

// A function without a name may have a receiver, `fun String.()`, which one in brackets has too, `fun (String).()`.
private val UNNAMED =
    mapOf(
        "object" to regex("""\s*[:{]"""),
        "fun" to regex("""\s*(?:[\p{L}_][\p{L}\p{Nd}_.]*(?:<(?:[^<>\n]|<[^<>\n]*>)*>)?\??\s*\.\s*)?\("""),
    )
private val RECEIVER_DOT = regex("""\s*\??\s*\.""")

/** The words that may stand before a declaration, each with what it says of it. */
internal val MODIFIER_WORDS: Map<String, Int> =
    "public protected internal final lateinit inline suspend tailrec operator infix external data enum annotation inner value actual expect"
        .split(" ")
        .associateWith { 0 } + listOf("open", "abstract", "sealed").associateWith { EXTENDED } +
        mapOf("private" to PRIVATE, "const" to CONST, "override" to OVERRIDES, "companion" to COMPANION)

private val QUOTED = regex("`[^`\n]+`")

// A part of an annotation's name may be in backticks, `` @`A` `` or `` @a.`b` ``.
private const val ANNOTATION_PART = """(?:[\p{L}_][\p{L}\p{Nd}_]*|`[^`\n]+`)"""

/** The words a modifier may declare a property, a function or a class with, past annotations on its line. */
private val DECLARES = setOf("val", "var", "fun", "class", "interface", "object")

/** Kotlin's hard keywords, which no annotation's name can be. */
private val HARD_KEYWORDS =
    "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while"
        .split(' ')

// A use-site target's colon may have blanks and line breaks after it, but never a hard keyword, and a name is never
// followed by a colon: `@field:` before `val` is no annotation.
private val ANNOTATION =
    regex(
        """@(?:[a-z]+:\s*)?(?!(?:${HARD_KEYWORDS.joinToString("|")})(?![\p{L}\p{Nd}_]))""" +
            """(?:\[|$ANNOTATION_PART(?:\.$ANNOTATION_PART)*\.?(?![\p{L}\p{Nd}_]|\s*:))""",
    )

/**
 * Just past the annotation written at [at], its type arguments and its arguments too where they close, or [at] where none
 * is. Brackets after a blank that hold or precede a function type's arrow, `@Composable () -> Unit`, are the type's.
 */
internal fun annotationEnd(
    src: String,
    code: BooleanArray,
    types: IntArray,
    at: Int,
): Int {
    val named = (ANNOTATION.matchAt(src, at) ?: return at).range.last + 1
    val name = typeArgumentsAt(src, types, named, blanks = " \t")?.let { it.last + 1 } ?: named
    // `@[A B]` holds its annotations in the bracket; Kotlin allows blanks, a line break too, before the arguments, `@A ("x")`.
    val args = (name until src.length).firstOrNull { !src[it].isWhitespace() }?.takeIf { src[it] == '(' } ?: name
    val open = if (src[name - 1] == '[') name - 1 else args
    val depth = IntArray(3)
    // Past 5,000 characters of code an annotation is one left open; a long literal in it, a query, counts for nothing.
    var budget = if (src.getOrNull(open) == '(' || open < name) 5_000 else 0
    var arrow = false
    var k = open - 1
    while (++k < src.length && budget > 0) {
        if (code[k]) budget--
        if (code[k] && src[k] in "([{") depth["([{".indexOf(src[k])]++
        if (code[k] && src.startsWith("->", k) && depth.sum() == 1) arrow = true
        val closes = if (code[k]) ")]}".indexOf(src[k]) else -1
        // A bracket that closes none of its kind is past an annotation left open.
        if (closes >= 0 && --depth[closes] < 0) break
        // `@Composable () -> Unit`, `@Composable (() -> Unit)?`: brackets after a blank are a type's where an arrow
        // follows them or stands at their first depth.
        if (closes >= 0 && depth.sum() == 0) {
            val next = (k + 1 until src.length).firstOrNull { !src[it].isWhitespace() } ?: src.length
            return if (open > name && (arrow || src.startsWith("->", next))) name else k + 1
        }
        // An annotation first on its line is past an annotation left open; `@A(@B 1)` annotates a value, and `this@A` names a label.
        if (k > open && src[k] == '@' && code[k] && firstOnLine(src, k)) break
        // No argument of an annotation holds a property: one written there begins past an annotation left open.
        // `` K.`val` `` is a name in backticks.
        if (src[k] == 'v' && code[k] && !isWordPart(src.getOrNull(k - 1)) && src.getOrNull(k - 1) != '`' &&
            VAL_OR_VAR.matchAt(src, k) != null
        ) {
            return k
        }
    }
    return open
}

private val VAL_OR_VAR = regex("""va[lr](?![\p{L}\p{Nd}_])""")

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

/** A tag, an end tag, a comment or a doctype, anywhere in a literal: `a < b` and `<3` are none. */
private val TAG = regex("""<[A-Za-z/!]""")

/** The `val`s of a file with a value, by name, and the index among them of the innermost whose value stands at each offset. */
private class Valued(
    val declarations: List<Pair<String, Declaration>>,
    val owner: IntArray,
)

/** Whether the `.` at [dot] ends core's package written out, `io.github.markusaugust.streamlord.core.domain.Trusted`, blanks before it too. */
private fun qualifiedByCore(
    src: String,
    code: BooleanArray,
    dot: Int,
): Boolean {
    val end = codeBefore(src, code, dot) + 1
    val from = end - DOMAIN.length
    return from >= 0 && src.startsWith(DOMAIN, from) && !isWordPart(src.getOrNull(from - 1)) && src.getOrNull(from - 1) != '.'
}

/**
 * Where those of [literals], in the order of the file, start that stand in no call's arguments within the bracket that
 * opens at [open]: in parentheses, square brackets, or a lambda handed to a call. The braces of a branch,
 * `if (c) { ... }`, `else { }`, `"a" -> { }`, are the block's own. One pass over the bracket.
 */

private fun outsideArguments(
    src: String,
    code: BooleanArray,
    open: Int,
    literals: List<KotlinString>,
): List<Int> {
    val out = ArrayList<Int>()
    // Each bracket open at k, and whether it holds arguments; how many of those are open.
    val stack = ArrayList<Boolean>()
    var arguments = 0
    var k = open + 1
    for (s in literals) {
        while (k < s.start) {
            val c = src[k]
            if (code[k] && (c == '(' || c == '[' || c == '{')) {
                // A parenthesis after a name or a bracket is a call's; one after an operator only groups.
                val holds = if (c == '{') !branch(src, code, open, k) else c == '[' || calls(src, code, k)
                stack += holds
                if (holds) arguments++
            } else if (code[k] && (c == ')' || c == ']' || c == '}') && stack.isNotEmpty()) {
                if (stack.removeAt(stack.lastIndex)) arguments--
            }
            k++
        }
        if (arguments == 0 && !compared(src, code, s)) out += s.start
    }
    return out
}

/** Whether the parenthesis at [paren] holds a call's arguments: it follows a name, a closing bracket or `>` of type arguments. */
private fun calls(
    src: String,
    code: BooleanArray,
    paren: Int,
): Boolean {
    var k = paren - 1
    while (k >= 0 && (!code[k] || src[k].isWhitespace())) k--
    return k >= 0 && (isWordPart(src[k]) || src[k] == ')' || src[k] == ']' || src[k] == '>' || src[k] == '`')
}

/** Whether [s] is compared, not written: an operand of `==` or `!=`, or the condition of a `when` branch. */
private fun compared(
    src: String,
    code: BooleanArray,
    s: KotlinString,
): Boolean {
    var before = s.start - 1
    while (before >= 0 && (!code[before] || src[before].isWhitespace())) before--
    var after = s.end
    while (after < src.length && (!code[after] || src[after].isWhitespace())) after++
    return listOf("==", "!=").any { src.startsWith(it, before - 1) || src.startsWith(it, after) } || src.startsWith("->", after)
}

private val BRANCH_WORD = regex("""(?:^|[^\p{L}\p{Nd}_])(?:try|finally|do|when)\s*$""")

/** Whether the brace at [brace] opens a branch: after `else`, `try`, `->`, or the parentheses of `if`, `when`, a loop or `catch`. */
private fun branch(
    src: String,
    code: BooleanArray,
    open: Int,
    brace: Int,
): Boolean {
    var k = brace - 1
    while (k > open && (!code[k] || src[k].isWhitespace())) k--
    if (k <= open) return false
    if (opensBranch(src, code, open, k)) return true
    return src[k] != ')' && BRANCH_WORD.containsMatchIn(src.substring(maxOf(open, k - 20, 0), k + 1))
}

/** The words after which a value begins whole: `return if (c) ...`. */
private val VALUE_WORDS = setOf("return", "else", "throw")

/** Whether the word `else` begins at [k]. */
private fun elseAt(
    src: String,
    k: Int,
) = src.startsWith("else", k) && !isWordPart(src.getOrNull(k + 4))

/** How far [declarations]'s `wholeBranch` follows a value: a `return`'s, or what a local is set to. */
private const val RETURNED = 0
private const val LOCAL_SET = 1

/** Whether the code ending at [k] opens a branch of what follows: `else`, `->` or the `)` of a condition, its `(` after [open]. */
private fun opensBranch(
    src: String,
    code: BooleanArray,
    open: Int,
    k: Int,
): Boolean =
    (k >= 1 && src.startsWith("->", k - 1)) || (src[k] == ')' && closesCondition(src, code, open, k)) ||
        (k >= 3 && elseAt(src, k - 3) && !isWordPart(src.getOrNull(k - 4)))

/** Whether the `)` at [k] closes the condition of an `if`, a `when`, a loop or a `catch`, its `(` after [open]. */
private fun closesCondition(
    src: String,
    code: BooleanArray,
    open: Int,
    k: Int,
): Boolean {
    var depth = 0
    var j = k
    while (j > open) {
        if (code[j] && src[j] == ')') depth++
        if (code[j] && src[j] == '(' && --depth == 0) break
        j--
    }
    return CONDITION_HEAD.containsMatchIn(src.substring(maxOf(open, j - 20, 0), maxOf(j, 0)))
}

/** [src] with each comment blank, one space for each of its characters but a line break, so that every offset holds. */
private fun withoutComments(
    src: String,
    comments: BooleanArray,
): String = CharArray(src.length) { if (comments.getOrElse(it) { false } && src[it] != '\n') ' ' else src[it] }.concatToString()

/** The words a name may follow in a type's brackets, `<out T>`, `<reified T>`, `<suspend () -> Unit>`. */
private val PROJECTION_WORDS = setOf("in", "out", "reified", "suspend")

/** The words whose type parameters may be bounded right after them, `fun <T : Any>`, and those after the name they declare, `class Box<T : Any>`. */
private val BOUNDED_AFTER = setOf("fun", "val", "var")
private val BOUNDED_NAMED = setOf("class", "interface")

/**
 * Each type's brackets in the code: at a `<` that opens them, where its `>` stands, and at that `>`, where the `<`
 * stands; -1 elsewhere. Read in one pass, as Kotlin's grammar has a type's arguments: names joined by `.`, `?`, `&`
 * and commas, a projection's `in`, `out` or `*`, annotations, a function type's brackets and arrow, and brackets of
 * the same kind; the arguments of an annotation in round brackets may hold anything but a brace or a `;`. A `:` stands
 * only in the type parameters a `fun`, `val`, `var`, `class` or `interface` declares, `<T : Any>`. Anything else, a
 * number, a literal, an operator, two names in a row or a brace, makes the `<` a comparison: `x<y, b: Int`, `{ it<0 }`
 * and `n<m and n>` open none, and `n < 0 || v is Pair<*, *>` opens one, at `Pair`.
 */
internal fun typeBrackets(
    src: String,
    code: BooleanArray,
    comments: BooleanArray,
): IntArray {
    val pairs = IntArray(src.length) { -1 }
    // Each `<` still open: where it stands, the depth of round brackets it was opened at, and whether a bound's `:` may follow.
    val open = ArrayList<Triple<Int, Int, Boolean>>()
    var parens = 0
    // The last token: `w` a name, `m` an annotation's name, `<` a `<` or a comma, else the character itself.
    var last = ' '
    // Where the last name and the one before it stand, read in place.
    var word = 0 until 0
    var previous = 0 until 0

    fun among(
        name: IntRange,
        words: Set<String>,
    ) = words.any { it.length == name.last - name.first + 1 && src.startsWith(it, name.first) }

    fun drop(above: Int) {
        while (open.isNotEmpty() && open.last().second >= above) open.removeAt(open.size - 1)
    }
    var i = 0
    while (i < src.length) {
        val c = src[i]
        val own = open.lastOrNull()?.second == parens
        if (!code[i]) {
            // A literal stands in no type; an annotation's arguments may hold one.
            if (!comments[i]) drop(parens)
        } else if (c == '@') {
            // An annotation's name, `@a.B` or `@field:A`, is one token, its arguments brackets of their own.
            i = ANNOTATION.matchAt(src, i)?.let { it.range.last + 1 } ?: (i + 1)
            last = 'm'
            continue
        } else if (isWordPart(c) || c == '`') {
            var end = i + 1
            if (c == '`') end = src.indexOf('`', end).takeIf { it > 0 && '\n' !in src.substring(i, it) }?.plus(1) ?: end
            while (c != '`' && end < src.length && code[end] && isWordPart(src[end])) end++
            // A name after a name, `n<m and`, or a number, `x<1`, stands in no type.
            if (own && (c.isDigit() || (last == 'w' && !among(word, PROJECTION_WORDS)))) drop(parens)
            previous = word
            word = i until end
            last = 'w'
            i = end
            continue
        } else {
            // A star stands alone, `<*>` or `<A, *>`, one `&` joins `T & Any`, and `->` points after a function type's
            // brackets; any other operator compares.
            val fits =
                when (c) {
                    '<' -> open.add(Triple(i, parens, among(word, BOUNDED_AFTER) || (last == 'w' && among(previous, BOUNDED_NAMED))))
                    '(', ',', '.', '?' -> true
                    '*' -> last == '<'
                    '&' -> src.getOrNull(i + 1) != '&' && src.getOrNull(i - 1) != '&'
                    ':' -> open.lastOrNull()?.third == true
                    '-' -> src.getOrNull(i + 1) == '>' && last == ')'
                    else -> c.isWhitespace() || c in ">){};"
                }
            if (own && !fits) drop(parens)
            when {
                c == '>' && own && src[i - 1] != '-' -> {
                    val at = open.removeAt(open.size - 1).first
                    pairs[at] = i
                    pairs[i] = at
                }

                c == '(' -> {
                    parens++
                }

                c == ')' -> {
                    drop(parens--)
                }

                c in "{};" -> {
                    drop(Int.MIN_VALUE)
                }
            }
            if (!c.isWhitespace()) last = if (c == ',') '<' else c
        }
        i++
    }
    return pairs
}

/**
 * The function whose parameters [g] holds, up to [close]: its name, the class it is declared in, each parameter marked
 * `@Language("HTML")`, by place and by name, as [html] has them from the parameters' reading, and what the scanner kept
 * of its result.
 */
private fun functionAt(
    src: String,
    types: IntArray,
    g: ScopeFrame,
    close: Int,
    html: Set<Any>,
): Pair<String, FunctionDeclaration>? {
    val name = g.name ?: return null
    val owner = g.owner?.let { it.open..(if (it.closeAt < 0) src.length else it.closeAt) }
    val body = g.body?.takeIf { it.end >= 0 && !overridable(g.funModifiers, g.owner?.extended, g.owner?.contract) }
    // Between the parameters and the body stands the type or nothing, or the function is not read.
    val type = body?.takeIf { it.start - close < 1_000 }?.let { returnType(src, types, close + 1, it.start - 1) }
    val results = if (body != null && src[body.start - 1] == '{') g.returns else listOfNotNull(body)
    val values = if (type == null || html.isNotEmpty()) emptyList() else results
    return name to
        FunctionDeclaration(owner, html, g.open, g.local, type != null && g.funModifiers and HTML != 0, type?.ifEmpty { null }, values)
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
    types: IntArray,
    functions: MutableMap<String, MutableList<FunctionDeclaration>>? = null,
    marks: MutableSet<Int> = HashSet(),
): Map<String, List<Declaration>> {
    // Each function's brackets, where they close and its marked parameters, by place and by name, read once the classes have closed.
    val declared = ArrayList<Triple<ScopeFrame, Int, Set<Any>>>()
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

    val breaks = LineBreaks(src, code, comments, types)

    // The literals each value is set to whole, or as a sum's operands: a returned local's are marked.
    val setTo = HashMap<Span, ArrayList<Int>>()

    // Each name a marked `return` gives whole, `return s`, and where: the literal its local is set to is marked.
    val returnedNames = ArrayList<Pair<String, Int>>()

    // What each `val` or `var` is set to where it is declared.
    val initialisers = HashMap<Declaration, Span>()

    /** Whether the operand that ends at [end] ends there: a `)`, `]`, `}`, `,`, `else` or the statement's end follows it. */
    fun endsOperand(end: Int): Boolean {
        val after = pastBlanks(src, comments, end - 1)
        val line = (end until after).firstOrNull { src[it] == '\n' }
        // Asked on its own: the breaks the scan asks are asked in order, and this one is ahead of them.
        return after >= src.length || src[after] in "});,]" || elseAt(src, after) ||
            (line != null && lineEnds(src, code, comments, types, line))
    }

    /**
     * Records in [f] an `if` or a `when` at [at] that is an operand, an operator standing before it, and for the innermost
     * `return`'s value in [f] the `if`s and `else`s it holds: `if (c) return "a" else "b"` returns no `"b"`.
     */
    fun branchWord(
        f: ScopeFrame,
        word: String,
        at: Int,
    ) {
        // The last value begun is the innermost `return`'s, and an `else` of an `if` around the `return` ends it.
        val v = f.values.lastOrNull()?.takeIf { it.returned }
        if (v != null && word == "if") v.ifs++
        if (v != null && word == "else" && v.ifs-- == 0) {
            v.end = pastComments(src, comments, at) + 1
            f.values.remove(v)
        }
        if (word != "if" && word != "when") return
        // An operator ending the code before it, on its line or the one above, which a literal there is not.
        val before = pastComments(src, comments, at)
        val c = src.getOrNull(before)
        if (c == null || !code[before]) return
        // An infix word before it on its line, `"a" to if (c) ...`, is an operator too, but for the words that begin a value.
        if (isWordPart(c) && (before until at).none { src[it] == '\n' }) {
            var w = before
            while (w > 0 && isWordPart(src[w - 1])) w--
            if (src.substring(w, before + 1) !in VALUE_WORDS) f.branchOperand = true
            return
        }
        if (!goesOn(src, code, comments, types, before)) return
        val previous = src.getOrNull(before - 1)
        if (c in "+-*/%|&<.!" || (c == '>' && previous != '-') || (c == '=' && previous != null && previous in "=!<>")) {
            f.branchOperand = true
        }
    }

    /**
     * Whether the literal or name from [at] to [end], in [f] whose value begins at [from], is a whole branch of it as the
     * Kotlin plugin follows one into a [RETURNED] value: through `if`, `?:` and a sum, not into a call, brackets or another
     * operator; into what a local is [LOCAL_SET] through a sum alone. A name stands alone, and the plugin looks through
     * `trimIndent()` and `trimMargin()`, a literal's then being no operand of a sum.
     */
    fun wholeBranch(
        f: ScopeFrame,
        from: Int,
        at: Int,
        end: Int,
        mode: Int,
    ): Boolean {
        if (end < 0) return false
        val trimmed = TRIM_CALL.matchAt(src, pastBlanks(src, comments, end - 1))
        val operandEnd = trimmed?.range?.last?.plus(1) ?: end
        val after = pastBlanks(src, comments, operandEnd - 1)
        val before = codeBefore(src, code, at)
        val sums = !isWordPart(src[at]) && trimmed == null
        val branches = mode == RETURNED && !f.branchOperand
        val opens =
            before < from || (sums && plusAt(src, before) && src.getOrNull(before - 1) != '+') ||
                (branches && ((before >= 1 && src.startsWith("?:", before - 1)) || opensBranch(src, code, from, before)))
        val closes =
            endsOperand(operandEnd) || (sums && plusAt(src, after)) ||
                (branches && src.startsWith("?:", after))
        return opens && closes && (branches || !elseAt(src, after))
    }

    /**
     * Records whether a mark reaches the literal at [at], as the Kotlin plugin reads one: the closest `// language=`
     * comment, before the element it stands in; else the declaration it is the value of, reached without passing a block,
     * a lambda or a class's body; else the marked function a `return` it is a whole branch of returns from. In
     * parameters it is the parameter's, read when they close.
     */
    fun reach(at: Int) {
        stack.last().let { it.element ?: it.outerElement }?.let {
            if (it) marks += at
            return
        }
        for (k in stack.indices.reversed()) {
            val f = stack[k]
            val v = f.values.lastOrNull { it.start <= at }
            if (v != null) {
                // A return's value, and what a local is set to, is followed in its own frame alone, into no brackets.
                val direct = k == stack.lastIndex
                val end = if (direct && (v.returned || v.initialiser)) readKotlinStringAt(src, at)?.end ?: -1 else -1
                if (v.html && (!v.returned || (direct && wholeBranch(f, v.start, at, end, RETURNED)))) marks += at
                if (v.initialiser && direct && wholeBranch(f, v.start, at, end, LOCAL_SET)) setTo.getOrPut(v) { ArrayList() } += at
                return
            }
            // A template's braces, `"${...}"`, are none of the literal's own.
            val template = f.kind == "block" && f.open > 0 && src[f.open - 1] == '$' && !code[f.open - 1]
            if (f.kind == "params" || f.kind == "ctor") f.literals += at
            if (!template && f.kind !in REACHED_THROUGH) return
        }
    }

    /** Whether the word at [at] begins a getter of a property in [f]: `get()`. After a value, `get("/")` is a call. */
    fun getterAt(
        f: ScopeFrame,
        at: Int,
    ) = (f.kind == "top" || f.kind == "class") && src.startsWith("get", at) && !isWordPart(src.getOrNull(at + 3)) &&
        GETTER_AFTER.matchAt(src, at + 3) != null

    /**
     * Whether a mark reaches what a `return` read now gives: in a local's value, `val a = if (c) "x" else return "<b>"`,
     * the local's own; else that of [inner], the innermost function's block. A lambda between them stops either.
     */
    fun returnMarked(inner: ScopeFrame?): Boolean {
        val local = stack.lastOrNull { it.initialising > 0 }?.takeIf { inner == null || stack.indexOf(it) >= stack.indexOf(inner) }
        val owner = local ?: inner ?: return false
        val marked = local?.values?.last { it.initialiser }?.html ?: (inner!!.function!!.funModifiers and HTML != 0)
        return marked && stack.subList(stack.indexOf(owner) + 1, stack.size).none { it.lambda }
    }

    /** Records [word], from [at] to [end], where a marked `return` in [f] gives it whole: `return s`. */
    fun returnedName(
        f: ScopeFrame,
        word: String,
        at: Int,
        end: Int,
    ) {
        val v = f.values.lastOrNull { it.start <= at } ?: return
        if (v.html && v.returned && src.getOrNull(at - 1) != '.' && wholeBranch(f, v.start, at, end, RETURNED)) returnedNames += word to at
    }

    /** Ends the element a `// language=` comment marked in [f] where what begins at [at] is no getter of it. */
    fun endElement(
        f: ScopeFrame,
        at: Int,
    ) {
        if (!f.elementEnded) return
        f.elementEnded = false
        if (!getterAt(f, at)) f.element = null
    }

    /** Whether a declaration's word follows [at] past words and annotations, twelve at most: `private @A<B> val`. */
    fun declaresAfter(at: Int): Boolean {
        var k = at
        repeat(13) {
            val from = k
            while (k < src.length && (src[k] == ' ' || src[k] == '\t')) k++
            if (k == from) return false
            if (src.getOrNull(k) == '@') {
                k = annotationEnd(src, code, types, k)
                return@repeat
            }
            val end = (k until src.length).firstOrNull { src[it] !in 'a'..'z' } ?: src.length
            if (end == k || isWordPart(src.getOrNull(end))) return false
            if (src.substring(k, end) in DECLARES) return true
            k = end
        }
        return false
    }

    /** Whether the bracket at [open] holds a receiver's type, `fun ((Int) -> Unit).call()`: a dot follows where it closes. */
    fun receiverIn(open: Int): Boolean {
        var depth = 0
        for (k in open until minOf(src.length, open + 2_000)) {
            if (!code[k]) continue
            if (src[k] == '(') depth++
            if (src[k] == ')' && --depth == 0) return RECEIVER_DOT.matchAt(blank, k + 1) != null
            // No type holds a block, a value or another function.
            if (src[k] in "{}=;" || (src[k] == 'f' && src.startsWith("fun", k) && !isWordPart(src.getOrNull(k + 3)))) return false
        }
        return false
    }

    /** What is written from [from] to [to], each annotation blank, the brackets and quotes of its arguments and its type arguments too. */
    fun withoutAnnotations(
        from: Int,
        to: Int,
    ): String {
        val out = blank.toCharArray(from, to)
        var i = from
        while (i < to) {
            val end = if (src[i] == '@' && code[i]) minOf(annotationEnd(src, code, types, i), to) else i
            if (end > i) {
                for (j in i until end) if (out[j - from] != '\n') out[j - from] = ' '
                i = end
            } else {
                i++
            }
        }
        return out.concatToString()
    }

    /**
     * Each parameter between the brackets of a function or a constructor; [visible] for a constructor other files can
     * see the properties of. Each parameter marked HTML goes into [marked] by its place and its name.
     */
    fun parameters(
        f: ScopeFrame,
        close: Int,
        visible: Boolean = false,
        holder: ScopeFrame? = null,
        marked: MutableSet<Any>? = null,
    ): List<Declaration> {
        val out = ArrayList<Declaration>()
        val cuts = listOf(f.open) + f.commas + close
        for (k in 0 until cuts.size - 1) {
            val text = blank.substring(cuts[k] + 1, cuts[k + 1])
            val written = withoutAnnotations(cuts[k] + 1, cuts[k + 1])
            val m = PARAMETER.find(written)
            if (m == null) {
                // A name the pattern does not read, `` `the body` ``, is not declared, but its mark still holds its place.
                val colon = (0 until written.length).firstOrNull { written[it] == ':' && code[cuts[k] + 1 + it] } ?: written.length
                if (HTML_ANNOTATION.findAll(text.substring(0, colon)).any { code[cuts[k] + 1 + it.range.first] }) marked?.add(k)
                continue
            }
            val modifiers = m.groupValues[1].split(' ', '\t', '\n', '\r').fold(0) { all, word -> all or (MODIFIER_WORDS[word] ?: 0) }
            val vararg = VARARG.containsMatchIn(m.groupValues[1])
            val type = if (vararg) null else m.groups[3]?.value
            val arguments =
                if (vararg ||
                    m.groups[3] == null
                ) {
                    null
                } else {
                    typeArgumentsAt(blank, types, cuts[k] + 1 + m.range.last + 1)?.let {
                        withoutAnnotations(it.first + 1, it.last)
                    }
                }
            // The mark is one of the annotations before the name, in code: one in the default's text marks nothing.
            val nameAt = PARAMETER.groupRange(written, m, 2)!!.first
            val html = HTML_ANNOTATION.findAll(text.substring(0, nameAt)).any { code[cuts[k] + 1 + it.range.first] }
            if (html && marked != null) {
                marked += k
                marked += m.groupValues[2]
            }
            out +=
                add(
                    m.groupValues[2],
                    Declaration(
                        cuts[k] + 1 + m.range.first,
                        f.open,
                        type,
                        html = html,
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

    /** The properties a `val` or a `var` just before [end] declares in [f], one or a destructured list. */
    fun property(
        f: ScopeFrame,
        word: String,
        end: Int,
    ) {
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
            // What it is set to is its value, which its mark reaches as a `val`'s does.
            SET_TO.matchAt(src, end + destructured.value.length)?.let {
                f.begin(Span(it.range.last + 1, html = html)).initialiser = true
                f.initialising++
            }
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
            val m = VAL_AFTER.matchAt(src, typeArgumentsAt(src, types, end)?.let { it.last + 1 } ?: end)
            // `val String.shout`: an extension, read on a receiver and never by its name alone.
            if (m != null && m.groupValues[1].isEmpty()) {
                val at = VAL_AFTER.groupRange(src, m, 2)!!.first
                val literal = if (m.groups[4] == null) plainLiteralAt(src, m.range.last + 1) else null
                val bare = m.groups[4] == null && literal == null
                val initialiser = if (bare) markupInitialiser(src, m.range.last + 1, aliases) else null
                val markup = initialiser != null
                val trusted = initialiser?.first == "Trusted"
                val trustedText = if (trusted && word == "val") literalTrusted(src, initialiser.second) else null
                val arguments = VAL_AFTER.groupRange(src, m, 3)?.let { typeArgumentsAt(blank, types, it.last + 1) }
                // The value of a `val` set once, which a name then stands for.
                val set = VALUE_AFTER.matchAt(src, arguments?.let { it.last + 1 } ?: (m.range.last + 1))?.range?.last
                val valueAt = if (bare && !markup) set?.plus(1) else literal?.first
                val value = valueAt?.takeIf { word == "val" }?.let { f.begin(Span(it)) }
                // What it is set to is its value however it is written, which the mark on it reaches.
                val initialised = value ?: set?.let { f.begin(Span(it + 1)) }
                initialised?.html = html
                val d =
                    add(
                        m.groupValues[2],
                        Declaration(
                            at,
                            scopeStart(at),
                            m.groups[3]?.value,
                            m.groups[4]?.value,
                            if (constant) literal?.let { src.substring(it) } else null,
                            constant,
                            html,
                            literal != null,
                            markup,
                            if (bare && !markup) textInitialiser(src, m.range.last + 1) else null,
                            arguments?.let { withoutAnnotations(it.first + 1, it.last) },
                            value,
                            shared,
                            trusted,
                            trustedText,
                            aliased = initialiser?.third == true,
                            overridable = overridable,
                            markupAt = initialiser?.second ?: -1,
                        ),
                    )
                if (owner.kind != "top") owner.ends += d
                initialised?.let {
                    initialisers[d] = it
                    it.initialiser = true
                    f.initialising++
                }
            }
        }
    }

    /** Where what follows a `// language=` comment in [f] begins at [at]: it is the element, within [LANGUAGE_COMMENT_REACH] characters. */
    fun begins(
        f: ScopeFrame,
        at: Int,
    ) {
        val (end, html) = f.comment ?: return
        // What touches the comment, `/* language=HTML */"..."`, is none it stands before.
        f.element = html.takeIf { at - end in 1..LANGUAGE_COMMENT_REACH }
        f.comment = null
        // Inside an expression on its line, `c -> /* language=HTML */ "b"`, it marks what follows it alone, not the rest of
        // the statement; after a sum's `+`, on its line or the next, it marks nothing.
        val k = pastComments(src, comments, at)
        f.elementEnded = k >= 0 && (k until at).none { src[it] == '\n' } && !(code[k] && src[k] in "{};=(,")
        if (k >= 0 && code[k] && src[k] == '+') f.element = null
    }

    // Where the last annotation read ends: a modifier after its arguments is a declaration's.
    var annotationClosed = -1

    // Just past the type's brackets the scanner is in, whose commas part no list: `Map<String, Int>`.
    var typed = -1
    var i = 0
    while (i < src.length) {
        val f = stack.last()
        if (types[i] > i) typed = maxOf(typed, types[i])
        // A line that carries on, `a +` or `else`, gives its next line's first word to its expression, not to a declaration.
        if (src[i] == '\n' && f.after == 1 && !breaks.carriesOn(i)) f.after = 0
        if (!code[i]) {
            // A literal is a value, and what stands before it is written before no declaration; a comment is passed over.
            if ((i == 0 || code[i - 1] || comments[i - 1]) && !comments[i]) {
                f.reset()
                if (f.kind != "for") f.pending?.begun(brace = false)
                endElement(f, i)
                begins(f, i)
                if (isStringStart(src, i)) reach(i)
            } else if (comments[i] && (i == 0 || !comments[i - 1])) {
                val m = LANGUAGE_COMMENT.matchAt(src, i)
                if (m != null) {
                    var end = i
                    while (end < src.length && comments[end]) end++
                    f.comment = end to m.groupValues[1].equals("html", ignoreCase = true)
                    f.element = null
                } else if (!src.startsWith("/**", i)) {
                    // Another comment between a mark and what follows it, but a documentation comment, takes the mark away.
                    f.comment = null
                }
            }
            i++
            continue
        }
        val c = src[i]
        if (!c.isWhitespace()) {
            endElement(f, i)
            begins(f, i)
        }
        if ((c.isLetter() || c == '_') && !isWordPart(src.getOrNull(i - 1))) {
            // A loop's own brackets hold its variable, not its statement.
            if (f.kind != "for") f.pending?.begun(brace = false)
            f.pending?.takeIf { it.state == "stmt" }?.let { p ->
                if (src.startsWith("if", i) && !isWordPart(src.getOrNull(i + 2))) p.ifs++
                if (elseAt(src, i) && p.ifs > 0) p.ifs--
            }
            var end = i + 1
            while (end < src.length && isWordPart(src[end])) end++
            val word = src.substring(i, end)
            // `Row::class` names a class and declares none.
            val reference = src.startsWith("::", i - 2)
            returnedName(f, word, i, end)
            branchWord(f, word, i)
            val ctorOfClass = word == "constructor" && f.cls
            // In a function's expression an `object` or a `fun` without a name is a value, and the parameters are in sight in it.
            val unnamed = if (f.pending?.unended == true && f.modifiers and COMPANION == 0) UNNAMED[word]?.matchAt(src, end) else null
            // A receiver in brackets has a dot after it, the parameters of a function without a name none; after a receiver's
            // dot a name is a function's, and a bracket the parameters of one without a name.
            val after = if (word == "fun") unnamed?.let { src.substring(it.range.last, minOf(src.length, it.range.last + 2_000)) } else null
            val dot = after?.let { RECEIVER_DOT.matchAt(it, maxOf(0, closing(it, depths(it), 0))) }
            val value = unnamed != null && (dot == null || after.substring(dot.range.last + 1).trimStart().startsWith("("))
            if (word in DECLARATION_WORDS && !reference && !ctorOfClass && !value) {
                // A new declaration ends a function's expression body, a loop body without braces, or a class without one.
                endPending(f, i)
                f.cls = false
            }
            when {
                reference -> {}

                // A constructor is declared in a class's body, where a declaration may begin; anywhere else the word is a name.
                word == "fun" || (word == "constructor" && !ctorOfClass && f.kind == "class" && f.after == 0) -> {
                    f.fn = true
                    f.funAt = end
                    f.funModifiers = f.modifiers
                }

                word == "return" -> {
                    // A `return` is the innermost function's. Where its block may be another's, a function still being typed
                    // or a type the text does not read, it counts as the outer one's too, which can only make that less.
                    val blocks = stack.filter { it.function != null }
                    val into = blocks.drop(maxOf(0, blocks.indexOfLast { it.sure })).mapNotNull { it.function }
                    val marked = returnMarked(blocks.lastOrNull())
                    val value =
                        if (src.getOrNull(end) == '@' ||
                            into.isEmpty()
                        ) {
                            null
                        } else {
                            f.begin(Span(end, html = marked, returned = true))
                        }
                    for (g in into) if (value == null) g.body = null else g.returns.add(value)
                }

                // A getter takes nothing: `get("/")` after a value is a call. Only a property of a class or the top level has
                // one; after a local `get()` is a call too.
                word == "get" -> {
                    if (getterAt(f, i)) {
                        for (v in f.lastValues) v.getter = true
                        // A getter marked itself has its value, past `()`, marked: the property's mark reaches none of it.
                        if (f.modifiers and HTML != 0) f.begin(Span(GETTER_AFTER.matchAt(src, end)!!.range.last + 1, html = true))
                    }
                }

                word == "class" || word == "interface" || word == "object" -> {
                    f.cls = true
                    f.anonymous = word == "object" && f.modifiers and COMPANION == 0 && UNNAMED.getValue("object").matchAt(src, end) != null
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
                    property(f, word, end)
                }
            }
            // `open` in `door.open` is a name, and says nothing of what the next line declares.
            // After a value on its line, `b private const val`, it is an infix call's.
            val says =
                MODIFIER_WORDS[word]?.takeIf {
                    f.after == 0 ||
                        (
                            declaresAfter(end) && !afterValue(src, code, comments, i, f.modifiers != 0, annotationClosed)
                        )
                }
            if (says == null) f.reset()
            f.modifiers = f.modifiers or (says ?: 0)
            f.word = word
            i = end
            continue
        }
        // A name in backticks is a name whatever it holds, and `@` after a word or `super<A>` names a label.
        val quoted = if (c == '`') QUOTED.matchAt(src, i)?.also { f.reset() } else null
        val label = c != '@' || src.getOrNull(i - 1)?.let { isWordPart(it) || it == '>' } == true
        val annotated = if (label) i + (quoted?.value?.length ?: 0) else annotationEnd(src, code, types, i)
        if (annotated > i) {
            // What an annotation stands first in has begun: a loop's statement, a function's expression.
            if (f.pending?.fn == null) f.pending?.state = "stmt"
            if (f.pending?.state == "expr") f.pending?.started = true
            if (HTML_ANNOTATION.matchAt(src, i)?.range?.last == annotated - 1) f.modifiers = f.modifiers or HTML
            annotationClosed = annotated
            i = annotated
            continue
        }
        if (c == '(' || c == '[' || c == '{') {
            if (f.kind != "for") f.pending?.begun(brace = c == '{')
            val g = ScopeFrame(i, if (c == '{') "block" else "paren")
            g.outerElement = f.element ?: f.outerElement
            // A function type in the type parameters, `fun <T : (Int) -> Unit>`, is no parameter list.
            if (c == '(' && f.fn && i > typed && !receiverIn(i)) {
                g.kind = "params"
                // A function still being typed has no brackets, and the next ones are a statement's.
                val named = typeArgumentsAt(src, types, f.funAt, " \t")?.let { it.last + 1 } ?: f.funAt
                val head = if (i - f.funAt < FUN_HEAD_LENGTH && named <= i) FUN_HEAD.matchEntire(src.substring(named, i)) else null
                g.own = head != null && head.groupValues[1] !in STATEMENT_WORDS
                g.owner = f.takeIf { it.kind == "class" }
                g.local = f.kind != "class" && f.kind != "top"
                g.funModifiers = f.funModifiers
                g.name = f.word
                f.fn = false
            } else if (c == '(' && f.cls && f.ctor == null) {
                g.kind = "ctor"
            } else if (c == '(' && f.word == "catch") {
                // An infix function of that name is called with a lambda, or with brackets that name no variable.
                g.kind = "catch"
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
                // A catch has its block right after its brackets; one still being typed has none, and leaves the next block alone.
                val caught = f.caught?.takeIf { codeBefore(src, code, i) == it.end - 1 }
                f.caught = null
                if (caught != null) {
                    g.ends += caught
                } else if (p != null && p.state == "after" && f.kind != "for") {
                    // A lambda in a loop's own brackets is no body of it.
                    // The body of the function or the loop whose parameters just closed.
                    g.ends += p.decls
                    f.pending = null
                    g.function = p.fn?.also { it.body = Span(i + 1) }
                    g.sure =
                        p.fn?.let { it.own && i - it.closeAt < 1_000 && returnType(blank, types, it.closeAt, i) != null } ==
                        true
                } else if (f.cls) {
                    g.kind = "class"
                    g.member = !f.anonymous
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
                    g.lambda = !branch(src, code, f.open, i)
                    // Read in the code alone, as a function's parameters are: a comment among them is blank.
                    val m = lambdaParameters(blank, types, i + 1)
                    if (m != null && (i + 1 until m.end).all { code[it] || comments[it] }) {
                        g.kind = "lambda"
                        g.lambda = true
                        for (part in splitTopLevel(m.text, types, pastWhiteSpace(blank, i + 1))) {
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
            // An `init` block is a member, and a declaration may begin after it on its line.
            if (c == '{' && f.kind == "class" && f.word == "init") g.member = true
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
            // `fun a() {} open val b`: after a function's or a class's body a declaration may begin on the same line, and
            // after a lambda's the expression goes on, `b { } open value`.
            if (c == '}' && (g.function != null || g.member)) outer.after = 0
            g.function?.body?.end = i + 1
            for (d in g.ends) d.end = i + 1
            g.closeAt = i + 1
            val marked = HashSet<Any>()
            if (g.kind == "params") {
                val around = outer.pending?.takeIf { it.unended }?.decls ?: emptyList()
                handOver(PendingScope(parameters(g, i, marked = marked) + around, g))
                if (functions != null) declared.add(Triple(g, i, marked))
            } else if (g.kind == "ctor") {
                outer.ctor = parameters(g, i, !classHidden(outer), outer, marked)
            }
            // A default's literal is its parameter's, marked where the parameter is.
            var before = 0
            for (literal in g.literals) {
                while (before < g.commas.size && g.commas[before] < literal) before++
                if (before in marked) marks += literal
            }
            if (g.kind == "catch" && c == ')') {
                // The variable is all the brackets hold, `name: Type`, but for annotations before it; `object : Type` is a value.
                // It is read in their last stretch, so brackets in brackets cost no more than their length.
                val from = maxOf(g.open + 1, i - CATCH_VARIABLE_LENGTH)
                val tail = blank.substring(from, i)
                val colon = tail.lastIndexOf(':')
                val before = tail.substring(0, maxOf(colon, 0)).trimEnd()
                val name = before.takeLastWhile { isWordPart(it) }
                val type =
                    tail
                        .substring(colon + 1)
                        .trim()
                        .removeSuffix(",")
                        .trimEnd()
                var first = g.open + 1
                while (first < i && blank[first].isWhitespace()) first++
                val alone = first == from + before.length - name.length || blank[first] == '@'
                if (name.firstOrNull()?.isDigit() == false && name != "object" && alone && WHOLE_TYPE.matchEntire(type) != null) {
                    outer.caught = add(name, Declaration(g.open + 1, g.open, type).also { it.end = i + 1 })
                }
            }
            i++
            continue
        }
        if (c == ',') {
            if (i > typed) f.commas += i
            f.element = null
            f.branchOperand = false
            i++
            continue
        }
        // In a `when`'s braces an `else` on the next line is its own branch.
        val open = f.values.isNotEmpty() || f.element != null || f.branchOperand
        if (open && (c == ';' || (c == '\n' && breaks.ends(i, f.kind != "when")))) {
            f.endValues(i)
            f.branchOperand = false
            // An annotation's line ends no element, and a property's getter on the next line is in it.
            // Read once for each element, not again at each blank line after it.
            if (f.element == null) {
                f.elementEnded = false
            } else if (!f.elementEnded && breaks.before(i) + 1 != annotationClosed) {
                f.elementEnded = true
            }
        }
        val p = f.pending
        if (p != null) {
            when (p.state) {
                "after" -> {
                    if (c == '=' && src.getOrNull(i + 1) != '=' && p.fn != null) {
                        p.state = "expr"
                        p.fn?.body = f.begin(Span(i + 1, html = p.fn!!.funModifiers and HTML != 0))
                    } else if (p.fn == null && !c.isWhitespace() && f.kind != "for") {
                        p.state = "stmt"
                    }
                }

                "stmt" -> {
                    if (c == ';' || (c == '\n' && breaks.ends(i, elseGoesOn = p.ifs > 0))) endPending(f, i)
                }

                else -> {
                    if (c == ';' || (c == '\n' && p.started && breaks.ends(i))) {
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
    for ((g, close, marked) in declared) {
        val (name, f) = functionAt(blank, types, g, close, marked) ?: continue
        functions!!.getOrPut(name) { ArrayList() } += f
    }
    // A returned local's literals are marked by the declaration the name is of.
    markReturnedLocals(names, initialisers, setTo, returnedNames, marks)
    return names
}

/**
 * Marks the literals a local is set to that a marked `return` gives by name, once every name's scope is read,
 * `val s = "<b>"; return s`: the nearest declaration of the name decides, and it must be a local.
 */
private fun markReturnedLocals(
    names: Map<String, List<Declaration>>,
    initialisers: Map<Declaration, Span>,
    setTo: Map<Span, List<Int>>,
    returnedNames: List<Pair<String, Int>>,
    marks: MutableSet<Int>,
) {
    for ((name, at) in returnedNames) {
        val local = names[name]?.let { nearestDeclaration(it, at) }?.takeIf { it.start == it.at } ?: continue
        marks += initialisers[local]?.let { setTo[it] }.orEmpty()
    }
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
private val RETURN_TYPE = regex("""\s*(?::\s*($TYPE))?""")

/**
 * What stands between a function's parameters and its body, [from] to [to]: nothing, or `: Type` with its type arguments
 * as [types] reads them. Null where anything else stands, else the type, empty where none is written.
 */
private fun returnType(
    src: String,
    types: IntArray,
    from: Int,
    to: Int,
): String? {
    val m = RETURN_TYPE.matchAt(src, from)!!
    val type = RETURN_TYPE.groupRange(src, m, 1)
    // `: List<Int>?`: the type's arguments, then its question mark.
    var end = type?.let { typeArgumentsAt(src, types, it.last + 1)?.last?.plus(1) ?: (it.last + 1) } ?: (m.range.last + 1)
    if (type != null && end > type.last + 1 && src.getOrNull(end) == '?') end++
    return (type?.let { src.substring(it.first, end) } ?: "").takeIf { (end until to).all { src[it].isWhitespace() } }
}

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
    return nearestDeclaration(list, at)
}

/** The declaration in [list], sorted by where each is in sight, that is in sight at [at] and seen last before it: the nearest. */
private fun nearestDeclaration(
    list: List<Declaration>,
    at: Int,
): Declaration? {
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
