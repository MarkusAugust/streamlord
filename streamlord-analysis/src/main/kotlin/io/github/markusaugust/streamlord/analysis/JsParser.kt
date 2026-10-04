package io.github.markusaugust.streamlord.analysis

/**
 * A syntax check for JavaScript, enough to judge a Datastar expression. It follows acorn step by
 * step (script mode, the latest ECMAScript, `return` and `await` allowed at the top level, as in
 * an event handler), so that the VS Code extension (which uses acorn) and the IntelliJ plugin
 * agree on what parses and on where an error sits. It keeps only as much of a tree as the early
 * errors need: which expressions can be assigned to, which names a pattern binds.
 *
 * A few messages are friendlier than acorn's ("Unexpected end of input"); the verdict is the same.
 */
public class JsSyntaxError(
    message: String,
    /** Offset of the offending token. */
    public val pos: Int,
    /** Offset just past it. */
    public val raisedAt: Int,
) : RuntimeException(message)

public object JsParser {
    /** Parse [source] as a script; throws [JsSyntaxError] on the first problem. */
    public fun check(source: String) {
        try {
            Parser(source).parseProgram()
        } catch (_: StackOverflowError) {
            throw JsSyntaxError("Not enough stack space to parse input", 0, source.length)
        }
    }

    /** The error for [source], or null when it parses. */
    public fun error(source: String): JsSyntaxError? =
        try {
            check(source)
            null
        } catch (e: JsSyntaxError) {
            e
        }
}

private enum class T { NAME, KEYWORD, NUM, STRING, TEMPLATE, REGEX, PUNCT, PRIVATE_NAME, EOF }

private class JsToken(
    val type: T,
    /** The word, the operator, or the cooked text of a string. */
    val value: String,
    val start: Int,
    val end: Int,
    val newlineBefore: Boolean,
    /** Whether a word was written with a `\u` escape; such a word is never a contextual keyword. */
    val escaped: Boolean = false,
)

private val KEYWORDS =
    setOf(
        "break",
        "case",
        "catch",
        "class",
        "const",
        "continue",
        "debugger",
        "default",
        "delete",
        "do",
        "else",
        "export",
        "extends",
        "finally",
        "for",
        "function",
        "if",
        "import",
        "in",
        "instanceof",
        "new",
        "return",
        "super",
        "switch",
        "this",
        "throw",
        "try",
        "typeof",
        "var",
        "void",
        "while",
        "with",
        "null",
        "true",
        "false",
    )

/** Names that strict code (a class body, or code under `"use strict"`) may not use as identifiers. */
private val STRICT_RESERVED = setOf("implements", "interface", "let", "package", "private", "protected", "public", "static", "yield")

private val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "&=", "|=", "^=", "&&=", "||=", "??=")

private val PREFIX_OPS = setOf("!", "~", "+", "-", "++", "--")

private val PREFIX_KEYWORDS = setOf("typeof", "void", "delete")

/** Keywords that can begin an expression; `yield` takes an operand only before one of these. */
private val EXPRESSION_KEYWORDS =
    setOf("function", "new", "this", "super", "class", "import", "null", "true", "false", "typeof", "void", "delete")

/** The bases of `0x`, `0o` and `0b` numbers, in that order. */
private val RADIXES = intArrayOf(16, 8, 2)

private val EXPRESSION_PUNCTUATION = setOf("[", "{", "(", "++", "--", "!", "~", "+", "-", "/", "/=")

private const val SCOPE_TOP = 1
private const val SCOPE_FUNCTION = 2
private const val SCOPE_ASYNC = 4
private const val SCOPE_GENERATOR = 8
private const val SCOPE_ARROW = 16
private const val SCOPE_SIMPLE_CATCH = 32
private const val SCOPE_SUPER = 64
private const val SCOPE_DIRECT_SUPER = 128
private const val SCOPE_CLASS_STATIC_BLOCK = 256
private const val SCOPE_CLASS_FIELD_INIT = 512
private const val SCOPE_SWITCH = 1024
private const val SCOPE_VAR = SCOPE_TOP or SCOPE_FUNCTION or SCOPE_CLASS_STATIC_BLOCK

private const val FUNC_STATEMENT = 1
private const val FUNC_HANGING_STATEMENT = 2

/** How an expression is being parsed inside a `for (` head: `in` is not an operator there. */
private const val FOR_INIT = 1
private const val FOR_AWAIT_INIT = 2

private enum class Bind { NONE, VAR, LEXICAL, FUNCTION, SIMPLE_CATCH, OUTSIDE }

private class Scope(
    val flags: Int,
) {
    val vars = HashSet<String>()
    val lexical = ArrayList<String>()
    val functions = HashSet<String>()
}

private class Label(
    val name: String?,
    var kind: String?,
    var statementStart: Int = -1,
)

private class PrivateNames {
    val declared = HashMap<String, String>()
    val used = ArrayList<Pair<String, Int>>()
}

/** Errors that only count once it is known whether an expression is a pattern or a value. */
private class DestructuringErrors {
    var shorthandAssign = -1
    var trailingComma = -1
    var parenthesizedAssign = -1
    var parenthesizedBind = -1
    var doubleProto = -1
}

private enum class N {
    IDENT,
    MEMBER,
    CHAIN,
    SUPER,
    PRIVATE_NAME,
    ARROW,
    ARRAY,
    OBJECT,
    PROPERTY,
    SPREAD,
    ASSIGN,
    ARRAY_PATTERN,
    OBJECT_PATTERN,
    ASSIGN_PATTERN,
    REST,
    OTHER,
}

private class Key(
    /** The identifier or string that names the member; null for a number or a computed key. */
    val name: String?,
    val isIdent: Boolean,
    val computed: Boolean,
    val isPrivate: Boolean,
    val start: Int,
)

private class Node(
    var type: N,
    val start: Int,
) {
    var end = start

    /** An identifier's name, an assignment's operator, or a property's kind (`init`, `get`, `set`). */
    var name = ""

    /** A spread's argument, a property's value, an assignment's target, a chain's expression. */
    var inner: Node? = null
    var items: List<Node?> = emptyList()
    var optional = false
    var privateProperty = false
    var key: Key? = null

    /** A property written `key: value`, the only kind that can redefine `__proto__`. */
    var plain = false
}

private class ClassMember(
    val kind: String,
    val isMethod: Boolean,
    val isStatic: Boolean,
    val key: Key,
    val start: Int,
)

private class VarDeclaration(
    val kind: String,
    val start: Int,
) {
    var count = 0
    var firstHasInit = false
    var firstIsIdent = false
}

/** Thrown out of an escape sequence that a tagged template may contain and an untagged one may not. */
private class InvalidTemplateEscape : RuntimeException(null, null, false, false)

