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
    /** `true` where the character is in a comment. */
    val comments: BooleanArray,
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

    /** The arguments of each annotation, `@Deprecated("<b>x</b>")`, from parenthesis to parenthesis: they say something of the code and hold no markup. */
    private val annotationArguments: List<IntRange> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ANNOTATION_OPEN
            .findAll(src)
            .filter { code[it.range.first] && code[it.range.last] }
            .mapNotNull { m ->
                var depth = 0
                for (k in m.range.last until src.length) {
                    if (!code[k]) continue
                    if (src[k] == '(') depth++
                    if (src[k] == ')' && --depth == 0) return@mapNotNull m.range.last..k
                }
                null
            }
            // An annotation in another's arguments lies in it: the ranges, merged, follow one another.
            .fold(ArrayList<IntRange>()) { merged, r ->
                val last = merged.lastOrNull()
                if (last != null && r.first <= last.last) {
                    merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
                } else {
                    merged += r
                }
                merged
            }
    }

    /** Whether [at] stands in an annotation's arguments. */
    fun inAnnotation(at: Int): Boolean {
        val k =
            annotationArguments.binarySearch {
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
     * tag or marks a value and stands in no call's arguments, where a Datastar expression or a log message would, or the
     * argument of core's `Trusted` that writes a tag.
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
        if ("Trusted" in src && !shadowsCore(src, "Trusted")) {
            for (site in findCallSites(src, setOf("Trusted"), lex)) {
                if (src.getOrNull(codeBefore(src, code, site.nameStart)) == '.') continue
                // Its argument, a literal or a sum of them: each that writes a tag, called in it by nothing else.
                val arg = site.named("html") ?: site.positional.firstOrNull() ?: continue
                val written = lex.stringsIn(arg.start, arg.end).filter { TAG.containsMatchIn(it.text) }
                out += outsideArguments(src, code, site.openParen, written)
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
    val (code, comments) = codeMask(src, l)
    val functions = HashMap<String, MutableList<FunctionDeclaration>>()
    return FileFacts(src, code, comments, declarations(src, code, comments, functions), functions).also { lastFacts = it }
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
            ?: codeMask(src, lexKotlin(src)).let { (code, comments) -> declarations(src, code, comments) }
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
        """\s*((?:\([^()]*\)|$NAME)(?:\s*:\s*[\p{L}\p{Nd}_.<>?`]+)?(?:\s*,\s*(?:\([^()]*\)|$NAME)(?:\s*:\s*[\p{L}\p{Nd}_.<>?`]+)?)*)\s*(?:,\s*)?->""",
    )

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
            src.getOrNull(k - 1) == '-' || !closesTypeArguments(src, code, k)
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

private val INFIX_OPERATOR = regex("""\s(?:and|or|xor|shl|shr|ushr)\s""")

private val DO_BEFORE = regex("""(?:^|[^\p{L}\p{Nd}_.])do(?:\s|\{)""")

private val CONDITION_HEAD = regex("""(?:^|[^\p{L}\p{Nd}_])(?:if|when|for|while|catch)\s*$""")

/** Whether the `>` at [k] closes type arguments a `<` opened before it on its line. */
private fun closesTypeArguments(
    src: String,
    code: BooleanArray,
    k: Int,
): Boolean {
    var depth = 0
    var j = k
    while (j >= 0 && src[j] != '\n') {
        // The `>` of an arrow, `() -> Int`, closes nothing.
        if (code[j] && src[j] == '>' && src.getOrNull(j - 1) != '-') depth++
        // `n<a && n>` compares: no type argument holds `&`, `|`, `=`, `+` or `%`.
        if (code[j] && depth > 0 && src[j] in "&|=+%") return false
        if (code[j] && depth > 0 && src[j] == '*' && src.getOrNull(j - 1) == ' ' && src.getOrNull(j + 1) == ' ') return false
        // `List<Int>`: a type's name, then a type at once; `b < c` and `n<3` compare.
        if (code[j] && src[j] == '<' &&
            --depth == 0
        ) {
            val first = src.getOrNull(j + 1)
            // `n<m and n>` compares: no type argument holds an infix operator.
            if (INFIX_OPERATOR.containsMatchIn(src.substring(j, k))) return false
            return isWordPart(src.getOrNull(j - 1)) && first != null && (first.isLetter() || first in "*(@?`_")
        }
        j--
    }
    return false
}

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

private val ELSE_NEXT = regex("""^else\b""")

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

    /** The values written in this frame whose statements have not ended. */
    val values = ArrayList<Span>()

    /** For a function's parameters: where its body is written, none once it returns to a label, and what each `return` of a block gives. */
    var body: Span? = null
    val returns = ArrayList<Span>()

    /** For a function's block: its parameters, and whether the block is surely that function's, with nothing but its type before it. */
    var function: ScopeFrame? = null
    var sure = false

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

internal fun isWordPart(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

/** Whether the character at [k], outside code, is a comment's and not a literal's, by what stands where code stopped. */
internal fun inComment(
    src: String,
    code: BooleanArray,
    k: Int,
): Boolean {
    var from = k
    while (from > 0 && !code[from - 1]) from--
    return src.startsWith("//", from) || src.startsWith("/*", from)
}

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

/** Whether the line break at [at] ends an expression: nothing before it or after it carries the expression on. */
internal fun lineEnds(
    src: String,
    code: BooleanArray,
    comments: BooleanArray,
    at: Int,
    elseGoesOn: Boolean = true,
): Boolean {
    var k = pastComments(src, comments, at)
    if (k >= 0 && code[k] && goesOn(src, code, comments, k)) return false
    val last = k
    k = at + 1
    // A comment at the next line's start is passed over; a literal there opens a line of its own.
    while (k < src.length) {
        if (src[k].isWhitespace()) {
            k++
        } else if (comments[k]) {
            while (k < src.length && comments[k]) k++
        } else {
            break
        }
    }
    val next = src.substring(k, minOf(src.length, k + 12))
    // An `else` on the next line is the statement's own only where it has an `if`; else an outer `if`'s.
    if (!elseGoesOn && ELSE_NEXT.containsMatchIn(next)) return true
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

/**
 * What stands between `fun` and a function's own brackets, on its line: type parameters, a receiver and the name, or
 * nothing. Brackets after anything else, or after a word that begins a statement, are not its parameters.
 */
private val FUN_HEAD = regex("""[ \t]*(?:<(?:[^<>\n]|<[^<>\n]*>)*>[ \t]*)?(?:[\p{L}_`][^\s()]*\.[ \t]*)?(?:($NAME)|`[^`\n]+`)?[ \t]*""")
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
private val MODIFIER_WORDS: Map<String, Int> =
    "public protected internal final lateinit inline suspend tailrec operator infix external data enum annotation inner value actual expect"
        .split(" ")
        .associateWith { 0 } + listOf("open", "abstract", "sealed").associateWith { EXTENDED } +
        mapOf("private" to PRIVATE, "const" to CONST, "override" to OVERRIDES, "companion" to COMPANION)

private val QUOTED = regex("`[^`\n]+`")

// A part of an annotation's name may be in backticks, `` @`A` `` or `` @a.`b` ``.
private const val ANNOTATION_PART = """(?:[\p{L}_][\p{L}\p{Nd}_]*|`[^`\n]+`)"""
private val ANNOTATION = regex("""@(?:[a-z]+:)?(?:\[|$ANNOTATION_PART(?:\.$ANNOTATION_PART)*\.?)""")

/** What stands between a modifier and the declaration on its line: words and annotations, twelve at most, so that no line is too long to read. */
private val DECLARES_AFTER =
    regex(
        """(?:[ \t]+(?:[a-z]+|@[^\s(<]+(?:[ \t]*<(?:[^<>\n]|->|<(?:[^<>\n]|->|<(?:[^<>\n]|->)*>)*>)*>)?(?:\([^()\n]*\))?)){0,12}""" +
            """[ \t]+(?:va[lr]|fun|class|interface|object)\b""",
    )

/** Just past the annotation written at [at], its type arguments and its arguments too where they close, or [at] where none is. */
private fun annotationEnd(
    src: String,
    code: BooleanArray,
    at: Int,
): Int {
    val named = (ANNOTATION.matchAt(src, at) ?: return at).range.last + 1
    val name = typeArgumentsEnd(src, code, named) ?: named
    // `@[A B]` holds its annotations in the bracket.
    val open = if (src[name - 1] == '[') name - 1 else name
    val depth = IntArray(3)
    for (k in open until if (src.getOrNull(open) == '(' || open < name) minOf(src.length, open + 5_000) else open) {
        if (code[k] && src[k] in "([{") depth["([{".indexOf(src[k])]++
        val closes = if (code[k]) ")]}".indexOf(src[k]) else -1
        // A bracket that closes none of its kind is past an annotation left open.
        if (closes >= 0 && --depth[closes] < 0) break
        if (closes >= 0 && depth.sum() == 0) return k + 1
        // No argument of an annotation holds a property: one written there begins past an annotation left open.
        if (code[k] && VAL_OR_VAR.matchAt(src, k) != null && !isWordPart(src.getOrNull(k - 1))) return k
    }
    return open
}

private val VAL_OR_VAR = regex("""va[lr](?![\p{L}\p{Nd}_])""")

/**
 * Just past the type arguments of an annotation's name that ends at [at], `<B<C>>` or `<(Int) -> Unit>`, with blanks before
 * them on its line; null where none close.
 */
private fun typeArgumentsEnd(
    src: String,
    code: BooleanArray,
    at: Int,
): Int? {
    var k = at
    while (k < src.length && (src[k] == ' ' || src[k] == '\t')) k++
    if (src.getOrNull(k) != '<') return null
    var depth = 0
    for (j in k until minOf(src.length, k + 5_000)) {
        if (!code[j]) continue
        when {
            src[j] == '<' -> depth++

            // The arrow of a function type closes nothing.
            src[j] == '>' && src[j - 1] != '-' -> if (--depth == 0) return j + 1

            src[j] in ";{}=" -> return null
        }
    }
    return null
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

/** An annotation's name and the parenthesis that opens its arguments; `this@Page.f(` and `return@forEach(` name a label. */
private val ANNOTATION_OPEN = regex("""(?<![\p{L}\p{Nd}_>.`])@(?:[a-z]+:)?[\p{L}_][\p{L}\p{Nd}_.]*\s*\(""")

/** A tag, an end tag, a comment or a doctype, anywhere in a literal: `a < b` and `<3` are none. */
private val TAG = regex("""<[A-Za-z/!]""")

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

private val BRANCH_WORD = regex("""(?:^|[^\p{L}\p{Nd}_])(?:else|try|finally|do|when)\s*$""")
private val BRANCH_HEAD = regex("""(?:^|[^\p{L}\p{Nd}_])(?:if|when|for|while|catch)\s*$""")

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
    if (src[k] == '>' && src.getOrNull(k - 1) == '-') return true
    if (src[k] == ')') {
        var depth = 0
        while (k > open) {
            if (code[k] && src[k] == ')') depth++
            if (code[k] && src[k] == '(' && --depth == 0) break
            k--
        }
        return BRANCH_HEAD.containsMatchIn(src.substring(maxOf(open, k - 20), k))
    }
    return BRANCH_WORD.containsMatchIn(src.substring(maxOf(open, k - 20), k + 1))
}

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

    /** Whether the line that ends at the line break [at] carries on: its last code, past comments, wants what follows. */
    fun goesOnAt(at: Int): Boolean {
        var k = at - 1
        while (k >= 0 && (src[k].isWhitespace() || comments[k])) k--
        return k >= 0 && code[k] && goesOn(src, code, comments, k)
    }

    /** Whether the bracket at [open] holds a receiver's type, `fun ((Int) -> Unit).call()`: a dot follows where it closes. */
    fun receiverIn(open: Int): Boolean {
        var depth = 0
        for (k in open until minOf(src.length, open + 2_000)) {
            if (!code[k]) continue
            if (src[k] == '(') depth++
            if (src[k] == ')' && --depth == 0) return RECEIVER_DOT.matchAt(blank, k + 1) != null
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
            val end = if (src[i] == '@' && code[i]) minOf(annotationEnd(src, code, i), to) else i
            if (end > i) {
                for (j in i until end) if (out[j - from] != '\n') out[j - from] = ' '
                i = end
            } else {
                i++
            }
        }
        return out.concatToString()
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
            val m = PARAMETER.find(withoutAnnotations(cuts[k] + 1, cuts[k + 1])) ?: continue
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

    // Where the last annotation read ends: a modifier after its arguments is a declaration's.
    var annotationClosed = -1

    var i = 0
    while (i < src.length) {
        val f = stack.last()
        // A line that carries on, `a +` or `else`, gives its next line's first word to its expression, not to a declaration.
        if (src[i] == '\n' && f.after == 1 && !goesOnAt(i)) f.after = 0
        if (!code[i]) {
            // A literal is a value, and what stands before it is written before no declaration; a comment is passed over.
            if ((i == 0 || code[i - 1]) && !src.startsWith("//", i) && !src.startsWith("/*", i)) {
                f.reset()
                if (f.kind != "for") f.pending?.begun(brace = false)
            }
            i++
            continue
        }
        val c = src[i]
        if ((c.isLetter() || c == '_') && !isWordPart(src.getOrNull(i - 1))) {
            // A loop's own brackets hold its variable, not its statement.
            if (f.kind != "for") f.pending?.begun(brace = false)
            f.pending?.takeIf { it.state == "stmt" }?.let { p ->
                if (src.startsWith("if", i) && !isWordPart(src.getOrNull(i + 2))) p.ifs++
                if (src.startsWith("else", i) && !isWordPart(src.getOrNull(i + 4)) && p.ifs > 0) p.ifs--
            }
            var end = i + 1
            while (end < src.length && isWordPart(src[end])) end++
            val word = src.substring(i, end)
            // `Row::class` names a class and declares none.
            val reference = src.startsWith("::", i - 2)
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
                    property(f, word, end)
                }
            }
            // `open` in `door.open` is a name, and says nothing of what the next line declares.
            // After a value on its line, `b private const val`, it is an infix call's.
            val says =
                MODIFIER_WORDS[word]?.takeIf {
                    f.after == 0 ||
                        (
                            DECLARES_AFTER.matchAt(
                                src,
                                end,
                            ) != null && !afterValue(src, code, comments, i, f.modifiers != 0, annotationClosed)
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
        val annotated = if (label) i + (quoted?.value?.length ?: 0) else annotationEnd(src, code, i)
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
            if (c == '(' && f.fn && !receiverIn(i)) {
                g.kind = "params"
                // A function still being typed has no brackets, and the next ones are a statement's.
                val head = if (i - f.funAt < FUN_HEAD_LENGTH) FUN_HEAD.matchEntire(src.substring(f.funAt, i)) else null
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
                        p.fn?.let { it.own && i - it.closeAt < 1_000 && RETURN_TYPE.matchEntire(blank.substring(it.closeAt, i)) != null } ==
                        true
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
                    // Read in the code alone, as a function's parameters are: a comment among them is blank.
                    val m = LAMBDA_PARAMETERS.matchAt(blank, i + 1)
                    if (m != null && (i + 1 until i + 1 + m.value.length).all { code[it] || comments[it] }) {
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
            // `fun a() {} open val b`: after a function's or a class's body a declaration may begin on the same line, and
            // after a lambda's the expression goes on, `b { } open value`.
            if (c == '}' && (g.function != null || g.kind == "class")) outer.after = 0
            g.function?.body?.end = i + 1
            for (d in g.ends) d.end = i + 1
            g.closeAt = i + 1
            if (g.kind == "params") {
                val around = outer.pending?.takeIf { it.unended }?.decls ?: emptyList()
                handOver(PendingScope(parameters(g, i) + around, g))
                if (functions != null) declared.add(g to i)
            } else if (g.kind == "ctor") {
                outer.ctor = parameters(g, i, !classHidden(outer), outer)
            } else if (g.kind == "catch" && c == ')') {
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
            f.commas += i
            i++
            continue
        }
        if (f.values.isNotEmpty() && (c == ';' || (c == '\n' && lineEnds(src, code, comments, i)))) f.endValues(i)
        val p = f.pending
        if (p != null) {
            when (p.state) {
                "after" -> {
                    if (c == '=' && src.getOrNull(i + 1) != '=' && p.fn != null) {
                        p.state = "expr"
                        p.fn?.body = f.begin(Span(i + 1))
                    } else if (p.fn == null && !c.isWhitespace() && f.kind != "for") {
                        p.state = "stmt"
                    }
                }

                "stmt" -> {
                    if (c == ';' || (c == '\n' && lineEnds(src, code, comments, i, elseGoesOn = p.ifs > 0))) endPending(f, i)
                }

                else -> {
                    if (c == ';' || (c == '\n' && p.started && lineEnds(src, code, comments, i))) {
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