private class Parser(
    private val src: String,
) {
    private var pos = 0
    private var tok: JsToken = JsToken(T.EOF, "", 0, 0, false)
    private var lastTokStart = 0
    private var lastTokEnd = 0
    private var containsEsc = false
    private var inTemplateElement = false
    private var strict = false

    /** Where an arrow function's parameters may begin, so `(a, b)` and `a` are tried as such only there. */
    private var potentialArrowAt = -1
    private var potentialArrowInForAwait = false

    /** Offsets of a `yield`, an `await` and a name `await` seen while what may be parameters is parsed. */
    private var yieldPos = 0
    private var awaitPos = 0
    private var awaitIdentPos = 0
    private var labels = ArrayList<Label>()
    private val scopeStack = ArrayList<Scope>()
    private val privateNameStack = ArrayList<PrivateNames>()

    fun parseProgram() {
        strict = strictDirective(0)
        enterScope(SCOPE_TOP)
        tok = readToken()
        while (tok.type != T.EOF) parseStatement(null, topLevel = true)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun raise(
        at: Int,
        message: String,
    ): Nothing = throw JsSyntaxError(message, at, pos)

    private fun unexpected(at: Int = tok.start): Nothing {
        if (at == tok.start) {
            if (tok.type == T.EOF) raise(at, "Unexpected end of input")
            if (tok.type == T.KEYWORD) raise(at, "Unexpected keyword '${tok.value}'")
        }
        raise(at, "Unexpected token")
    }

    private fun isPunct(value: String): Boolean = tok.type == T.PUNCT && tok.value == value

    private fun isKeyword(value: String): Boolean = tok.type == T.KEYWORD && tok.value == value

    /** A contextual keyword such as `of`, `async` or `get`: a plain name, written without escapes. */
    private fun isName(value: String): Boolean = tok.type == T.NAME && tok.value == value && !tok.escaped

    private fun eat(value: String): Boolean {
        if (isPunct(value)) {
            next()
            return true
        }
        return false
    }

    private fun eatKeyword(value: String): Boolean {
        if (isKeyword(value)) {
            next()
            return true
        }
        return false
    }

    private fun eatName(value: String): Boolean {
        if (isName(value)) {
            next()
            return true
        }
        return false
    }

    private fun expect(value: String) {
        if (!eat(value)) unexpected()
    }

    private fun canInsertSemicolon(): Boolean = tok.type == T.EOF || isPunct("}") || tok.newlineBefore

    private fun semicolon() {
        if (!eat(";") && !canInsertSemicolon()) unexpected()
    }

    private fun hasLineBreak(
        from: Int,
        to: Int,
    ): Boolean {
        for (i in from until minOf(to, src.length)) if (isNewLine(src[i])) return true
        return false
    }

    private fun codePointAt(i: Int): Int = if (i < src.length) src.codePointAt(i) else -1

    /** The offset after any white space and comments that start at [from], without reading tokens. */
    private fun skipBlank(from: Int): Int {
        var i = from
        while (i < src.length) {
            val c = src[i]
            if (isJsSpace(c)) {
                i++
            } else if (c == '/' && src.getOrNull(i + 1) == '/') {
                i += 2
                while (i < src.length && !isNewLine(src[i])) i++
            } else if (c == '/' && src.getOrNull(i + 1) == '*') {
                val close = src.indexOf("*/", i + 2)
                if (close < 0) return i
                i = close + 2
            } else {
                return i
            }
        }
        return i
    }

    /** Whether the statements from [from] open with a `"use strict"` directive. */
    private fun strictDirective(from: Int): Boolean {
        var start = from
        while (true) {
            start = skipBlank(start)
            val quote = src.getOrNull(start)
            if (quote != '\'' && quote != '"') return false
            var i = start + 1
            while (i < src.length && src[i] != quote) i += if (src[i] == '\\') 2 else 1
            if (i >= src.length) return false
            val end = i + 1
            if (src.substring(start + 1, i) == "use strict") {
                val after = skipBlank(end)
                val next = src.getOrNull(after)
                if (next == ';' || next == '}') return true
                val continues = next != null && (next in "(`.[+-/*%<>=,?^&" || (next == '!' && src.getOrNull(after + 1) == '='))
                return hasLineBreak(end, after) && !continues
            }
            start = skipBlank(end)
            if (src.getOrNull(start) == ';') start++
        }
    }

    private fun checkPatternErrors(
        ref: DestructuringErrors?,
        isAssign: Boolean,
    ) {
        if (ref == null) return
        if (ref.trailingComma > -1) raise(ref.trailingComma, "Comma is not permitted after the rest element")
        val parens = if (isAssign) ref.parenthesizedAssign else ref.parenthesizedBind
        if (parens > -1) raise(parens, if (isAssign) "Assigning to rvalue" else "Parenthesized pattern")
    }

    private fun checkExpressionErrors(
        ref: DestructuringErrors?,
        andThrow: Boolean = false,
    ): Boolean {
        if (ref == null) return false
        if (!andThrow) return ref.shorthandAssign >= 0 || ref.doubleProto >= 0
        if (ref.shorthandAssign >= 0) {
            raise(ref.shorthandAssign, "Shorthand property assignments are valid only in destructuring patterns")
        }
        if (ref.doubleProto >= 0) raise(ref.doubleProto, "Redefinition of __proto__ property")
        return false
    }

    private fun checkYieldAwaitInDefaultParams() {
        if (yieldPos != 0 && (awaitPos == 0 || yieldPos < awaitPos)) raise(yieldPos, "Yield expression cannot be a default value")
        if (awaitPos != 0) raise(awaitPos, "Await expression cannot be a default value")
    }

    // ---- scopes --------------------------------------------------------------------------------

    private fun enterScope(flags: Int) {
        scopeStack += Scope(flags)
    }

    private fun exitScope() {
        scopeStack.removeAt(scopeStack.lastIndex)
    }

    private fun currentScope(): Scope = scopeStack[scopeStack.lastIndex]

    private fun currentVarScope(): Scope =
        scopeStack.last { it.flags and (SCOPE_VAR or SCOPE_CLASS_FIELD_INIT or SCOPE_CLASS_STATIC_BLOCK) != 0 }

    private fun currentThisScope(): Scope =
        scopeStack.last {
            it.flags and (SCOPE_VAR or SCOPE_CLASS_FIELD_INIT or SCOPE_CLASS_STATIC_BLOCK) != 0 && it.flags and SCOPE_ARROW == 0
        }

    private fun inFunction(): Boolean = currentVarScope().flags and SCOPE_FUNCTION != 0

    private fun inGenerator(): Boolean = currentVarScope().flags and SCOPE_GENERATOR != 0

    private fun inAsync(): Boolean = currentVarScope().flags and SCOPE_ASYNC != 0

    private fun inClassStaticBlock(): Boolean = currentVarScope().flags and SCOPE_CLASS_STATIC_BLOCK != 0

    private fun canAwait(): Boolean {
        for (i in scopeStack.indices.reversed()) {
            val flags = scopeStack[i].flags
            if (flags and (SCOPE_CLASS_STATIC_BLOCK or SCOPE_CLASS_FIELD_INIT) != 0) return false
            if (flags and SCOPE_FUNCTION != 0) return flags and SCOPE_ASYNC != 0
        }
        return true
    }

    private fun allowNewDotTarget(): Boolean =
        scopeStack.any {
            it.flags and (SCOPE_CLASS_STATIC_BLOCK or SCOPE_CLASS_FIELD_INIT) != 0 ||
                (it.flags and SCOPE_FUNCTION != 0 && it.flags and SCOPE_ARROW == 0)
        }

    private fun treatFunctionsAsVar(scope: Scope): Boolean = scope.flags and (SCOPE_FUNCTION or SCOPE_TOP) != 0

    private fun functionFlags(
        async: Boolean,
        generator: Boolean,
    ): Int = SCOPE_FUNCTION or (if (async) SCOPE_ASYNC else 0) or (if (generator) SCOPE_GENERATOR else 0)

    private fun declareName(
        name: String,
        type: Bind,
        at: Int,
    ) {
        var redeclared = false
        val scope = currentScope()
        when (type) {
            Bind.LEXICAL -> {
                redeclared = name in scope.lexical || name in scope.functions || name in scope.vars
                scope.lexical += name
            }

            Bind.SIMPLE_CATCH -> {
                scope.lexical += name
            }

            Bind.FUNCTION -> {
                redeclared = name in scope.lexical || (!treatFunctionsAsVar(scope) && name in scope.vars)
                scope.functions += name
            }

            else -> {
                for (i in scopeStack.indices.reversed()) {
                    val s = scopeStack[i]
                    val catchParameter = s.flags and SCOPE_SIMPLE_CATCH != 0 && s.lexical[0] == name
                    if ((name in s.lexical && !catchParameter) || (!treatFunctionsAsVar(s) && name in s.functions)) {
                        redeclared = true
                        break
                    }
                    s.vars += name
                    if (s.flags and SCOPE_VAR != 0) break
                }
            }
        }
        if (redeclared) raise(at, "Identifier '$name' has already been declared")
    }

    // ---- statements ----------------------------------------------------------------------------

    private fun isLet(context: String?): Boolean {
        if (!isName("let")) return false
        var next = skipBlank(pos)
        var ch = codePointAt(next)
        if (ch == '['.code || ch == '\\'.code) return true
        if (context != null) return false
        if (ch == '{'.code) return true
        if (isIdStart(ch)) {
            val start = next
            do {
                next += Character.charCount(ch)
                ch = codePointAt(next)
            } while (isIdChar(ch))
            if (ch == '\\'.code) return true
            val word = src.substring(start, next)
            if (word != "in" && word != "instanceof") return true
        }
        return false
    }

    /** `async function` on one line. */
    private fun isAsyncFunction(): Boolean {
        if (!isName("async")) return false
        val next = skipBlank(pos)
        if (hasLineBreak(pos, next) || !src.startsWith("function", next)) return false
        val after = codePointAt(next + 8)
        return !(isIdChar(after) || after == '\\'.code)
    }

    private fun isUsingKeyword(
        isAwaitUsing: Boolean,
        isFor: Boolean,
    ): Boolean {
        if (!isName(if (isAwaitUsing) "await" else "using")) return false
        var next = skipBlank(pos)
        if (hasLineBreak(pos, next)) return false
        if (isAwaitUsing) {
            val usingEnd = next + 5
            if (!src.startsWith("using", next) || usingEnd == src.length) return false
            val after = codePointAt(usingEnd)
            if (isIdChar(after) || after == '\\'.code) return false
            next = skipBlank(usingEnd)
            if (hasLineBreak(usingEnd, next)) return false
        }
        var ch = codePointAt(next)
        if (!isIdStart(ch) && ch != '\\'.code) return false
        val start = next
        do {
            next += Character.charCount(ch)
            ch = codePointAt(next)
        } while (isIdChar(ch))
        if (ch == '\\'.code) return true
        val word = src.substring(start, next)
        if (word == "in" || word == "instanceof") return false
        if (isFor && !isAwaitUsing && word == "of") {
            // `for (using of = ...)` declares a variable named `of`; `for (using of x)` iterates.
            next = skipBlank(next)
            val following = src.getOrNull(next + 1)
            if (src.getOrNull(next) != '=' || following == '=' || following == '>') return false
        }
        return true
    }

    /** `using` or `await using`, when a declaration of that kind starts here. */
    private fun usingKind(isFor: Boolean): String? =
        when {
            isUsingKeyword(false, isFor) -> "using"
            isUsingKeyword(true, isFor) -> "await using"
            else -> null
        }

    private fun parseStatement(
        context: String?,
        topLevel: Boolean = false,
    ) {
        val start = tok.start
        if (isLet(context)) {
            parseVarStatement(context, "let", start)
            return
        }
        when (if (tok.type == T.KEYWORD) tok.value else "") {
            "break", "continue" -> {
                parseBreakContinue(start, tok.value)
            }

            "debugger" -> {
                next()
                semicolon()
            }

            "do" -> {
                next()
                labels += Label(null, "loop")
                parseStatement("do")
                labels.removeAt(labels.lastIndex)
                if (!eatKeyword("while")) unexpected()
                parseParenExpression()
                eat(";")
            }

            "for" -> {
                parseFor()
            }

            "function" -> {
                // A function may be the whole body of an `if` or of a label, in sloppy code only.
                if (context != null && (strict || (context != "if" && context != "label"))) unexpected()
                next()
                parseFunction(start, FUNC_STATEMENT or (if (context == null) 0 else FUNC_HANGING_STATEMENT), isAsync = false)
            }

            "class" -> {
                if (context != null) unexpected()
                parseClass(isStatement = true)
            }

            "if" -> {
                next()
                parseParenExpression()
                parseStatement("if")
                if (eatKeyword("else")) parseStatement("if")
            }

            "return" -> {
                if (!inFunction() && currentVarScope().flags and SCOPE_TOP == 0) raise(start, "'return' outside of function")
                next()
                if (!eat(";") && !canInsertSemicolon()) {
                    parseExpression()
                    semicolon()
                }
            }

            "switch" -> {
                parseSwitch()
            }

            "throw" -> {
                next()
                if (tok.newlineBefore) raise(lastTokEnd, "Illegal newline after throw")
                parseExpression()
                semicolon()
            }

            "try" -> {
                parseTry(start)
            }

            "const", "var" -> {
                parseVarStatement(context, tok.value, start)
            }

            "while" -> {
                next()
                parseParenExpression()
                labels += Label(null, "loop")
                parseStatement("while")
                labels.removeAt(labels.lastIndex)
            }

            "with" -> {
                if (strict) raise(start, "'with' in strict mode")
                next()
                parseParenExpression()
                parseStatement("with")
            }

            "import", "export" -> {
                // `import(...)` and `import.meta` are expressions; anything else is a module declaration.
                val following = src.getOrNull(skipBlank(pos))
                if (tok.value == "import" && (following == '(' || following == '.')) {
                    parseExpression()
                    semicolon()
                } else {
                    if (!topLevel) raise(start, "'import' and 'export' may only appear at the top level")
                    raise(start, "'${tok.value}' is not allowed in a Datastar expression")
                }
            }

            else -> {
                parseOtherStatement(context, start)
            }
        }
    }

    private fun parseOtherStatement(
        context: String?,
        start: Int,
    ) {
        if (isPunct("{")) {
            parseBlock()
            return
        }
        if (eat(";")) return
        if (isAsyncFunction()) {
            if (context != null) unexpected()
            next()
            next()
            parseFunction(start, FUNC_STATEMENT, isAsync = true)
            return
        }
        val usingKind = usingKind(isFor = false)
        if (usingKind != null) {
            val flags = currentScope().flags
            if (flags and (SCOPE_SWITCH or SCOPE_TOP) != 0) {
                raise(start, "Using declaration cannot appear in the top level when source type is `script` or in the bare case statement")
            }
            if (context != null) raise(start, "Using declaration is not allowed in single-statement positions")
            if (usingKind == "await using") {
                if (!canAwait()) raise(start, "Await using cannot appear outside of async function")
                next()
            }
            next()
            parseVar(false, usingKind, start)
            semicolon()
            return
        }
        val maybeName = tok.value
        val startsWithName = tok.type == T.NAME
        val expr = parseExpression()
        if (startsWithName && expr.type == N.IDENT && eat(":")) {
            parseLabeledStatement(start, maybeName, expr, context)
        } else {
            semicolon()
        }
    }

    private fun parseVarStatement(
        context: String?,
        kind: String,
        start: Int,
    ) {
        if (context != null && kind != "var") unexpected()
        next()
        parseVar(false, kind, start)
        semicolon()
    }

    private fun parseBreakContinue(
        start: Int,
        keyword: String,
    ) {
        val isBreak = keyword == "break"
        next()
        var label: String? = null
        if (!eat(";") && !canInsertSemicolon()) {
            if (tok.type != T.NAME) unexpected()
            label = parseIdent().name
            semicolon()
        }
        // There must be something to break out of or to continue: a loop, a switch or the named label.
        val found =
            labels.any {
                (label == null || it.name == label) &&
                    ((it.kind != null && (isBreak || it.kind == "loop")) || (label != null && isBreak))
            }
        if (!found) raise(start, "Unsyntactic $keyword")
    }

    private fun parseLabeledStatement(
        start: Int,
        name: String,
        expr: Node,
        context: String?,
    ) {
        if (labels.any { it.name == name }) raise(expr.start, "Label '$name' is already declared")
        val kind =
            when {
                isKeyword("for") || isKeyword("while") || isKeyword("do") -> "loop"
                isKeyword("switch") -> "switch"
                else -> null
            }
        // Labels stacked on one statement all name it: `a: b: for (;;) continue a`.
        for (i in labels.indices.reversed()) {
            val label = labels[i]
            if (label.statementStart != start) break
            label.statementStart = tok.start
            label.kind = kind
        }
        labels += Label(name, kind, tok.start)
        val inner =
            when {
                context == null -> "label"
                "label" in context -> context
                else -> context + "label"
            }
        parseStatement(inner)
        labels.removeAt(labels.lastIndex)
    }

    private fun parseBlock(
        createScope: Boolean = true,
        exitStrict: Boolean = false,
    ) {
        expect("{")
        if (createScope) enterScope(0)
        while (!isPunct("}")) parseStatement(null)
        // Before the brace is consumed, so that the token after it is read as sloppy code again.
        if (exitStrict) strict = false
        next()
        if (createScope) exitScope()
    }

    private fun parseParenExpression() {
        expect("(")
        parseExpression()
        expect(")")
    }

    private fun parseFor() {
        next()
        val awaitAt = if (canAwait() && eatName("await")) lastTokStart else -1
        labels += Label(null, "loop")
        enterScope(0)
        expect("(")
        if (isPunct(";")) {
            if (awaitAt > -1) unexpected(awaitAt)
            parseForRest()
            return
        }
        val isLet = isLet(null)
        if (isKeyword("var") || isKeyword("const") || isLet) {
            val kind = if (isLet) "let" else tok.value
            val start = tok.start
            next()
            parseForAfterInit(parseVar(true, kind, start), awaitAt)
            return
        }
        val startsWithLet = isName("let")
        val usingKind = usingKind(isFor = true)
        if (usingKind != null) {
            val start = tok.start
            next()
            if (usingKind == "await using") {
                if (!canAwait()) raise(tok.start, "Await using cannot appear outside of async function")
                next()
            }
            parseForAfterInit(parseVar(true, usingKind, start), awaitAt)
            return
        }
        val escaped = tok.escaped
        val ref = DestructuringErrors()
        val initPos = tok.start
        val init = if (awaitAt > -1) parseExprSubscripts(ref, FOR_AWAIT_INIT) else parseExpression(FOR_INIT, ref)
        val isForOf = !isKeyword("in") && isName("of")
        if (isKeyword("in") || isForOf) {
            if (awaitAt > -1) {
                if (isKeyword("in")) unexpected(awaitAt)
            } else if (isForOf && init.start == initPos && !escaped && init.type == N.IDENT && init.name == "async") {
                // `for (async of x)` would read as the start of an async arrow function.
                unexpected()
            }
            if (startsWithLet && isForOf) raise(init.start, "The left-hand side of a for-of loop may not start with 'let'.")
            toAssignable(init, false, ref)
            checkLValPattern(init)
            parseForIn(null)
            return
        }
        checkExpressionErrors(ref, true)
        if (awaitAt > -1) unexpected(awaitAt)
        parseForRest()
    }

    private fun parseForAfterInit(
        init: VarDeclaration,
        awaitAt: Int,
    ) {
        if ((isKeyword("in") || isName("of")) && init.count == 1) {
            if (isKeyword("in")) {
                if (init.kind.endsWith("using") && !init.firstHasInit) {
                    raise(tok.start, "Using declaration is not allowed in for-in loops")
                }
                if (awaitAt > -1) unexpected(awaitAt)
            }
            parseForIn(init)
            return
        }
        if (awaitAt > -1) unexpected(awaitAt)
        parseForRest()
    }

    private fun parseForRest() {
        expect(";")
        if (!isPunct(";")) parseExpression()
        expect(";")
        if (!isPunct(")")) parseExpression()
        expect(")")
        parseStatement("for")
        exitScope()
        labels.removeAt(labels.lastIndex)
    }

    private fun parseForIn(init: VarDeclaration?) {
        val isForIn = isKeyword("in")
        next()
        // Only sloppy `for (var x = 1 in y)` may initialise its variable.
        if (init != null && init.firstHasInit && (!isForIn || strict || init.kind != "var" || !init.firstIsIdent)) {
            raise(init.start, "${if (isForIn) "for-in" else "for-of"} loop variable declaration may not have an initializer")
        }
        if (isForIn) parseExpression() else parseMaybeAssign()
        expect(")")
        parseStatement("for")
        exitScope()
        labels.removeAt(labels.lastIndex)
    }

    private fun parseVar(
        isFor: Boolean,
        kind: String,
        start: Int,
    ): VarDeclaration {
        val declaration = VarDeclaration(kind, start)
        val isUsing = kind.endsWith("using")
        do {
            val id = if (isUsing) parseIdent() else parseBindingAtom()
            checkLValPattern(id, if (kind == "var") Bind.VAR else Bind.LEXICAL)
            var hasInit = false
            // The head of a for-in or for-of names its variable without a value.
            val loopHead = isKeyword("in") || isName("of")
            if (eat("=")) {
                parseMaybeAssign(if (isFor) FOR_INIT else 0)
                hasInit = true
            } else if (kind == "const" && !loopHead) {
                unexpected()
            } else if (isUsing && !loopHead) {
                raise(lastTokEnd, "Missing initializer in $kind declaration")
            } else if (id.type != N.IDENT && !(isFor && loopHead)) {
                raise(lastTokEnd, "Complex binding patterns require an initialization value")
            }
            if (declaration.count++ == 0) {
                declaration.firstHasInit = hasInit
                declaration.firstIsIdent = id.type == N.IDENT
            }
        } while (eat(","))
        return declaration
    }

    private fun parseTry(start: Int) {
        next()
        parseBlock()
        var handled = false
        if (eatKeyword("catch")) {
            handled = true
            if (eat("(")) {
                val param = parseBindingAtom()
                val simple = param.type == N.IDENT
                enterScope(if (simple) SCOPE_SIMPLE_CATCH else 0)
                checkLValPattern(param, if (simple) Bind.SIMPLE_CATCH else Bind.LEXICAL)
                expect(")")
            } else {
                enterScope(0)
            }
            parseBlock(createScope = false)
            exitScope()
        }
        if (eatKeyword("finally")) {
            handled = true
            parseBlock()
        }
        if (!handled) raise(start, "Missing catch or finally clause")
    }

    private fun parseSwitch() {
        next()
        parseParenExpression()
        expect("{")
        labels += Label(null, "switch")
        enterScope(SCOPE_SWITCH)
        var sawCase = false
        var sawDefault = false
        while (!isPunct("}")) {
            if (isKeyword("case") || isKeyword("default")) {
                val isCase = isKeyword("case")
                sawCase = true
                next()
                if (isCase) {
                    parseExpression()
                } else {
                    if (sawDefault) raise(lastTokStart, "Multiple default clauses")
                    sawDefault = true
                }
                expect(":")
            } else {
                if (!sawCase) unexpected()
                parseStatement(null)
            }
        }
        exitScope()
        next()
        labels.removeAt(labels.lastIndex)
    }

    private fun parseFunction(
        start: Int,
        statement: Int,
        isAsync: Boolean,
        forInit: Int = 0,
    ): Node {
        if (isPunct("*") && statement and FUNC_HANGING_STATEMENT != 0) unexpected()
        val generator = eat("*")
        var id: Node? = null
        if (statement and FUNC_STATEMENT != 0) {
            id = parseIdent()
            if (statement and FUNC_HANGING_STATEMENT == 0) {
                // A plain sloppy function declaration may repeat a `var`; the other kinds bind like `let`.
                val bind =
                    when {
                        !(strict || generator || isAsync) -> Bind.FUNCTION
                        treatFunctionsAsVar(currentScope()) -> Bind.VAR
                        else -> Bind.LEXICAL
                    }
                checkLValSimple(id, bind)
            }
        }
        val oldYieldPos = yieldPos
        val oldAwaitPos = awaitPos
        val oldAwaitIdentPos = awaitIdentPos
        yieldPos = 0
        awaitPos = 0
        awaitIdentPos = 0
        enterScope(functionFlags(isAsync, generator))
        if (statement and FUNC_STATEMENT == 0 && tok.type == T.NAME) id = parseIdent()
        expect("(")
        val params = parseBindingList(")", allowEmpty = false, allowTrailingComma = true)
        checkYieldAwaitInDefaultParams()
        parseFunctionBody(start, id, params, isArrow = false, isMethod = false, forInit = forInit)
        yieldPos = oldYieldPos
        awaitPos = oldAwaitPos
        awaitIdentPos = oldAwaitIdentPos
        return Node(N.OTHER, start)
    }

    private fun parseFunctionBody(
        start: Int,
        id: Node?,
        params: List<Node?>,
        isArrow: Boolean,
        isMethod: Boolean,
        forInit: Int,
    ) {
        val oldStrict = strict
        if (isArrow && !isPunct("{")) {
            parseMaybeAssign(forInit)
            checkParams(params, false)
        } else {
            val simple = params.all { it?.type == N.IDENT }
            var useStrict = false
            if (!oldStrict || !simple) {
                useStrict = strictDirective(tok.end)
                if (useStrict && !simple) raise(start, "Illegal 'use strict' directive in function with non-simple parameter list")
            }
            // Labels do not reach into a function.
            val oldLabels = labels
            labels = ArrayList()
            if (useStrict) strict = true
            checkParams(params, !oldStrict && !useStrict && !isArrow && !isMethod && simple)
            if (strict && id != null) checkLValSimple(id, Bind.OUTSIDE)
            parseBlock(createScope = false, exitStrict = useStrict && !oldStrict)
            labels = oldLabels
        }
        exitScope()
    }

    private fun checkParams(
        params: List<Node?>,
        allowDuplicates: Boolean,
    ) {
        val names = if (allowDuplicates) null else HashSet<String>()
        for (param in params) if (param != null) checkLValInnerPattern(param, Bind.VAR, names)
    }

    private fun parseClass(isStatement: Boolean): Node {
        val start = tok.start
        next()
        // A class is strict code throughout.
        val oldStrict = strict
        strict = true
        if (tok.type == T.NAME) {
            val id = parseIdent()
            if (isStatement) checkLValSimple(id, Bind.LEXICAL)
        } else if (isStatement) {
            unexpected()
        }
        val hasSuper = eatKeyword("extends")
        if (hasSuper) parseExprSubscripts(null, 0)
        val names = PrivateNames()
        privateNameStack += names
        var hadConstructor = false
        expect("{")
        while (!isPunct("}")) {
            val member = parseClassElement(hasSuper) ?: continue
            if (member.isMethod && member.kind == "constructor") {
                if (hadConstructor) raise(member.start, "Duplicate constructor in the same class")
                hadConstructor = true
            } else if (member.key.isPrivate && isPrivateNameConflicted(names.declared, member)) {
                raise(member.key.start, "Identifier '#${member.key.name}' has already been declared")
            }
        }
        strict = oldStrict
        next()
        exitClassBody()
        return Node(N.OTHER, start)
    }

    private fun exitClassBody() {
        val names = privateNameStack.removeAt(privateNameStack.lastIndex)
        val parent = privateNameStack.lastOrNull()
        for (used in names.used) {
            if (used.first in names.declared) continue
            // An outer class may still declare it.
            if (parent != null) {
                parent.used += used
            } else {
                raise(used.second, "Private field '#${used.first}' must be declared in an enclosing class")
            }
        }
    }

    /** A getter and a setter of the same kind may share a private name; nothing else may. */
    private fun isPrivateNameConflicted(
        declared: HashMap<String, String>,
        member: ClassMember,
    ): Boolean {
        val name = member.key.name ?: return false
        val current = declared[name]
        val accessor = member.isMethod && (member.kind == "get" || member.kind == "set")
        val next = if (accessor) (if (member.isStatic) "s" else "i") + member.kind else "true"
        val pair = setOf(current, next)
        if (pair == setOf("iget", "iset") || pair == setOf("sget", "sset")) {
            declared[name] = "true"
            return false
        }
        if (current == null) {
            declared[name] = next
            return false
        }
        return true
    }

    private fun isClassElementNameStart(): Boolean =
        when (tok.type) {
            T.NAME, T.PRIVATE_NAME, T.NUM, T.STRING, T.KEYWORD -> true
            else -> isPunct("[")
        }

    private fun parseClassElement(constructorAllowsSuper: Boolean): ClassMember? {
        if (eat(";")) return null
        val start = tok.start
        var keyName = ""
        var isGenerator = false
        var isAsync = false
        var kind = "method"
        var isStatic = false
        if (eatName("static")) {
            if (eat("{")) {
                parseClassStaticBlock()
                return null
            }
            if (isClassElementNameStart() || isPunct("*")) isStatic = true else keyName = "static"
        }
        if (keyName.isEmpty() && eatName("async")) {
            if ((isClassElementNameStart() || isPunct("*")) && !canInsertSemicolon()) isAsync = true else keyName = "async"
        }
        if (keyName.isEmpty() && eat("*")) isGenerator = true
        if (keyName.isEmpty() && !isAsync && !isGenerator) {
            val word = tok.value
            if (eatName("get") || eatName("set")) {
                if (isClassElementNameStart()) kind = word else keyName = word
            }
        }
        // `static`, `async`, `get` or `set` not followed by a name is itself the name.
        val key =
            when {
                keyName.isNotEmpty() -> {
                    Key(keyName, isIdent = true, computed = false, isPrivate = false, start = lastTokStart)
                }

                tok.type == T.PRIVATE_NAME -> {
                    if (tok.value == "constructor") raise(tok.start, "Classes can't have an element named '#constructor'")
                    val id = parsePrivateIdent()
                    Key(id.name, isIdent = false, computed = false, isPrivate = true, start = id.start)
                }

                else -> {
                    parsePropertyName()
                }
            }
        val named = { name: String -> !key.computed && !key.isPrivate && key.name == name }
        if (isPunct("(") || kind != "method" || isGenerator || isAsync) {
            val isConstructor = !isStatic && named("constructor")
            if (isConstructor && kind != "method") raise(key.start, "Constructor can't have get/set modifier")
            if (isConstructor) {
                kind = "constructor"
                if (isGenerator) raise(key.start, "Constructor can't be a generator")
                if (isAsync) raise(key.start, "Constructor can't be an async method")
            } else if (isStatic && named("prototype")) {
                raise(key.start, "Classes may not have a static property named prototype")
            }
            val valueStart = tok.start
            val params = parseMethod(isGenerator, isAsync, isConstructor && constructorAllowsSuper)
            checkAccessorParams(kind, params, valueStart)
            return ClassMember(kind, true, isStatic, key, start)
        }
        if (named("constructor")) raise(key.start, "Classes can't have a field named 'constructor'")
        if (isStatic && named("prototype")) raise(key.start, "Classes can't have a static field named 'prototype'")
        if (eat("=")) {
            enterScope(SCOPE_CLASS_FIELD_INIT or SCOPE_SUPER)
            parseMaybeAssign()
            exitScope()
        }
        semicolon()
        return ClassMember("field", false, isStatic, key, start)
    }

    private fun checkAccessorParams(
        kind: String,
        params: List<Node?>,
        valueStart: Int,
    ) {
        if (kind == "get" && params.isNotEmpty()) raise(valueStart, "getter should have no params")
        if (kind == "set" && params.size != 1) raise(valueStart, "setter should have exactly one param")
        if (kind == "set" && params[0]?.type == N.REST) raise(params[0]!!.start, "Setter cannot use rest params")
    }

    private fun parseClassStaticBlock() {
        val oldLabels = labels
        labels = ArrayList()
        enterScope(SCOPE_CLASS_STATIC_BLOCK or SCOPE_SUPER)
        while (!isPunct("}")) parseStatement(null)
        next()
        exitScope()
        labels = oldLabels
    }

    // ---- patterns ------------------------------------------------------------------------------

    /** Turn what was parsed as an expression into the pattern it has to be, now that `=` or `=>` follows it. */
    private fun toAssignable(
        node: Node,
        isBinding: Boolean,
        ref: DestructuringErrors? = null,
    ): Node {
        when (node.type) {
            N.IDENT -> {
                if (inAsync() && node.name == "await") raise(node.start, "Cannot use 'await' as identifier inside an async function")
            }

            N.OBJECT_PATTERN, N.ARRAY_PATTERN, N.ASSIGN_PATTERN, N.REST -> {}

            N.OBJECT -> {
                node.type = N.OBJECT_PATTERN
                checkPatternErrors(ref, true)
                for (prop in node.items) {
                    toAssignable(prop!!, isBinding)
                    val rest = prop.inner
                    if (prop.type == N.REST && (rest?.type == N.ARRAY_PATTERN || rest?.type == N.OBJECT_PATTERN)) {
                        raise(rest.start, "Unexpected token")
                    }
                }
            }

            N.PROPERTY -> {
                if (node.name != "init") raise(node.key!!.start, "Object pattern can't contain getter or setter")
                toAssignable(node.inner!!, isBinding)
            }

            N.ARRAY -> {
                node.type = N.ARRAY_PATTERN
                checkPatternErrors(ref, true)
                for (element in node.items) if (element != null) toAssignable(element, isBinding)
            }

            N.SPREAD -> {
                node.type = N.REST
                val argument = node.inner!!
                toAssignable(argument, isBinding)
                if (argument.type == N.ASSIGN_PATTERN) raise(argument.start, "Rest elements cannot have a default value")
            }

            N.ASSIGN -> {
                val target = node.inner!!
                if (node.name != "=") raise(target.end, "Only '=' operator can be used for specifying default value.")
                node.type = N.ASSIGN_PATTERN
                toAssignable(target, isBinding)
            }

            N.CHAIN -> {
                raise(node.start, "Optional chaining cannot appear in left-hand side")
            }

            else -> {
                // A member such as `a.b` can be assigned to, but a parameter cannot be one.
                if (node.type != N.MEMBER || isBinding) raise(node.start, "Assigning to rvalue")
            }
        }
        return node
    }

    private fun parseRestBinding(): Node {
        val node = Node(N.REST, tok.start)
        next()
        node.inner = parseBindingAtom()
        return node
    }

    private fun parseBindingAtom(): Node {
        if (isPunct("[")) {
            val node = Node(N.ARRAY_PATTERN, tok.start)
            next()
            node.items = parseBindingList("]", allowEmpty = true, allowTrailingComma = true)
            return node
        }
        if (isPunct("{")) return parseObj(true, null)
        return parseIdent()
    }

    private fun parseBindingList(
        close: String,
        allowEmpty: Boolean,
        allowTrailingComma: Boolean,
    ): List<Node?> {
        val elements = ArrayList<Node?>()
        var first = true
        while (!eat(close)) {
            if (first) first = false else expect(",")
            if (allowEmpty && isPunct(",")) {
                elements += null
            } else if (allowTrailingComma && eat(close)) {
                break
            } else if (isPunct("...")) {
                elements += parseRestBinding()
                if (isPunct(",")) raise(tok.start, "Comma is not permitted after the rest element")
                expect(close)
                break
            } else {
                elements += parseMaybeDefault(tok.start, null)
            }
        }
        return elements
    }

    private fun parseMaybeDefault(
        start: Int,
        left: Node?,
    ): Node {
        val target = left ?: parseBindingAtom()
        if (!eat("=")) return target
        parseMaybeAssign()
        val node = Node(N.ASSIGN_PATTERN, start)
        node.inner = target
        return node
    }

    /** A name or a member, nothing more: the target of `+=` and `++`, the name of a function. */
    private fun checkLValSimple(
        expr: Node,
        bind: Bind = Bind.NONE,
        clashes: MutableSet<String>? = null,
    ) {
        val isBind = bind != Bind.NONE
        when (expr.type) {
            N.IDENT -> {
                val name = expr.name
                if (strict && (name == "enum" || name in STRICT_RESERVED || name == "eval" || name == "arguments")) {
                    raise(expr.start, "${if (isBind) "Binding " else "Assigning to "}$name in strict mode")
                }
                if (isBind) {
                    if (bind == Bind.LEXICAL && name == "let") raise(expr.start, "let is disallowed as a lexically bound name")
                    if (clashes != null && !clashes.add(name)) raise(expr.start, "Argument name clash")
                    if (bind != Bind.OUTSIDE) declareName(name, bind, expr.start)
                }
            }

            N.CHAIN -> {
                raise(expr.start, "Optional chaining cannot appear in left-hand side")
            }

            N.MEMBER -> {
                if (isBind) raise(expr.start, "Binding member expression")
            }

            else -> {
                raise(expr.start, "${if (isBind) "Binding" else "Assigning to"} rvalue")
            }
        }
    }

    private fun checkLValPattern(
        expr: Node,
        bind: Bind = Bind.NONE,
        clashes: MutableSet<String>? = null,
    ) {
        when (expr.type) {
            N.OBJECT_PATTERN, N.ARRAY_PATTERN -> {
                for (item in expr.items) if (item != null) checkLValInnerPattern(item, bind, clashes)
            }

            else -> {
                checkLValSimple(expr, bind, clashes)
            }
        }
    }

    private fun checkLValInnerPattern(
        expr: Node,
        bind: Bind = Bind.NONE,
        clashes: MutableSet<String>? = null,
    ) {
        when (expr.type) {
            N.PROPERTY -> checkLValInnerPattern(expr.inner!!, bind, clashes)
            N.ASSIGN_PATTERN, N.REST -> checkLValPattern(expr.inner!!, bind, clashes)
            else -> checkLValPattern(expr, bind, clashes)
        }
    }

    // ---- expressions ---------------------------------------------------------------------------

    private fun parseExpression(
        forInit: Int = 0,
        ref: DestructuringErrors? = null,
    ): Node {
        val start = tok.start
        val expr = parseMaybeAssign(forInit, ref)
        if (!isPunct(",")) return expr
        while (eat(",")) parseMaybeAssign(forInit, ref)
        return Node(N.OTHER, start)
    }

    private fun parseMaybeAssign(
        forInit: Int = 0,
        outerRef: DestructuringErrors? = null,
    ): Node {
        if (isName("yield") && inGenerator()) return parseYield(forInit)
        val own = outerRef == null
        val ref = outerRef ?: DestructuringErrors()
        val oldParenAssign = ref.parenthesizedAssign
        val oldTrailingComma = ref.trailingComma
        val oldDoubleProto = ref.doubleProto
        ref.parenthesizedAssign = -1
        ref.trailingComma = -1
        val start = tok.start
        if (isPunct("(") || tok.type == T.NAME) {
            potentialArrowAt = tok.start
            potentialArrowInForAwait = forInit == FOR_AWAIT_INIT
        }
        val left = parseMaybeConditional(forInit, ref)
        if (tok.type == T.PUNCT && tok.value in ASSIGN_OPS) {
            val op = tok.value
            if (op == "=") toAssignable(left, false, ref)
            if (!own) {
                ref.parenthesizedAssign = -1
                ref.trailingComma = -1
                ref.doubleProto = -1
            }
            // A shorthand default such as `{a = 1}` turned out to sit in a pattern after all.
            if (ref.shorthandAssign >= left.start) ref.shorthandAssign = -1
            if (op == "=") checkLValPattern(left) else checkLValSimple(left)
            next()
            parseMaybeAssign(forInit)
            if (oldDoubleProto > -1) ref.doubleProto = oldDoubleProto
            val node = Node(N.ASSIGN, start)
            node.name = op
            node.inner = left
            return node
        }
        if (own) checkExpressionErrors(ref, true)
        if (oldParenAssign > -1) ref.parenthesizedAssign = oldParenAssign
        if (oldTrailingComma > -1) ref.trailingComma = oldTrailingComma
        return left
    }

    private fun parseMaybeConditional(
        forInit: Int,
        ref: DestructuringErrors?,
    ): Node {
        val start = tok.start
        val expr = parseExprOps(forInit, ref)
        if (checkExpressionErrors(ref)) return expr
        if (!(expr.type == N.ARROW && expr.start == start) && eat("?")) {
            parseMaybeAssign()
            expect(":")
            parseMaybeAssign(forInit)
            return Node(N.OTHER, start)
        }
        return expr
    }

    private fun parseExprOps(
        forInit: Int,
        ref: DestructuringErrors?,
    ): Node {
        val start = tok.start
        val expr = parseMaybeUnary(ref, false, false, forInit)
        if (checkExpressionErrors(ref)) return expr
        return if (expr.start == start && expr.type == N.ARROW) expr else parseExprOp(expr, start, -1, forInit)
    }

    private fun binaryPrecedence(): Int {
        if (tok.type == T.KEYWORD) return if (tok.value == "in" || tok.value == "instanceof") 7 else -1
        if (tok.type != T.PUNCT) return -1
        return when (tok.value) {
            "??", "||" -> 1
            "&&" -> 2
            "|" -> 3
            "^" -> 4
            "&" -> 5
            "==", "!=", "===", "!==" -> 6
            "<", ">", "<=", ">=" -> 7
            "<<", ">>", ">>>" -> 8
            "+", "-" -> 9
            "*", "/", "%" -> 10
            else -> -1
        }
    }

    private fun parseExprOp(
        left: Node,
        leftStart: Int,
        minPrec: Int,
        forInit: Int,
    ): Node {
        val prec = binaryPrecedence()
        if (prec <= minPrec || (forInit != 0 && isKeyword("in"))) return left
        val logical = isPunct("||") || isPunct("&&")
        val coalesce = isPunct("??")
        next()
        val start = tok.start
        // The right side of `??` stops before `||` and `&&`, so that mixing them is seen below.
        val right = parseExprOp(parseMaybeUnary(null, false, false, forInit), start, if (coalesce) 2 else prec, forInit)
        if (right.type == N.PRIVATE_NAME) raise(right.start, "Private identifier can only be left side of binary expression")
        if ((logical && isPunct("??")) || (coalesce && (isPunct("||") || isPunct("&&")))) {
            raise(tok.start, "Logical expressions and coalesce expressions cannot be mixed. Wrap either by parentheses")
        }
        return parseExprOp(Node(N.OTHER, leftStart), leftStart, minPrec, forInit)
    }

    private fun parseMaybeUnary(
        ref: DestructuringErrors?,
        afterUnary: Boolean,
        incDec: Boolean,
        forInit: Int,
    ): Node {
        val start = tok.start
        var sawUnary = afterUnary
        val expr: Node
        if (isName("await") && canAwait()) {
            if (awaitPos == 0) awaitPos = tok.start
            next()
            parseMaybeUnary(null, true, false, forInit)
            expr = Node(N.OTHER, start)
            sawUnary = true
        } else if ((tok.type == T.PUNCT && tok.value in PREFIX_OPS) || (tok.type == T.KEYWORD && tok.value in PREFIX_KEYWORDS)) {
            val op = tok.value
            val update = op == "++" || op == "--"
            next()
            val argument = parseMaybeUnary(null, true, update, forInit)
            checkExpressionErrors(ref, true)
            if (update) {
                checkLValSimple(argument)
            } else if (strict && op == "delete" && argument.type == N.IDENT) {
                raise(start, "Deleting local variable in strict mode")
            } else if (op == "delete" && isPrivateFieldAccess(argument)) {
                raise(start, "Private fields can not be deleted")
            } else {
                sawUnary = true
            }
            expr = Node(N.OTHER, start)
        } else if (!sawUnary && tok.type == T.PRIVATE_NAME) {
            // `#x in obj`, inside the class that declares `#x`.
            if (forInit != 0 || privateNameStack.isEmpty()) unexpected()
            expr = parsePrivateIdent()
            if (!isKeyword("in")) unexpected()
        } else {
            var operand = parseExprSubscripts(ref, forInit)
            if (checkExpressionErrors(ref)) return operand
            while ((isPunct("++") || isPunct("--")) && !canInsertSemicolon()) {
                checkLValSimple(operand)
                next()
                operand = Node(N.OTHER, start)
            }
            expr = operand
        }
        if (!incDec && !(expr.type == N.ARROW && expr.start == start) && eat("**")) {
            if (sawUnary) raise(lastTokStart, "Unary operator used immediately before exponentiation expression")
            parseMaybeUnary(null, false, false, forInit)
            return Node(N.OTHER, start)
        }
        return expr
    }

    private fun isPrivateFieldAccess(node: Node): Boolean =
        (node.type == N.MEMBER && node.privateProperty) || (node.type == N.CHAIN && isPrivateFieldAccess(node.inner!!))

    private fun parseExprSubscripts(
        ref: DestructuringErrors?,
        forInit: Int,
    ): Node {
        val start = tok.start
        val expr = parseExprAtom(ref, forInit, false)
        // An arrow function with a block body ends the expression: `a => {} (b)` is not a call.
        if (expr.type == N.ARROW && src.substring(lastTokStart, lastTokEnd) != ")") return expr
        val result = parseSubscripts(expr, start, false, forInit)
        if (ref != null && result.type == N.MEMBER) {
            if (ref.parenthesizedAssign >= result.start) ref.parenthesizedAssign = -1
            if (ref.parenthesizedBind >= result.start) ref.parenthesizedBind = -1
            if (ref.trailingComma >= result.start) ref.trailingComma = -1
        }
        return result
    }

    private fun parseSubscripts(
        base: Node,
        start: Int,
        noCalls: Boolean,
        forInit: Int,
    ): Node {
        val maybeAsyncArrow =
            base.type == N.IDENT &&
                base.name == "async" &&
                lastTokEnd == base.end &&
                !canInsertSemicolon() &&
                base.end - base.start == 5 &&
                potentialArrowAt == base.start
        var optionalChained = false
        var current = base
        while (true) {
            val element = parseSubscript(current, start, noCalls, maybeAsyncArrow, optionalChained, forInit)
            if (element.optional) optionalChained = true
            if (element === current || element.type == N.ARROW) {
                if (!optionalChained) return element
                val chain = Node(N.CHAIN, start)
                chain.inner = element
                return chain
            }
            current = element
        }
    }

    private fun parseSubscript(
        base: Node,
        start: Int,
        noCalls: Boolean,
        maybeAsyncArrow: Boolean,
        optionalChained: Boolean,
        forInit: Int,
    ): Node {
        val optional = eat("?.")
        if (noCalls && optional) raise(lastTokStart, "Optional chaining cannot appear in the callee of new expressions")
        val computed = eat("[")
        if (computed || (optional && !isPunct("(") && tok.type != T.TEMPLATE) || eat(".")) {
            val node = Node(N.MEMBER, start)
            if (computed) {
                parseExpression()
                expect("]")
            } else if (tok.type == T.PRIVATE_NAME && base.type != N.SUPER) {
                parsePrivateIdent()
                node.privateProperty = true
            } else {
                parseIdent(liberal = true)
            }
            node.optional = optional
            return node
        }
        if (!noCalls && eat("(")) {
            val ref = DestructuringErrors()
            val oldYieldPos = yieldPos
            val oldAwaitPos = awaitPos
            val oldAwaitIdentPos = awaitIdentPos
            yieldPos = 0
            awaitPos = 0
            awaitIdentPos = 0
            val arguments = parseExprList(")", allowTrailingComma = true, allowEmpty = false, ref = ref)
            if (maybeAsyncArrow && !optional && !canInsertSemicolon() && eat("=>")) {
                // `async (a, b) => ...`: what looked like a call was a parameter list.
                checkPatternErrors(ref, false)
                checkYieldAwaitInDefaultParams()
                if (awaitIdentPos > 0) raise(awaitIdentPos, "Cannot use 'await' as identifier inside an async function")
                yieldPos = oldYieldPos
                awaitPos = oldAwaitPos
                awaitIdentPos = oldAwaitIdentPos
                return parseArrowExpression(start, arguments, true, forInit)
            }
            checkExpressionErrors(ref, true)
            if (oldYieldPos != 0) yieldPos = oldYieldPos
            if (oldAwaitPos != 0) awaitPos = oldAwaitPos
            if (oldAwaitIdentPos != 0) awaitIdentPos = oldAwaitIdentPos
            val node = Node(N.OTHER, start)
            node.optional = optional
            return node
        }
        if (tok.type == T.TEMPLATE) {
            if (optional || optionalChained) {
                raise(tok.start, "Optional chaining cannot appear in the tag of tagged template expressions")
            }
            parseTemplate(isTagged = true)
            return Node(N.OTHER, start)
        }
        return base
    }

    private fun parseExprAtom(
        ref: DestructuringErrors?,
        forInit: Int,
        forNew: Boolean,
    ): Node {
        // Where an expression begins a slash opens a regular expression; the tokenizer read it as a division.
        // After `await`, acorn takes `/=` for the operator, so `await /=/` is an error there and here.
        if (isPunct("/") || (isPunct("/=") && src.substring(lastTokStart, lastTokEnd) != "await")) {
            pos = tok.start + 1
            tok = readRegexp(tok.start, tok.newlineBefore)
        }
        val start = tok.start
        val canBeArrow = potentialArrowAt == start
        return when (tok.type) {
            T.KEYWORD -> {
                parseKeywordAtom(forInit, forNew)
            }

            T.NAME -> {
                parseNameAtom(canBeArrow, forInit)
            }

            T.NUM, T.STRING, T.REGEX -> {
                next()
                Node(N.OTHER, start)
            }

            T.TEMPLATE -> {
                parseTemplate(isTagged = false)
            }

            T.PUNCT -> {
                when (tok.value) {
                    "(" -> {
                        val expr = parseParenAndDistinguishExpression(canBeArrow, forInit)
                        if (ref != null) {
                            val simple = expr.type == N.IDENT || expr.type == N.MEMBER
                            if (ref.parenthesizedAssign < 0 && !simple) ref.parenthesizedAssign = start
                            if (ref.parenthesizedBind < 0) ref.parenthesizedBind = start
                        }
                        expr
                    }

                    "[" -> {
                        val node = Node(N.ARRAY, start)
                        next()
                        node.items = parseExprList("]", allowTrailingComma = true, allowEmpty = true, ref = ref)
                        node
                    }

                    "{" -> {
                        parseObj(false, ref)
                    }

                    else -> {
                        unexpected()
                    }
                }
            }

            else -> {
                unexpected()
            }
        }
    }

    private fun parseKeywordAtom(
        forInit: Int,
        forNew: Boolean,
    ): Node {
        val start = tok.start
        when (tok.value) {
            "super" -> {
                val scope = currentThisScope()
                if (scope.flags and SCOPE_SUPER == 0) raise(start, "'super' keyword outside a method")
                next()
                if (isPunct("(") && scope.flags and SCOPE_DIRECT_SUPER == 0) {
                    raise(start, "super() call outside constructor of a subclass")
                }
                if (!isPunct(".") && !isPunct("[") && !isPunct("(")) unexpected()
                return Node(N.SUPER, start)
            }

            "this", "null", "true", "false" -> {
                next()
                return Node(N.OTHER, start)
            }

            "function" -> {
                next()
                return parseFunction(start, 0, isAsync = false)
            }

            "class" -> {
                return parseClass(isStatement = false)
            }

            "new" -> {
                return parseNew()
            }

            "import" -> {
                next()
                if (isPunct("(") && !forNew) return parseDynamicImport(start)
                if (!eat(".")) unexpected()
                val property = parseIdent(liberal = true)
                if (property.name != "meta") raise(property.start, "The only valid meta property for import is 'import.meta'")
                raise(start, "Cannot use 'import.meta' outside a module")
            }

            else -> {
                unexpected()
            }
        }
    }

    private fun parseNameAtom(
        canBeArrow: Boolean,
        forInit: Int,
    ): Node {
        val start = tok.start
        val escaped = tok.escaped
        val id = parseIdent()
        if (!escaped && id.name == "async" && !canInsertSemicolon() && eatKeyword("function")) {
            return parseFunction(start, 0, isAsync = true, forInit = forInit)
        }
        if (canBeArrow && !canInsertSemicolon()) {
            if (eat("=>")) return parseArrowExpression(start, listOf(id), false, forInit)
            val arrowParameter = tok.type == T.NAME && (!potentialArrowInForAwait || tok.value != "of" || tok.escaped)
            if (id.name == "async" && !escaped && arrowParameter) {
                // `async x => ...`; `async` followed by any other name is an error.
                val param = parseIdent()
                if (canInsertSemicolon() || !eat("=>")) unexpected()
                return parseArrowExpression(start, listOf(param), true, forInit)
            }
        }
        return id
    }

    private fun parseDynamicImport(start: Int): Node {
        next()
        parseMaybeAssign()
        // A second argument (the options) and a trailing comma are allowed; a third argument is not.
        if (!eat(")")) {
            expect(",")
            if (!eat(")")) {
                parseMaybeAssign()
                if (!eat(")")) {
                    expect(",")
                    if (!eat(")")) unexpected()
                }
            }
        }
        return Node(N.OTHER, start)
    }

    private fun parseParenAndDistinguishExpression(
        canBeArrow: Boolean,
        forInit: Int,
    ): Node {
        val start = tok.start
        next()
        val innerStart = tok.start
        val list = ArrayList<Node?>()
        var first = true
        var lastIsComma = false
        var spreadStart = -1
        val ref = DestructuringErrors()
        val oldYieldPos = yieldPos
        val oldAwaitPos = awaitPos
        yieldPos = 0
        awaitPos = 0
        while (!isPunct(")")) {
            if (first) first = false else expect(",")
            if (isPunct(")")) {
                lastIsComma = true
                break
            } else if (isPunct("...")) {
                spreadStart = tok.start
                list += parseRestBinding()
                if (isPunct(",")) raise(tok.start, "Comma is not permitted after the rest element")
                break
            } else {
                list += parseMaybeAssign(0, ref)
            }
        }
        expect(")")
        if (canBeArrow && !canInsertSemicolon() && eat("=>")) {
            checkPatternErrors(ref, false)
            checkYieldAwaitInDefaultParams()
            yieldPos = oldYieldPos
            awaitPos = oldAwaitPos
            return parseArrowExpression(start, list, false, forInit)
        }
        // Not parameters after all, so an empty list, a trailing comma and a rest element are errors.
        if (list.isEmpty() || lastIsComma) unexpected(lastTokStart)
        if (spreadStart > -1) unexpected(spreadStart)
        checkExpressionErrors(ref, true)
        if (oldYieldPos != 0) yieldPos = oldYieldPos
        if (oldAwaitPos != 0) awaitPos = oldAwaitPos
        return if (list.size > 1) Node(N.OTHER, innerStart) else list[0]!!
    }

    private fun parseNew(): Node {
        val start = tok.start
        next()
        if (eat(".")) {
            val escaped = tok.escaped
            val property = parseIdent(liberal = true)
            if (property.name != "target") raise(property.start, "The only valid meta property for new is 'new.target'")
            if (escaped) raise(start, "'new.target' must not contain escaped characters")
            if (!allowNewDotTarget()) raise(start, "'new.target' can only be used in functions and class static block")
            return Node(N.OTHER, start)
        }
        // Member accesses bind to the constructor; the first argument list belongs to `new`.
        val calleeStart = tok.start
        val callee = parseSubscripts(parseExprAtom(null, 0, true), calleeStart, true, 0)
        if (callee.type == N.SUPER) raise(calleeStart, "Invalid use of 'super'")
        if (eat("(")) parseExprList(")", allowTrailingComma = true, allowEmpty = false, ref = null)
        return Node(N.OTHER, start)
    }

    /**
     * A template literal, entered at its opening backquote. The text between the expressions is
     * read here, straight from the source, since it is not made of JavaScript tokens.
     */
    private fun parseTemplate(isTagged: Boolean): Node {
        val start = tok.start
        while (true) {
            if (pos >= src.length) raise(pos, "Unterminated template literal")
            if (readTemplateChunk(isTagged)) break
            tok = JsToken(T.PUNCT, "\${", pos, pos + 2, false)
            pos += 2
            next()
            parseExpression()
            if (!isPunct("}")) unexpected()
        }
        tok = JsToken(T.TEMPLATE, "`", pos, pos + 1, false)
        pos++
        next()
        return Node(N.OTHER, start)
    }

    /** Reads template text up to the closing backquote (true) or the next `${` (false). */
    private fun readTemplateChunk(isTagged: Boolean): Boolean {
        val start = pos
        val atEnd = { src[pos] == '`' || (src[pos] == '$' && src.getOrNull(pos + 1) == '{') }
        inTemplateElement = true
        try {
            while (true) {
                if (pos >= src.length) raise(start, "Unterminated template")
                if (atEnd()) break
                if (src[pos] == '\\') readEscapedChar(true) else pos++
            }
        } catch (_: InvalidTemplateEscape) {
            // A tag receives the raw text, so only an untagged template has to have valid escapes.
            while (true) {
                if (pos >= src.length) raise(start, "Unterminated template")
                if (atEnd()) break
                pos += if (src[pos] == '\\') 2 else 1
            }
            if (!isTagged) raise(start, "Bad escape sequence in untagged template literal")
        } finally {
            inTemplateElement = false
        }
        return src[pos] == '`'
    }

    private fun parseObj(
        isPattern: Boolean,
        ref: DestructuringErrors?,
    ): Node {
        val node = Node(if (isPattern) N.OBJECT_PATTERN else N.OBJECT, tok.start)
        val properties = ArrayList<Node?>()
        var first = true
        var sawProto = false
        next()
        while (!eat("}")) {
            if (first) {
                first = false
            } else {
                expect(",")
                if (eat("}")) break
            }
            val prop = parseProperty(isPattern, ref)
            val key = prop.key
            if (!isPattern && prop.plain && key != null && !key.computed && key.name == "__proto__") {
                // Two `__proto__: value` are an error in an object, and fine in a pattern.
                if (sawProto) {
                    if (ref == null) raise(key.start, "Redefinition of __proto__ property")
                    if (ref.doubleProto < 0) ref.doubleProto = key.start
                }
                sawProto = true
            }
            properties += prop
        }
        node.items = properties
        return node
    }

    private fun parseProperty(
        isPattern: Boolean,
        ref: DestructuringErrors?,
    ): Node {
        val start = tok.start
        if (eat("...")) {
            if (isPattern) {
                val rest = Node(N.REST, start)
                rest.inner = parseIdent()
                if (isPunct(",")) raise(tok.start, "Comma is not permitted after the rest element")
                return rest
            }
            val spread = Node(N.SPREAD, start)
            spread.inner = parseMaybeAssign(0, ref)
            if (isPunct(",") && ref != null && ref.trailingComma < 0) ref.trailingComma = tok.start
            return spread
        }
        val prop = Node(N.PROPERTY, start)
        var isGenerator = !isPattern && eat("*")
        var isAsync = false
        val escaped = tok.escaped
        var key = parsePropertyName()
        if (!isPattern && !escaped && !isGenerator && isAsyncProp(key)) {
            isAsync = true
            isGenerator = eat("*")
            key = parsePropertyName()
        }
        prop.key = key
        prop.name = "init"
        if ((isGenerator || isAsync) && isPunct(":")) unexpected()
        val accessor = !isPattern && !escaped && !key.computed && key.isIdent && (key.name == "get" || key.name == "set")
        if (eat(":")) {
            prop.inner = if (isPattern) parseMaybeDefault(tok.start, null) else parseMaybeAssign(0, ref)
            prop.plain = true
        } else if (isPunct("(")) {
            if (isPattern) unexpected()
            prop.inner = Node(N.OTHER, tok.start)
            parseMethod(isGenerator, isAsync, false)
        } else if (accessor && !isPunct(",") && !isPunct("}") && !isPunct("=")) {
            if (isGenerator || isAsync) unexpected()
            prop.name = key.name
            prop.key = parsePropertyName()
            val valueStart = tok.start
            prop.inner = Node(N.OTHER, valueStart)
            checkAccessorParams(prop.name, parseMethod(false, false, false), valueStart)
        } else if (!key.computed && key.isIdent) {
            // Shorthand `{a}`, or `{a = 1}`, which only a pattern may contain.
            if (isGenerator || isAsync) unexpected()
            val id = Node(N.IDENT, key.start)
            id.name = key.name!!
            id.end = lastTokEnd
            checkUnreserved(id.start, id.name)
            if (id.name == "await" && awaitIdentPos == 0) awaitIdentPos = start
            if (isPattern) {
                prop.inner = parseMaybeDefault(start, id)
            } else if (isPunct("=") && ref != null) {
                if (ref.shorthandAssign < 0) ref.shorthandAssign = tok.start
                prop.inner = parseMaybeDefault(start, id)
            } else {
                prop.inner = id
            }
        } else {
            unexpected()
        }
        return prop
    }

    /** `async` as a method modifier: followed, on the same line, by the name of the method. */
    private fun isAsyncProp(key: Key): Boolean {
        if (key.computed || !key.isIdent || key.name != "async" || tok.newlineBefore) return false
        return tok.type == T.NAME || tok.type == T.NUM || tok.type == T.STRING || tok.type == T.KEYWORD || isPunct("[") || isPunct("*")
    }

    private fun parsePropertyName(): Key {
        val start = tok.start
        if (eat("[")) {
            parseMaybeAssign()
            expect("]")
            return Key(null, isIdent = false, computed = true, isPrivate = false, start = start)
        }
        if (tok.type == T.NUM || tok.type == T.STRING) {
            val name = if (tok.type == T.STRING) tok.value else null
            next()
            return Key(name, isIdent = false, computed = false, isPrivate = false, start = start)
        }
        return Key(parseIdent(liberal = true).name, isIdent = true, computed = false, isPrivate = false, start = start)
    }

    /** The parameters and body of an object or class method; returns the parameters. */
    private fun parseMethod(
        isGenerator: Boolean,
        isAsync: Boolean,
        allowDirectSuper: Boolean,
    ): List<Node?> {
        val start = tok.start
        val oldYieldPos = yieldPos
        val oldAwaitPos = awaitPos
        val oldAwaitIdentPos = awaitIdentPos
        yieldPos = 0
        awaitPos = 0
        awaitIdentPos = 0
        enterScope(functionFlags(isAsync, isGenerator) or SCOPE_SUPER or (if (allowDirectSuper) SCOPE_DIRECT_SUPER else 0))
        expect("(")
        val params = parseBindingList(")", allowEmpty = false, allowTrailingComma = true)
        checkYieldAwaitInDefaultParams()
        parseFunctionBody(start, null, params, isArrow = false, isMethod = true, forInit = 0)
        yieldPos = oldYieldPos
        awaitPos = oldAwaitPos
        awaitIdentPos = oldAwaitIdentPos
        return params
    }

    private fun parseArrowExpression(
        start: Int,
        params: List<Node?>,
        isAsync: Boolean,
        forInit: Int,
    ): Node {
        val oldYieldPos = yieldPos
        val oldAwaitPos = awaitPos
        val oldAwaitIdentPos = awaitIdentPos
        enterScope(functionFlags(isAsync, false) or SCOPE_ARROW)
        yieldPos = 0
        awaitPos = 0
        awaitIdentPos = 0
        for (param in params) if (param != null) toAssignable(param, true)
        parseFunctionBody(start, null, params, isArrow = true, isMethod = false, forInit = forInit)
        yieldPos = oldYieldPos
        awaitPos = oldAwaitPos
        awaitIdentPos = oldAwaitIdentPos
        return Node(N.ARROW, start)
    }

    private fun parseExprList(
        close: String,
        allowTrailingComma: Boolean,
        allowEmpty: Boolean,
        ref: DestructuringErrors?,
    ): List<Node?> {
        val elements = ArrayList<Node?>()
        var first = true
        while (!eat(close)) {
            if (first) {
                first = false
            } else {
                expect(",")
                if (allowTrailingComma && eat(close)) break
            }
            if (allowEmpty && isPunct(",")) {
                elements += null
            } else if (isPunct("...")) {
                val spread = Node(N.SPREAD, tok.start)
                next()
                spread.inner = parseMaybeAssign(0, ref)
                elements += spread
                if (ref != null && isPunct(",") && ref.trailingComma < 0) ref.trailingComma = tok.start
            } else {
                elements += parseMaybeAssign(0, ref)
            }
        }
        return elements
    }

    private fun checkUnreserved(
        start: Int,
        name: String,
    ) {
        if (inGenerator() && name == "yield") raise(start, "Cannot use 'yield' as identifier inside a generator")
        if (inAsync() && name == "await") raise(start, "Cannot use 'await' as identifier inside an async function")
        if (currentThisScope().flags and SCOPE_VAR == 0 && name == "arguments") {
            raise(start, "Cannot use 'arguments' in class field initializer")
        }
        if (inClassStaticBlock() && (name == "arguments" || name == "await")) {
            raise(start, "Cannot use $name in class static initialization block")
        }
        if (name in KEYWORDS) raise(start, "Unexpected keyword '$name'")
        if (name == "enum" || (strict && name in STRICT_RESERVED)) raise(start, "The keyword '$name' is reserved")
    }

    /** A name; with [liberal], as after a dot or as a property key, a keyword serves as one too. */
    private fun parseIdent(liberal: Boolean = false): Node {
        if (tok.type != T.NAME && tok.type != T.KEYWORD) unexpected()
        val node = Node(N.IDENT, tok.start)
        node.name = tok.value
        // The word is consumed as a name, so a keyword spelled with an escape is reported as a keyword below.
        next(ignoreEscapedKeyword = true)
        node.end = lastTokEnd
        if (!liberal) {
            checkUnreserved(node.start, node.name)
            if (node.name == "await" && awaitIdentPos == 0) awaitIdentPos = node.start
        }
        return node
    }

    private fun parsePrivateIdent(): Node {
        if (tok.type != T.PRIVATE_NAME) unexpected()
        val node = Node(N.PRIVATE_NAME, tok.start)
        node.name = tok.value
        next()
        val names = privateNameStack.lastOrNull()
        if (names == null) raise(node.start, "Private field '#${node.name}' must be declared in an enclosing class")
        names.used += node.name to node.start
        return node
    }

    private fun parseYield(forInit: Int): Node {
        val start = tok.start
        if (yieldPos == 0) yieldPos = start
        next()
        val startsExpression =
            when (tok.type) {
                T.NAME, T.NUM, T.STRING, T.REGEX, T.TEMPLATE, T.PRIVATE_NAME -> true
                T.KEYWORD -> tok.value in EXPRESSION_KEYWORDS
                T.PUNCT -> tok.value in EXPRESSION_PUNCTUATION
                T.EOF -> false
            }
        if (!isPunct(";") && !canInsertSemicolon() && (isPunct("*") || startsExpression)) {
            eat("*")
            parseMaybeAssign(forInit)
        }
        return Node(N.OTHER, start)
    }

    // ---- tokens --------------------------------------------------------------------------------

    private fun next(ignoreEscapedKeyword: Boolean = false) {
        if (!ignoreEscapedKeyword && tok.type == T.KEYWORD && tok.escaped) raise(tok.start, "Escape sequence in keyword ${tok.value}")
        lastTokStart = tok.start
        lastTokEnd = tok.end
        tok = readToken()
    }

    private fun token(
        type: T,
        value: String,
        start: Int,
    ): JsToken = JsToken(type, value, start, pos, hasLineBreak(lastTokEnd, start))

    private fun skipLineComment(startSkip: Int) {
        pos += startSkip
        while (pos < src.length && !isNewLine(src[pos])) pos++
    }

    private fun skipSpace() {
        while (pos < src.length) {
            val c = src[pos]
            if (isJsSpace(c)) {
                pos++
            } else if (c == '/' && src.getOrNull(pos + 1) == '/') {
                skipLineComment(2)
            } else if (c == '/' && src.getOrNull(pos + 1) == '*') {
                pos += 2
                val close = src.indexOf("*/", pos)
                if (close < 0) raise(pos - 2, "Unterminated comment")
                pos = close + 2
            } else {
                return
            }
        }
    }

    private fun readToken(): JsToken {
        while (true) {
            skipSpace()
            val start = pos
            if (pos >= src.length) return token(T.EOF, "", start)
            val code = src.codePointAt(pos)
            if (isIdStart(code) || code == '\\'.code) {
                val word = readWord1()
                return JsToken(if (word in KEYWORDS) T.KEYWORD else T.NAME, word, start, pos, hasLineBreak(lastTokEnd, start), containsEsc)
            }
            // A script still honours the HTML comment markers: `<!--` anywhere, `-->` at the start of a line.
            if (src.startsWith("<!--", pos)) {
                skipLineComment(4)
            } else if (src.startsWith("-->", pos) && (lastTokEnd == 0 || hasLineBreak(lastTokEnd, pos))) {
                skipLineComment(3)
            } else {
                return readTokenFromCode(code, start)
            }
        }
    }

    private fun readTokenFromCode(
        code: Int,
        start: Int,
    ): JsToken {
        val c = src[pos]
        val next = src.getOrNull(pos + 1)
        val next2 = src.getOrNull(pos + 2)
        val size =
            when (c) {
                '.' -> {
                    if (next != null && next in '0'..'9') return readNumber(true)
                    if (next == '.' && next2 == '.') 3 else 1
                }

                '(', ')', ';', ',', '[', ']', '{', '}', ':', '~' -> {
                    1
                }

                '`' -> {
                    pos++
                    return token(T.TEMPLATE, "`", start)
                }

                in '0'..'9' -> {
                    val radix = if (c == '0') "xob".indexOf(next?.lowercaseChar() ?: ' ') else -1
                    return if (radix < 0) readNumber(false) else readRadixNumber(RADIXES[radix])
                }

                '"', '\'' -> {
                    return readString(c)
                }

                // Read as a division; `parseExprAtom` rereads it as a regular expression where one can stand.
                '/', '^' -> {
                    if (next == '=') 2 else 1
                }

                '%', '*' -> {
                    val power = c == '*' && next == '*'
                    val base = if (power) 2 else 1
                    if ((if (power) next2 else next) == '=') base + 1 else base
                }

                '|', '&' -> {
                    when {
                        next != c -> if (next == '=') 2 else 1
                        next2 == '=' -> 3
                        else -> 2
                    }
                }

                '+', '-' -> {
                    if (next == c || next == '=') 2 else 1
                }

                '<', '>' -> {
                    if (next == c) {
                        val shift = if (c == '>' && next2 == '>') 3 else 2
                        if (src.getOrNull(pos + shift) == '=') shift + 1 else shift
                    } else if (next == '=') {
                        2
                    } else {
                        1
                    }
                }

                '=', '!' -> {
                    when {
                        next == '=' -> if (next2 == '=') 3 else 2
                        c == '=' && next == '>' -> 2
                        else -> 1
                    }
                }

                '?' -> {
                    // `?.` followed by a digit is a conditional with a decimal: `a?.5:1`.
                    if (next == '.' && next2 != null && next2 !in '0'..'9') {
                        2
                    } else if (next == '?') {
                        if (next2 == '=') 3 else 2
                    } else {
                        1
                    }
                }

                '#' -> {
                    pos++
                    val first = codePointAt(pos)
                    if (!isIdStart(first) && first != '\\'.code) raise(pos, "Unexpected character '${codePointToString(first)}'")
                    return token(T.PRIVATE_NAME, readWord1(), start)
                }

                else -> {
                    raise(pos, "Unexpected character '${codePointToString(code)}'")
                }
            }
        pos += size
        return token(T.PUNCT, src.substring(start, pos), start)
    }

    private fun readWord1(): String {
        containsEsc = false
        val word = StringBuilder()
        var first = true
        while (pos < src.length) {
            val ch = src.codePointAt(pos)
            if (isIdChar(ch)) {
                word.appendCodePoint(ch)
                pos += Character.charCount(ch)
            } else if (ch == '\\'.code) {
                containsEsc = true
                val escapeStart = pos
                if (src.getOrNull(++pos) != 'u') invalidStringToken(pos, "Expecting Unicode escape sequence \\uXXXX")
                pos++
                val escaped = readCodePoint()
                if (!(if (first) isIdStart(escaped) else isIdChar(escaped))) invalidStringToken(escapeStart, "Invalid Unicode escape")
                word.appendCodePoint(escaped)
            } else {
                break
            }
            first = false
        }
        return word.toString()
    }

    /**
     * Digits in [radix], with `_` between them when [len] is null (a number, not an escape). Null
     * when there are none, or not exactly [len] of them.
     */
    private fun readInt(
        radix: Int,
        len: Int? = null,
        maybeLegacyOctal: Boolean = false,
    ): Int? {
        val allowSeparators = len == null
        val legacyOctal = maybeLegacyOctal && src.getOrNull(pos) == '0'
        val start = pos
        var total = 0L
        var lastWasSeparator = false
        var count = 0
        while (len == null || count < len) {
            val c = src.getOrNull(pos) ?: break
            if (allowSeparators && c == '_') {
                if (legacyOctal) raise(pos, "Numeric separator is not allowed in legacy octal numeric literals")
                if (lastWasSeparator) raise(pos, "Numeric separator must be exactly one underscore")
                if (count == 0) raise(pos, "Numeric separator is not allowed at the first of digits")
                lastWasSeparator = true
            } else {
                val digit =
                    when (c) {
                        in 'a'..'z' -> c - 'a' + 10
                        in 'A'..'Z' -> c - 'A' + 10
                        in '0'..'9' -> c - '0'
                        else -> Int.MAX_VALUE
                    }
                if (digit >= radix) break
                lastWasSeparator = false
                // Only an escape's value is used, and that is out of range long before this saturates.
                total = minOf(total * radix + digit, Int.MAX_VALUE.toLong())
            }
            count++
            pos++
        }
        if (allowSeparators && lastWasSeparator) raise(pos - 1, "Numeric separator is not allowed at the last of digits")
        if (pos == start || (len != null && pos - start != len)) return null
        return total.toInt()
    }

    private fun readRadixNumber(radix: Int): JsToken {
        val start = pos
        pos += 2
        if (readInt(radix) == null) raise(start + 2, "Expected number in radix $radix")
        if (src.getOrNull(pos) == 'n') {
            pos++
        } else if (isIdStart(codePointAt(pos))) {
            raise(pos, "Identifier directly after number")
        }
        return token(T.NUM, src.substring(start, pos), start)
    }

    private fun readNumber(startsWithDot: Boolean): JsToken {
        val start = pos
        if (!startsWithDot && readInt(10, null, true) == null) raise(start, "Invalid number")
        // `017` is a legacy octal number; `019` is a decimal one despite its leading zero.
        var octal = pos - start >= 2 && src[start] == '0'
        if (octal && strict) raise(start, "Invalid number")
        var next = src.getOrNull(pos)
        if (!octal && !startsWithDot && next == 'n') {
            pos++
            if (isIdStart(codePointAt(pos))) raise(pos, "Identifier directly after number")
            return token(T.NUM, src.substring(start, pos), start)
        }
        if (octal && src.substring(start, pos).any { it == '8' || it == '9' }) octal = false
        if (next == '.' && !octal) {
            pos++
            readInt(10)
            next = src.getOrNull(pos)
        }
        if ((next == 'e' || next == 'E') && !octal) {
            next = src.getOrNull(++pos)
            if (next == '+' || next == '-') pos++
            if (readInt(10) == null) raise(start, "Invalid number")
        }
        if (isIdStart(codePointAt(pos))) raise(pos, "Identifier directly after number")
        return token(T.NUM, src.substring(start, pos), start)
    }

    private fun readCodePoint(): Int {
        if (src.getOrNull(pos) != '{') return readHexChar(4)
        val codePos = ++pos
        val code = readHexChar(src.indexOf('}', pos) - pos)
        pos++
        if (code > 0x10FFFF) invalidStringToken(codePos, "Code point out of bounds")
        return code
    }

    private fun readHexChar(len: Int): Int {
        val codePos = pos
        return readInt(16, len) ?: invalidStringToken(codePos, "Bad character escape sequence")
    }

    private fun invalidStringToken(
        at: Int,
        message: String,
    ): Nothing {
        if (inTemplateElement) throw InvalidTemplateEscape()
        raise(at, message)
    }

    private fun readString(quote: Char): JsToken {
        val start = pos
        val out = StringBuilder()
        pos++
        while (true) {
            if (pos >= src.length) raise(start, "Unterminated string constant")
            val c = src[pos]
            if (c == quote) break
            if (c == '\\') {
                out.append(readEscapedChar(false))
            } else {
                // The two Unicode line separators may sit in a string; a line feed may not.
                if (c == '\n' || c == '\r') raise(start, "Unterminated string constant")
                out.append(c)
                pos++
            }
        }
        pos++
        return token(T.STRING, out.toString(), start)
    }

    private fun readEscapedChar(inTemplate: Boolean): String {
        val c = src.getOrNull(++pos) ?: return ""
        pos++
        return when (c) {
            'n' -> {
                "\n"
            }

            'r' -> {
                "\r"
            }

            't' -> {
                "\t"
            }

            'b' -> {
                "\b"
            }

            'v' -> {
                "\u000b"
            }

            'f' -> {
                "\u000c"
            }

            'x' -> {
                codePointToString(readHexChar(2))
            }

            'u' -> {
                codePointToString(readCodePoint())
            }

            '\r' -> {
                if (src.getOrNull(pos) == '\n') pos++
                ""
            }

            '8', '9' -> {
                if (strict) invalidStringToken(pos - 1, "Invalid escape sequence")
                if (inTemplate) invalidStringToken(pos - 1, "Invalid escape sequence in template string")
                c.toString()
            }

            in '0'..'7' -> {
                var octal = src.substring(pos - 1, minOf(pos + 2, src.length)).takeWhile { it in '0'..'7' }
                if (octal.toInt(8) > 255) octal = octal.dropLast(1)
                pos += octal.length - 1
                val following = src.getOrNull(pos)
                // `\0` alone is the NUL character everywhere; any other octal escape is legacy syntax.
                if ((octal != "0" || following == '8' || following == '9') && (strict || inTemplate)) {
                    invalidStringToken(
                        pos - 1 - octal.length,
                        if (inTemplate) "Octal literal in template string" else "Octal literal in strict mode",
                    )
                }
                octal.toInt(8).toChar().toString()
            }

            else -> {
                if (isNewLine(c)) "" else c.toString()
            }
        }
    }

    /** A regular expression whose opening slash is at [start]; [pos] is just past that slash. */
    private fun readRegexp(
        start: Int,
        newlineBefore: Boolean,
    ): JsToken {
        val bodyStart = pos
        var escaped = false
        var inClass = false
        while (true) {
            if (pos >= src.length || isNewLine(src[pos])) raise(bodyStart, "Unterminated regular expression")
            val c = src[pos]
            if (escaped) {
                escaped = false
            } else {
                if (c == '[') {
                    inClass = true
                } else if (c == ']' && inClass) {
                    inClass = false
                } else if (c == '/' && !inClass) {
                    break
                }
                escaped = c == '\\'
            }
            pos++
        }
        val pattern = src.substring(bodyStart, pos)
        pos++
        val flagsStart = pos
        val flags = readWord1()
        if (containsEsc) unexpected(flagsStart)
        for ((i, flag) in flags.withIndex()) {
            if (flag !in "dgimsuvy") raise(bodyStart, "Invalid regular expression flag")
            if (flags.indexOf(flag, i + 1) > -1) raise(bodyStart, "Duplicate regular expression flag")
        }
        if ('u' in flags && 'v' in flags) raise(bodyStart, "Invalid regular expression flag")
        RegExpValidator(pattern, flags) { raise(bodyStart, "Invalid regular expression: /$pattern/: $it") }.validate()
        return JsToken(T.REGEX, src.substring(start, pos), start, pos, newlineBefore)
    }
}

/** Disjunction branches of a regular expression: two groups may share a name only in separate alternatives. */
private class BranchId(
    val parent: BranchId?,
    base: BranchId?,
) {
    val base: BranchId = base ?: this

    fun separatedFrom(other: BranchId?): Boolean {
        var self: BranchId? = this
        while (self != null) {
            var alt = other
            while (alt != null) {
                if (self.base === alt.base && self !== alt) return true
                alt = alt.parent
            }
            self = self.parent
        }
        return false
    }

    fun sibling(): BranchId = BranchId(parent, base)
}

/**
 * The syntax of a regular expression body, as acorn validates it: the lenient web grammar without
 * flags, the strict one under `u`, and the set notation of character classes under `v`.
 */
private class RegExpValidator(
    private val source: String,
    flags: String,
    private val raise: (String) -> Nothing,
) {
    private val switchV = 'v' in flags
    private val switchU = switchV || 'u' in flags

    /** Whether `\k<name>` is a named reference; without `u` it is one only if the pattern names a group. */
    private var switchN = switchU
    private var pos = 0
    private var lastIntValue = 0
    private var lastStringValue = ""
    private var lastAssertionIsQuantifiable = false
    private var numCapturingParens = 0
    private var maxBackReference = 0
    private val groupNames = HashMap<String, MutableList<BranchId?>>()
    private val backReferenceNames = ArrayList<String>()
    private var branchId: BranchId? = null

    fun validate() {
        pattern()
        if (!switchN && groupNames.isNotEmpty()) {
            switchN = true
            pattern()
        }
    }

    /** The code point at [i] under `u` (or [forceU]), else the code unit; -1 past the end. */
    private fun at(
        i: Int,
        forceU: Boolean = false,
    ): Int {
        if (i >= source.length) return -1
        val c = source[i]
        if (!(forceU || switchU) || !c.isHighSurrogate() || i + 1 >= source.length || !source[i + 1].isLowSurrogate()) return c.code
        return Character.toCodePoint(c, source[i + 1])
    }

    private fun nextIndex(
        i: Int,
        forceU: Boolean = false,
    ): Int {
        if (i >= source.length) return source.length
        return if (at(i, forceU) > 0xFFFF) i + 2 else i + 1
    }

    private fun current(forceU: Boolean = false): Int = at(pos, forceU)

    private fun lookahead(): Int = at(nextIndex(pos))

    private fun advance(forceU: Boolean = false) {
        pos = nextIndex(pos, forceU)
    }

    private fun eat(ch: Char): Boolean {
        if (current() != ch.code) return false
        advance()
        return true
    }

    private fun eatChars(chars: String): Boolean {
        if (!source.startsWith(chars, pos)) return false
        pos += chars.length
        return true
    }

    private fun pattern() {
        pos = 0
        lastIntValue = 0
        lastStringValue = ""
        lastAssertionIsQuantifiable = false
        numCapturingParens = 0
        maxBackReference = 0
        groupNames.clear()
        backReferenceNames.clear()
        branchId = null
        disjunction()
        if (pos != source.length) {
            if (eat(')')) raise("Unmatched ')'")
            if (eat(']') || eat('}')) raise("Lone quantifier brackets")
        }
        if (maxBackReference > numCapturingParens) raise("Invalid escape")
        for (name in backReferenceNames) if (name !in groupNames) raise("Invalid named capture referenced")
    }

    private fun disjunction() {
        val branch = BranchId(branchId, null)
        branchId = branch
        alternative()
        while (eat('|')) {
            branchId = branchId!!.sibling()
            alternative()
        }
        branchId = branch.parent
        if (eatQuantifier(noError = true)) raise("Nothing to repeat")
        if (eat('{')) raise("Lone quantifier brackets")
    }

    private fun alternative() {
        while (pos < source.length && eatTerm()) {
            // Each term is consumed by the test.
        }
    }

    private fun eatTerm(): Boolean {
        if (eatAssertion()) {
            // Only a lookahead may be repeated, and only without `u`.
            if (lastAssertionIsQuantifiable && eatQuantifier() && switchU) raise("Invalid quantifier")
            return true
        }
        if (if (switchU) eatAtom() else eatExtendedAtom()) {
            eatQuantifier()
            return true
        }
        return false
    }

    private fun eatAssertion(): Boolean {
        val start = pos
        lastAssertionIsQuantifiable = false
        if (eat('^') || eat('$')) return true
        if (eat('\\')) {
            if (eat('B') || eat('b')) return true
            pos = start
        }
        if (eat('(') && eat('?')) {
            val lookbehind = eat('<')
            if (eat('=') || eat('!')) {
                disjunction()
                if (!eat(')')) raise("Unterminated group")
                lastAssertionIsQuantifiable = !lookbehind
                return true
            }
        }
        pos = start
        return false
    }

    private fun eatQuantifier(noError: Boolean = false): Boolean {
        if (eat('*') || eat('+') || eat('?') || eatBracedQuantifier(noError)) {
            eat('?')
            return true
        }
        return false
    }

    private fun eatBracedQuantifier(noError: Boolean): Boolean {
        val start = pos
        if (eat('{')) {
            if (eatDecimalDigits()) {
                val min = lastIntValue
                var max = -1
                if (eat(',') && eatDecimalDigits()) max = lastIntValue
                if (eat('}')) {
                    if (max != -1 && max < min && !noError) raise("numbers out of order in {} quantifier")
                    return true
                }
            }
            if (switchU && !noError) raise("Incomplete quantifier")
            pos = start
        }
        return false
    }

    private fun eatAtom(): Boolean =
        eatPatternCharacters() ||
            eat('.') ||
            eatReverseSolidusAtomEscape() ||
            eatCharacterClass() ||
            eatUncapturingGroup() ||
            eatCapturingGroup()

    private fun eatReverseSolidusAtomEscape(): Boolean {
        val start = pos
        if (eat('\\')) {
            if (eatAtomEscape()) return true
            pos = start
        }
        return false
    }

    private fun eatUncapturingGroup(): Boolean {
        val start = pos
        if (eat('(')) {
            if (eat('?')) {
                // `(?ims-ims:...)` switches flags on and off for the group.
                val add = eatModifiers()
                val hasHyphen = eat('-')
                if (add.isNotEmpty() || hasHyphen) {
                    if (add.toSet().size != add.length) raise("Duplicate regular expression modifiers")
                    if (hasHyphen) {
                        val remove = eatModifiers()
                        if (add.isEmpty() && remove.isEmpty() && current() == ':'.code) raise("Invalid regular expression modifiers")
                        if (remove.toSet().size != remove.length || remove.any { it in add }) {
                            raise("Duplicate regular expression modifiers")
                        }
                    }
                }
                if (eat(':')) {
                    disjunction()
                    if (eat(')')) return true
                    raise("Unterminated group")
                }
            }
            pos = start
        }
        return false
    }

    private fun eatCapturingGroup(): Boolean {
        if (!eat('(')) return false
        if (eat('?')) {
            if (!eatGroupName()) raise("Invalid group")
            val known = groupNames.getOrPut(lastStringValue) { ArrayList() }
            if (known.any { it?.separatedFrom(branchId) != true }) raise("Duplicate capture group name")
            known += branchId
        }
        disjunction()
        if (!eat(')')) raise("Unterminated group")
        numCapturingParens++
        return true
    }

    private fun eatModifiers(): String {
        val start = pos
        while (current() == 'i'.code || current() == 'm'.code || current() == 's'.code) advance()
        return source.substring(start, pos)
    }

    private fun eatExtendedAtom(): Boolean =
        eat('.') ||
            eatReverseSolidusAtomEscape() ||
            eatCharacterClass() ||
            eatUncapturingGroup() ||
            eatCapturingGroup() ||
            eatInvalidBracedQuantifier() ||
            eatExtendedPatternCharacter()

    private fun eatInvalidBracedQuantifier(): Boolean {
        if (eatBracedQuantifier(noError = true)) raise("Nothing to repeat")
        return false
    }

    private fun isSyntaxCharacter(ch: Int): Boolean = ch >= 0 && ch.toChar() in "$()*+.?[\\]^{|}" && ch <= 0x7D

    private fun eatSyntaxCharacter(): Boolean {
        val ch = current()
        if (!isSyntaxCharacter(ch)) return false
        lastIntValue = ch
        advance()
        return true
    }

    private fun eatPatternCharacters(): Boolean {
        val start = pos
        while (current() != -1 && !isSyntaxCharacter(current())) advance()
        return pos != start
    }

    private fun eatExtendedPatternCharacter(): Boolean {
        val ch = current()
        if (ch == -1 || (ch <= 0x7C && ch.toChar() in "$()*+.?[^|")) return false
        advance()
        return true
    }

    private fun eatGroupName(): Boolean {
        lastStringValue = ""
        if (eat('<')) {
            if (eatRegExpIdentifierName() && eat('>')) return true
            raise("Invalid capture group name")
        }
        return false
    }

    private fun eatRegExpIdentifierName(): Boolean {
        val name = StringBuilder()
        if (!eatRegExpIdentifierChar(start = true)) {
            lastStringValue = ""
            return false
        }
        name.appendCodePoint(lastIntValue)
        while (eatRegExpIdentifierChar(start = false)) name.appendCodePoint(lastIntValue)
        lastStringValue = name.toString()
        return true
    }

    private fun eatRegExpIdentifierChar(start: Boolean): Boolean {
        val begin = pos
        var ch = current(forceU = true)
        advance(forceU = true)
        if (ch == '\\'.code && eatRegExpUnicodeEscapeSequence(forceU = true)) ch = lastIntValue
        if (if (start) isIdStart(ch) else isIdChar(ch)) {
            lastIntValue = ch
            return true
        }
        pos = begin
        return false
    }

    private fun eatAtomEscape(): Boolean {
        if (eatBackReference() || eatCharacterClassEscape() != CHAR_SET_NONE || eatCharacterEscape() || (switchN && eatKGroupName())) {
            return true
        }
        if (switchU) {
            if (current() == 'c'.code) raise("Invalid unicode escape")
            raise("Invalid escape")
        }
        return false
    }

    private fun eatBackReference(): Boolean {
        val start = pos
        if (eatDecimalEscape()) {
            val n = lastIntValue
            if (switchU) {
                // Checked against the number of groups once the whole pattern has been read.
                if (n > maxBackReference) maxBackReference = n
                return true
            }
            if (n <= numCapturingParens) return true
            pos = start
        }
        return false
    }

    private fun eatKGroupName(): Boolean {
        if (eat('k')) {
            if (eatGroupName()) {
                backReferenceNames += lastStringValue
                return true
            }
            raise("Invalid named reference")
        }
        return false
    }

    private fun eatCharacterEscape(): Boolean =
        eatControlEscape() ||
            eatCControlLetter() ||
            eatZero() ||
            eatHexEscapeSequence() ||
            eatRegExpUnicodeEscapeSequence(forceU = false) ||
            (!switchU && eatLegacyOctalEscapeSequence()) ||
            eatIdentityEscape()

    private fun eatCControlLetter(): Boolean {
        val start = pos
        if (eat('c')) {
            val ch = current()
            if (ch in 'A'.code..'Z'.code || ch in 'a'.code..'z'.code) {
                lastIntValue = ch % 0x20
                advance()
                return true
            }
            pos = start
        }
        return false
    }

    private fun eatZero(): Boolean {
        if (current() != '0'.code || isDecimalDigit(lookahead())) return false
        lastIntValue = 0
        advance()
        return true
    }

    private fun eatControlEscape(): Boolean {
        lastIntValue =
            when (current()) {
                't'.code -> 0x09
                'n'.code -> 0x0A
                'v'.code -> 0x0B
                'f'.code -> 0x0C
                'r'.code -> 0x0D
                else -> return false
            }
        advance()
        return true
    }

    private fun eatRegExpUnicodeEscapeSequence(forceU: Boolean): Boolean {
        val start = pos
        val unicode = forceU || switchU
        if (eat('u')) {
            if (eatFixedHexDigits(4)) {
                val lead = lastIntValue
                if (unicode && lead in 0xD800..0xDBFF) {
                    // A surrogate pair written as two escapes is one code point.
                    val leadEnd = pos
                    if (eat('\\') && eat('u') && eatFixedHexDigits(4)) {
                        val trail = lastIntValue
                        if (trail in 0xDC00..0xDFFF) {
                            lastIntValue = (lead - 0xD800) * 0x400 + (trail - 0xDC00) + 0x10000
                            return true
                        }
                    }
                    pos = leadEnd
                    lastIntValue = lead
                }
                return true
            }
            if (unicode && eat('{') && eatHexDigits() && eat('}') && lastIntValue <= 0x10FFFF) return true
            if (unicode) raise("Invalid unicode escape")
            pos = start
        }
        return false
    }

    private fun eatIdentityEscape(): Boolean {
        if (switchU) {
            if (eatSyntaxCharacter()) return true
            if (eat('/')) {
                lastIntValue = '/'.code
                return true
            }
            return false
        }
        val ch = current()
        if (ch != 'c'.code && (!switchN || ch != 'k'.code)) {
            lastIntValue = ch
            advance()
            return true
        }
        return false
    }

    private fun eatDecimalEscape(): Boolean {
        lastIntValue = 0
        if (current() !in '1'.code..'9'.code) return false
        do {
            lastIntValue = saturate(10L * lastIntValue + (current() - '0'.code))
            advance()
        } while (isDecimalDigit(current()))
        return true
    }

    private fun eatCharacterClassEscape(): Int {
        val ch = current()
        if (ch >= 0 && ch.toChar() in "dDsSwW" && ch < 0x80) {
            lastIntValue = -1
            advance()
            return CHAR_SET_OK
        }
        val negate = ch == 'P'.code
        if (switchU && (negate || ch == 'p'.code)) {
            lastIntValue = -1
            advance()
            if (eat('{')) {
                val result = eatUnicodePropertyValueExpression()
                if (result != CHAR_SET_NONE && eat('}')) {
                    if (negate && result == CHAR_SET_STRING) raise("Invalid property name")
                    return result
                }
            }
            raise("Invalid property name")
        }
        return CHAR_SET_NONE
    }

    private fun eatUnicodePropertyValueExpression(): Int {
        val start = pos
        if (eatUnicodePropertyWord(digits = false) && eat('=')) {
            val name = lastStringValue
            if (eatUnicodePropertyWord(digits = true)) {
                val values = UNICODE_PROPERTY_VALUES[name] ?: raise("Invalid property name")
                if (lastStringValue !in values) raise("Invalid property value")
                return CHAR_SET_OK
            }
        }
        pos = start
        if (eatUnicodePropertyWord(digits = true)) {
            if (lastStringValue in UNICODE_BINARY) return CHAR_SET_OK
            if (switchV && lastStringValue in UNICODE_BINARY_OF_STRINGS) return CHAR_SET_STRING
            raise("Invalid property name")
        }
        return CHAR_SET_NONE
    }

    private fun eatUnicodePropertyWord(digits: Boolean): Boolean {
        val start = pos
        while (true) {
            val ch = current()
            val letter = ch in 'A'.code..'Z'.code || ch in 'a'.code..'z'.code || ch == '_'.code
            if (!letter && !(digits && isDecimalDigit(ch))) break
            advance()
        }
        lastStringValue = source.substring(start, pos)
        return pos != start
    }

    private fun eatCharacterClass(): Boolean {
        if (!eat('[')) return false
        val negate = eat('^')
        val result = classContents()
        if (!eat(']')) raise("Unterminated character class")
        if (negate && result == CHAR_SET_STRING) raise("Negated character class may contain strings")
        return true
    }

    private fun classContents(): Int {
        if (current() == ']'.code) return CHAR_SET_OK
        if (switchV) return classSetExpression()
        nonEmptyClassRanges()
        return CHAR_SET_OK
    }

    private fun nonEmptyClassRanges() {
        while (eatClassAtom()) {
            val left = lastIntValue
            if (eat('-') && eatClassAtom()) {
                val right = lastIntValue
                if (switchU && (left == -1 || right == -1)) raise("Invalid character class")
                if (left != -1 && right != -1 && left > right) raise("Range out of order in character class")
            }
        }
    }

    private fun eatClassAtom(): Boolean {
        val start = pos
        if (eat('\\')) {
            if (eatClassEscape()) return true
            if (switchU) {
                val ch = current()
                if (ch == 'c'.code || ch in '0'.code..'7'.code) raise("Invalid class escape")
                raise("Invalid escape")
            }
            pos = start
        }
        val ch = current()
        if (ch == ']'.code) return false
        lastIntValue = ch
        advance()
        return true
    }

    private fun eatClassEscape(): Boolean {
        val start = pos
        if (eat('b')) {
            lastIntValue = 0x08
            return true
        }
        if (switchU && eat('-')) {
            lastIntValue = '-'.code
            return true
        }
        if (!switchU && eat('c')) {
            val ch = current()
            if (isDecimalDigit(ch) || ch == '_'.code) {
                lastIntValue = ch % 0x20
                advance()
                return true
            }
            pos = start
        }
        return eatCharacterClassEscape() != CHAR_SET_NONE || eatCharacterEscape()
    }

    /** The contents of a class under `v`: a union, an intersection (`&&`) or a subtraction (`--`). */
    private fun classSetExpression(): Int {
        var result = CHAR_SET_OK
        if (!eatClassSetRange()) {
            val operand = eatClassSetOperand()
            if (operand == CHAR_SET_NONE) raise("Invalid character in character class")
            if (operand == CHAR_SET_STRING) result = CHAR_SET_STRING
            val start = pos
            while (eatChars("&&")) {
                val next = if (current() != '&'.code) eatClassSetOperand() else CHAR_SET_NONE
                if (next == CHAR_SET_NONE) raise("Invalid character in character class")
                if (next != CHAR_SET_STRING) result = CHAR_SET_OK
            }
            if (start != pos) return result
            while (eatChars("--")) {
                if (eatClassSetOperand() == CHAR_SET_NONE) raise("Invalid character in character class")
            }
            if (start != pos) return result
        }
        while (true) {
            if (eatClassSetRange()) continue
            val operand = eatClassSetOperand()
            if (operand == CHAR_SET_NONE) return result
            if (operand == CHAR_SET_STRING) result = CHAR_SET_STRING
        }
    }

    private fun eatClassSetRange(): Boolean {
        val start = pos
        if (eatClassSetCharacter()) {
            val left = lastIntValue
            if (eat('-') && eatClassSetCharacter()) {
                val right = lastIntValue
                if (left != -1 && right != -1 && left > right) raise("Range out of order in character class")
                return true
            }
            pos = start
        }
        return false
    }

    private fun eatClassSetOperand(): Int {
        if (eatClassSetCharacter()) return CHAR_SET_OK
        val strings = eatClassStringDisjunction()
        return if (strings != CHAR_SET_NONE) strings else eatNestedClass()
    }

    private fun eatNestedClass(): Int {
        val start = pos
        if (eat('[')) {
            val negate = eat('^')
            val result = classContents()
            if (eat(']')) {
                if (negate && result == CHAR_SET_STRING) raise("Negated character class may contain strings")
                return result
            }
            pos = start
        }
        if (eat('\\')) {
            val result = eatCharacterClassEscape()
            if (result != CHAR_SET_NONE) return result
            pos = start
        }
        return CHAR_SET_NONE
    }

    /** `\q{abc|def}`, a set of strings. */
    private fun eatClassStringDisjunction(): Int {
        val start = pos
        if (eatChars("\\q")) {
            if (!eat('{')) raise("Invalid escape")
            var result = classString()
            while (eat('|')) if (classString() == CHAR_SET_STRING) result = CHAR_SET_STRING
            if (eat('}')) return result
            pos = start
        }
        return CHAR_SET_NONE
    }

    private fun classString(): Int {
        var count = 0
        while (eatClassSetCharacter()) count++
        return if (count == 1) CHAR_SET_OK else CHAR_SET_STRING
    }

    private fun eatClassSetCharacter(): Boolean {
        val start = pos
        if (eat('\\')) {
            if (eatCharacterEscape()) return true
            val ch = current()
            if (ch in 0..0x7E && ch.toChar() in "!#%&,-:;<=>@`~") {
                lastIntValue = ch
                advance()
                return true
            }
            if (eat('b')) {
                lastIntValue = 0x08
                return true
            }
            pos = start
            return false
        }
        val ch = current()
        if (ch < 0) return false
        val ascii = if (ch < 0x80) ch.toChar() else ' '
        // A doubled punctuator is reserved for operators, and the brackets structure the class.
        if (ch == lookahead() && ascii in "!#$%&*+,.:;<=>?@^`~") return false
        if (ascii in "()-/[\\]{|}") return false
        advance()
        lastIntValue = ch
        return true
    }

    private fun eatHexEscapeSequence(): Boolean {
        val start = pos
        if (eat('x')) {
            if (eatFixedHexDigits(2)) return true
            if (switchU) raise("Invalid escape")
            pos = start
        }
        return false
    }

    private fun eatDecimalDigits(): Boolean {
        val start = pos
        lastIntValue = 0
        while (isDecimalDigit(current())) {
            lastIntValue = saturate(10L * lastIntValue + (current() - '0'.code))
            advance()
        }
        return pos != start
    }

    private fun eatHexDigits(): Boolean {
        val start = pos
        lastIntValue = 0
        while (hexValue(current()) >= 0) {
            lastIntValue = saturate(16L * lastIntValue + hexValue(current()))
            advance()
        }
        return pos != start
    }

    private fun eatFixedHexDigits(length: Int): Boolean {
        val start = pos
        lastIntValue = 0
        repeat(length) {
            val digit = hexValue(current())
            if (digit < 0) {
                pos = start
                return false
            }
            lastIntValue = 16 * lastIntValue + digit
            advance()
        }
        return true
    }

    /** `\1` to `\377` without `u`: an octal escape when it is not a back reference. */
    private fun eatLegacyOctalEscapeSequence(): Boolean {
        if (!eatOctalDigit()) return false
        val n1 = lastIntValue
        if (eatOctalDigit()) {
            val n2 = lastIntValue
            lastIntValue = if (n1 <= 3 && eatOctalDigit()) n1 * 64 + n2 * 8 + lastIntValue else n1 * 8 + n2
        } else {
            lastIntValue = n1
        }
        return true
    }

    private fun eatOctalDigit(): Boolean {
        val ch = current()
        if (ch in '0'.code..'7'.code) {
            lastIntValue = ch - '0'.code
            advance()
            return true
        }
        lastIntValue = 0
        return false
    }

    private fun isDecimalDigit(ch: Int): Boolean = ch in '0'.code..'9'.code

    private fun hexValue(ch: Int): Int =
        when (ch) {
            in '0'.code..'9'.code -> ch - '0'.code
            in 'a'.code..'f'.code -> ch - 'a'.code + 10
            in 'A'.code..'F'.code -> ch - 'A'.code + 10
            else -> -1
        }

    /** Numbers in a pattern are only compared, so one too large to hold is as good as the largest. */
    private fun saturate(value: Long): Int = minOf(value, Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        /** What a class escape or a class under `v` can match: nothing parsed, characters, or strings too. */
        const val CHAR_SET_NONE = 0
        const val CHAR_SET_OK = 1
        const val CHAR_SET_STRING = 2
    }
}

private fun isNewLine(c: Char): Boolean = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

private fun isJsSpace(c: Char): Boolean =
    c == ' ' ||
        c in '\t'..'\r' ||
        c == '\u00a0' ||
        c == '\u1680' ||
        c in '\u2000'..'\u200a' ||
        c == '\u2028' ||
        c == '\u2029' ||
        c == '\u202f' ||
        c == '\u205f' ||
        c == '\u3000' ||
        c == '\ufeff'

private fun isIdStart(c: Int): Boolean =
    when {
        c < 0 -> false
        c < 0x80 -> c == '$'.code || c == '_'.code || c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code
        else -> Character.isUnicodeIdentifierStart(c)
    }

/** The two joiners are allowed inside a name; the other invisible format characters are not. */
private fun isIdChar(c: Int): Boolean =
    when {
        c < 0 -> false
        c < 0x80 -> isIdStart(c) || c in '0'.code..'9'.code
        else -> c == 0x200C || c == 0x200D || (Character.isUnicodeIdentifierPart(c) && !Character.isIdentifierIgnorable(c))
    }

private fun codePointToString(c: Int): String = if (Character.isValidCodePoint(c)) String(Character.toChars(c)) else ""

private val UNICODE_BINARY: Set<String> = "$UNICODE_BINARY_PROPERTIES $UNICODE_GENERAL_CATEGORIES".split(' ').toSet()

private val UNICODE_BINARY_OF_STRINGS: Set<String> = UNICODE_BINARY_PROPERTIES_OF_STRINGS.split(' ').toSet()

/** The properties written `\p{Name=Value}`, each with the values it takes. */
private val UNICODE_PROPERTY_VALUES: Map<String, Set<String>> =
    run {
        val categories = UNICODE_GENERAL_CATEGORIES.split(' ').toSet()
        val scripts = UNICODE_SCRIPTS.split(' ').toSet()
        mapOf(
            "General_Category" to categories,
            "gc" to categories,
            "Script" to scripts,
            "sc" to scripts,
            "Script_Extensions" to scripts,
            "scx" to scripts,
        )
    }

// The names ECMAScript accepts in `\p{...}`, as its specification tables list them.

private const val UNICODE_BINARY_PROPERTIES =
    "ASCII ASCII_Hex_Digit AHex Alphabetic Alpha Any Assigned Bidi_Control Bidi_C Bidi_Mirrored Bidi_M " +
        "Case_Ignorable CI Cased Changes_When_Casefolded CWCF Changes_When_Casemapped CWCM Changes_When_Lowercased CWL " +
        "Changes_When_NFKC_Casefolded CWKCF Changes_When_Titlecased CWT Changes_When_Uppercased CWU Dash " +
        "Default_Ignorable_Code_Point DI Deprecated Dep Diacritic Dia Emoji Emoji_Component Emoji_Modifier " +
        "Emoji_Modifier_Base Emoji_Presentation Extender Ext Grapheme_Base Gr_Base Grapheme_Extend Gr_Ext Hex_Digit Hex " +
        "IDS_Binary_Operator IDSB IDS_Trinary_Operator IDST ID_Continue IDC ID_Start IDS Ideographic Ideo Join_Control " +
        "Join_C Logical_Order_Exception LOE Lowercase Lower Math Noncharacter_Code_Point NChar Pattern_Syntax Pat_Syn " +
        "Pattern_White_Space Pat_WS Quotation_Mark QMark Radical Regional_Indicator RI Sentence_Terminal STerm " +
        "Soft_Dotted SD Terminal_Punctuation Term Unified_Ideograph UIdeo Uppercase Upper Variation_Selector VS " +
        "White_Space space XID_Continue XIDC XID_Start XIDS Extended_Pictographic EBase EComp EMod EPres ExtPict"

private const val UNICODE_BINARY_PROPERTIES_OF_STRINGS =
    "Basic_Emoji Emoji_Keycap_Sequence RGI_Emoji_Modifier_Sequence RGI_Emoji_Flag_Sequence RGI_Emoji_Tag_Sequence " +
        "RGI_Emoji_ZWJ_Sequence RGI_Emoji"

private const val UNICODE_GENERAL_CATEGORIES =
    "Cased_Letter LC Close_Punctuation Pe Connector_Punctuation Pc Control Cc cntrl Currency_Symbol Sc " +
        "Dash_Punctuation Pd Decimal_Number Nd digit Enclosing_Mark Me Final_Punctuation Pf Format Cf " +
        "Initial_Punctuation Pi Letter L Letter_Number Nl Line_Separator Zl Lowercase_Letter Ll Mark M Combining_Mark " +
        "Math_Symbol Sm Modifier_Letter Lm Modifier_Symbol Sk Nonspacing_Mark Mn Number N Open_Punctuation Ps Other C " +
        "Other_Letter Lo Other_Number No Other_Punctuation Po Other_Symbol So Paragraph_Separator Zp Private_Use Co " +
        "Punctuation P punct Separator Z Space_Separator Zs Spacing_Mark Mc Surrogate Cs Symbol S Titlecase_Letter Lt " +
        "Unassigned Cn Uppercase_Letter Lu"

private const val UNICODE_SCRIPTS =
    "Adlam Adlm Ahom Anatolian_Hieroglyphs Hluw Arabic Arab Armenian Armn Avestan Avst Balinese Bali Bamum Bamu " +
        "Bassa_Vah Bass Batak Batk Bengali Beng Bhaiksuki Bhks Bopomofo Bopo Brahmi Brah Braille Brai Buginese Bugi " +
        "Buhid Buhd Canadian_Aboriginal Cans Carian Cari Caucasian_Albanian Aghb Chakma Cakm Cham Cham Cherokee Cher " +
        "Common Zyyy Coptic Copt Qaac Cuneiform Xsux Cypriot Cprt Cyrillic Cyrl Deseret Dsrt Devanagari Deva Duployan " +
        "Dupl Egyptian_Hieroglyphs Egyp Elbasan Elba Ethiopic Ethi Georgian Geor Glagolitic Glag Gothic Goth Grantha " +
        "Gran Greek Grek Gujarati Gujr Gurmukhi Guru Han Hani Hangul Hang Hanunoo Hano Hatran Hatr Hebrew Hebr Hiragana " +
        "Hira Imperial_Aramaic Armi Inherited Zinh Qaai Inscriptional_Pahlavi Phli Inscriptional_Parthian Prti Javanese " +
        "Java Kaithi Kthi Kannada Knda Katakana Kana Kayah_Li Kali Kharoshthi Khar Khmer Khmr Khojki Khoj Khudawadi " +
        "Sind Lao Laoo Latin Latn Lepcha Lepc Limbu Limb Linear_A Lina Linear_B Linb Lisu Lisu Lycian Lyci Lydian Lydi " +
        "Mahajani Mahj Malayalam Mlym Mandaic Mand Manichaean Mani Marchen Marc Masaram_Gondi Gonm Meetei_Mayek Mtei " +
        "Mende_Kikakui Mend Meroitic_Cursive Merc Meroitic_Hieroglyphs Mero Miao Plrd Modi Mongolian Mong Mro Mroo " +
        "Multani Mult Myanmar Mymr Nabataean Nbat New_Tai_Lue Talu Newa Newa Nko Nkoo Nushu Nshu Ogham Ogam Ol_Chiki " +
        "Olck Old_Hungarian Hung Old_Italic Ital Old_North_Arabian Narb Old_Permic Perm Old_Persian Xpeo " +
        "Old_South_Arabian Sarb Old_Turkic Orkh Oriya Orya Osage Osge Osmanya Osma Pahawh_Hmong Hmng Palmyrene Palm " +
        "Pau_Cin_Hau Pauc Phags_Pa Phag Phoenician Phnx Psalter_Pahlavi Phlp Rejang Rjng Runic Runr Samaritan Samr " +
        "Saurashtra Saur Sharada Shrd Shavian Shaw Siddham Sidd SignWriting Sgnw Sinhala Sinh Sora_Sompeng Sora Soyombo " +
        "Soyo Sundanese Sund Syloti_Nagri Sylo Syriac Syrc Tagalog Tglg Tagbanwa Tagb Tai_Le Tale Tai_Tham Lana " +
        "Tai_Viet Tavt Takri Takr Tamil Taml Tangut Tang Telugu Telu Thaana Thaa Thai Thai Tibetan Tibt Tifinagh Tfng " +
        "Tirhuta Tirh Ugaritic Ugar Vai Vaii Warang_Citi Wara Yi Yiii Zanabazar_Square Zanb Dogra Dogr Gunjala_Gondi " +
        "Gong Hanifi_Rohingya Rohg Makasar Maka Medefaidrin Medf Old_Sogdian Sogo Sogdian Sogd Elymaic Elym Nandinagari " +
        "Nand Nyiakeng_Puachue_Hmong Hmnp Wancho Wcho Chorasmian Chrs Diak Dives_Akuru Khitan_Small_Script Kits Yezi " +
        "Yezidi Cypro_Minoan Cpmn Old_Uyghur Ougr Tangsa Tnsa Toto Vithkuqi Vith Berf Beria_Erfe Gara Garay Gukh " +
        "Gurung_Khema Hrkt Katakana_Or_Hiragana Kawi Kirat_Rai Krai Nag_Mundari Nagm Ol_Onal Onao Sidetic Sidt Sunu " +
        "Sunuwar Tai_Yo Tayo Todhri Todr Tolong_Siki Tols Tulu_Tigalari Tutg Unknown Zzzz"
