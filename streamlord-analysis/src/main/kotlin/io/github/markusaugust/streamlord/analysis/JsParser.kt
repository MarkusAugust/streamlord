package io.github.markusaugust.streamlord.analysis

/**
 * A syntax check for JavaScript, enough to judge a Datastar expression: statements separated by
 * semicolons or line breaks, the full expression grammar including arrow functions, template
 * literals, regular expressions, optional chaining, spread, destructuring targets, `async` and
 * `await`. It builds no tree; it stops at the first error and says where, in the manner of
 * acorn, so that the VS Code extension (which uses acorn) and the IntelliJ plugin agree.
 *
 * `return` and `await` are allowed at the top level, as in an event handler.
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
        Parser(source).parseProgram()
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
    val value: String,
    val start: Int,
    val end: Int,
    val newlineBefore: Boolean,
    /** For templates: the `${...}` expressions inside, as (start, end) offsets. */
    val templateParts: List<IntRange> = emptyList(),
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

/** Keywords after which a `/` starts a regular expression rather than a division. */
private val REGEX_AFTER_KEYWORD =
    setOf(
        "return",
        "typeof",
        "case",
        "do",
        "else",
        "in",
        "of",
        "instanceof",
        "new",
        "delete",
        "void",
        "throw",
        "await",
        "yield",
    )

private val PUNCTUATORS =
    listOf(
        ">>>=",
        "...",
        "===",
        "!==",
        "**=",
        "<<=",
        ">>=",
        ">>>",
        "&&=",
        "||=",
        "??=",
        "=>",
        "==",
        "!=",
        "<=",
        ">=",
        "&&",
        "||",
        "??",
        "?.",
        "++",
        "--",
        "+=",
        "-=",
        "*=",
        "/=",
        "%=",
        "&=",
        "|=",
        "^=",
        "<<",
        ">>",
        "**",
        "{",
        "}",
        "(",
        ")",
        "[",
        "]",
        ";",
        ",",
        "<",
        ">",
        "+",
        "-",
        "*",
        "/",
        "%",
        "&",
        "|",
        "^",
        "!",
        "~",
        "?",
        ":",
        "=",
        ".",
        "@",
        "#",
    )

private class Parser(
    private val src: String,
) {
    private var pos = 0
    private var tok: JsToken = JsToken(T.EOF, "", 0, 0, false)
    private var last: JsToken? = null
    private var inFunction = 0
    private var inGenerator = 0

    fun parseProgram() {
        next()
        while (tok.type != T.EOF) parseStatement()
    }

    // ---- tokens --------------------------------------------------------------------------------

    private fun raise(
        message: String,
        at: Int = tok.start,
        end: Int = maxOf(tok.end, at + 1),
    ): Nothing = throw JsSyntaxError(message, at, end)

    private fun unexpected(t: JsToken = tok): Nothing =
        when {
            t.type == T.EOF -> raise("Unexpected end of input", t.start, t.start + 1)
            t.type == T.KEYWORD -> raise("Unexpected keyword '${t.value}'", t.start, t.end)
            else -> raise("Unexpected token", t.start, t.end)
        }

    private fun regexAllowed(): Boolean {
        val l = last ?: return true
        return when (l.type) {
            T.NAME, T.NUM, T.STRING, T.TEMPLATE, T.REGEX, T.PRIVATE_NAME -> false
            T.KEYWORD -> l.value in REGEX_AFTER_KEYWORD
            T.PUNCT -> l.value != ")" && l.value != "]" && l.value != "}"
            T.EOF -> true
        }
    }

    private fun next() {
        last = tok
        tok = readToken()
    }

    private fun skipSpace(): Boolean {
        var newline = false
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029' -> {
                    newline = true
                    pos++
                }

                c.isWhitespace() || c == '\uFEFF' -> {
                    pos++
                }

                c == '/' && src.getOrNull(pos + 1) == '/' -> {
                    while (pos < src.length && src[pos] != '\n' && src[pos] != '\r') pos++
                }

                c == '/' && src.getOrNull(pos + 1) == '*' -> {
                    val close = src.indexOf("*/", pos + 2)
                    if (close < 0) throw JsSyntaxError("Unterminated comment", pos, pos + 2)
                    if (src.substring(pos, close).any { it == '\n' || it == '\r' }) newline = true
                    pos = close + 2
                }

                else -> {
                    return newline
                }
            }
        }
        return newline
    }

    private fun readToken(): JsToken {
        val newline = skipSpace()
        if (pos >= src.length) return JsToken(T.EOF, "", src.length, src.length, newline)
        val start = pos
        val c = src[pos]
        return when {
            c.isJsIdentStart() -> {
                val word = readWord()
                JsToken(if (word in KEYWORDS) T.KEYWORD else T.NAME, word, start, pos, newline)
            }

            c == '#' && src.getOrNull(pos + 1)?.isJsIdentStart() == true -> {
                pos++
                val word = readWord()
                JsToken(T.PRIVATE_NAME, "#$word", start, pos, newline)
            }

            c in '0'..'9' || (c == '.' && src.getOrNull(pos + 1) in '0'..'9') -> {
                readNumber(start, newline)
            }

            c == '"' || c == '\'' -> {
                readString(c, start, newline)
            }

            c == '`' -> {
                readTemplate(start, newline)
            }

            c == '/' && regexAllowed() -> {
                readRegex(start, newline)
            }

            else -> {
                val p = PUNCTUATORS.firstOrNull { src.startsWith(it, pos) } ?: raise("Unexpected character '$c'", pos, pos + 1)
                // `?.` followed by a digit is a conditional with a decimal: `a?.5:1`.
                val value = if (p == "?." && src.getOrNull(pos + 2) in '0'..'9') "?" else p
                pos += value.length
                JsToken(T.PUNCT, value, start, pos, newline)
            }
        }
    }

    private fun readWord(): String {
        val s = pos
        while (pos < src.length && (src[pos].isJsIdentPart())) pos++
        return src.substring(s, pos)
    }

    private fun readNumber(
        start: Int,
        newline: Boolean,
    ): JsToken {
        fun digits(ok: (Char) -> Boolean) {
            while (pos < src.length && (ok(src[pos]) || src[pos] == '_')) pos++
        }
        if (src[pos] == '0' && src.getOrNull(pos + 1)?.lowercaseChar() in setOf('x', 'o', 'b')) {
            val radix = src[pos + 1].lowercaseChar()
            pos += 2
            val before = pos
            digits {
                when (radix) {
                    'x' -> it.isJsHex()
                    'o' -> it in '0'..'7'
                    else -> it == '0' || it == '1'
                }
            }
            if (pos == before) raise("Expected number in radix", start, pos)
        } else {
            digits { it in '0'..'9' }
            if (src.getOrNull(pos) == '.') {
                pos++
                digits { it in '0'..'9' }
            }
            if (src.getOrNull(pos)?.lowercaseChar() == 'e') {
                pos++
                if (src.getOrNull(pos) == '+' || src.getOrNull(pos) == '-') pos++
                val before = pos
                digits { it in '0'..'9' }
                if (pos == before) raise("Invalid number", start, pos)
            }
        }
        if (src.getOrNull(pos) == 'n') pos++
        if (pos < src.length && src[pos].isJsIdentStart()) raise("Identifier directly after number", pos, pos + 1)
        return JsToken(T.NUM, src.substring(start, pos), start, pos, newline)
    }

    private fun readString(
        quote: Char,
        start: Int,
        newline: Boolean,
    ): JsToken {
        pos++
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == quote -> {
                    pos++
                    return JsToken(T.STRING, src.substring(start, pos), start, pos, newline)
                }

                c == '\\' -> {
                    pos += 2
                }

                c == '\n' || c == '\r' -> {
                    raise("Unterminated string constant", start, pos)
                }

                else -> {
                    pos++
                }
            }
        }
        raise("Unterminated string constant", start, src.length)
    }

    private fun readTemplate(
        start: Int,
        newline: Boolean,
    ): JsToken {
        pos++
        val parts = ArrayList<IntRange>()
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '`' -> {
                    pos++
                    return JsToken(T.TEMPLATE, src.substring(start, pos), start, pos, newline, parts)
                }

                c == '\\' -> {
                    pos += 2
                }

                c == '$' && src.getOrNull(pos + 1) == '{' -> {
                    val open = pos + 2
                    val close = matchingBrace(open)
                    parts += open until close
                    pos = close + 1
                }

                else -> {
                    pos++
                }
            }
        }
        raise("Unterminated template", start, src.length)
    }

    /** The offset of the `}` that closes a `${` whose contents start at [from]; nested strings and templates are skipped. */
    private fun matchingBrace(from: Int): Int {
        var depth = 1
        var i = from
        while (i < src.length) {
            when (val c = src[i]) {
                '{' -> {
                    depth++
                }

                '}' -> {
                    if (--depth == 0) return i
                }

                '\'', '"' -> {
                    i++
                    while (i < src.length && src[i] != c) {
                        if (src[i] == '\\') i++
                        i++
                    }
                }

                '`' -> {
                    i++
                    while (i < src.length && src[i] != '`') {
                        if (src[i] == '\\') {
                            i++
                        } else if (src[i] == '$' && src.getOrNull(i + 1) == '{') {
                            i = matchingBrace(i + 2)
                        }
                        i++
                    }
                }
            }
            i++
        }
        raise("Unterminated template", from - 2, src.length)
    }

    private fun readRegex(
        start: Int,
        newline: Boolean,
    ): JsToken {
        pos++
        var inClass = false
        while (true) {
            if (pos >= src.length || src[pos] == '\n' || src[pos] == '\r') raise("Unterminated regular expression", start, pos)
            val c = src[pos]
            when {
                c == '\\' -> pos++
                c == '[' -> inClass = true
                c == ']' -> inClass = false
                c == '/' && !inClass -> break
            }
            pos++
        }
        pos++
        val flagsStart = pos
        while (pos < src.length && src[pos].isJsIdentPart()) pos++
        val flags = src.substring(flagsStart, pos)
        if (flags.any { it !in "dgimsuvy" } || flags.toSet().size != flags.length) {
            raise("Invalid regular expression flag", flagsStart, pos)
        }
        return JsToken(T.REGEX, src.substring(start, pos), start, pos, newline)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun isPunct(value: String): Boolean = tok.type == T.PUNCT && tok.value == value

    private fun isKeyword(value: String): Boolean = tok.type == T.KEYWORD && tok.value == value

    private fun isName(value: String): Boolean = tok.type == T.NAME && tok.value == value

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

    private fun expect(value: String) {
        if (!eat(value)) unexpected()
    }

    private fun canInsertSemicolon(): Boolean = tok.type == T.EOF || isPunct("}") || tok.newlineBefore

    private fun semicolon() {
        if (!eat(";") && !canInsertSemicolon()) unexpected()
    }

    // ---- statements ----------------------------------------------------------------------------

    private fun parseStatement() {
        when {
            tok.type == T.KEYWORD -> {
                when (tok.value) {
                    "var", "const" -> {
                        next()
                        parseVarDeclarations()
                        semicolon()
                    }

                    "if" -> {
                        parseIf()
                    }

                    "for" -> {
                        parseFor()
                    }

                    "while" -> {
                        next()
                        parseParenExpression()
                        parseStatement()
                    }

                    "do" -> {
                        next()
                        parseStatement()
                        if (!eatKeyword("while")) unexpected()
                        parseParenExpression()
                        eat(";")
                    }

                    "return" -> {
                        next()
                        if (!eat(";") && !canInsertSemicolon()) {
                            parseExpression()
                            semicolon()
                        }
                    }

                    "break", "continue" -> {
                        next()
                        if (tok.type == T.NAME && !tok.newlineBefore) next()
                        semicolon()
                    }

                    "throw" -> {
                        next()
                        if (tok.newlineBefore) raise("Illegal newline after throw", tok.start, tok.end)
                        parseExpression()
                        semicolon()
                    }

                    "try" -> {
                        parseTry()
                    }

                    "switch" -> {
                        parseSwitch()
                    }

                    "function" -> {
                        next()
                        parseFunctionRest(isStatement = true)
                    }

                    "class" -> {
                        next()
                        parseClassRest(isStatement = true)
                    }

                    "debugger" -> {
                        next()
                        semicolon()
                    }

                    "import", "export", "with" -> {
                        raise("'${tok.value}' is not allowed in a Datastar expression", tok.start, tok.end)
                    }

                    else -> {
                        parseExpression()
                        semicolon()
                    }
                }
            }

            isPunct("{") -> {
                parseBlock()
            }

            isPunct(";") -> {
                next()
            }

            isName("let") && peekIsBinding() -> {
                next()
                parseVarDeclarations()
                semicolon()
            }

            isName("async") && peekIsFunctionKeyword() -> {
                next()
                next()
                parseFunctionRest(isStatement = true)
            }

            tok.type == T.NAME && peekIsColon() -> {
                // A labelled statement: `outer: for (...) {}`.
                next()
                next()
                parseStatement()
            }

            else -> {
                parseExpression()
                semicolon()
            }
        }
    }

    private fun peekIsBinding(): Boolean {
        val save = pos
        val saveTok = tok
        val saveLast = last
        next()
        val yes = tok.type == T.NAME || isPunct("[") || isPunct("{") || (tok.type == T.KEYWORD && tok.value !in setOf("in", "instanceof"))
        pos = save
        tok = saveTok
        last = saveLast
        return yes
    }

    private fun peekIsFunctionKeyword(): Boolean = peek { isKeyword("function") && !tok.newlineBefore }

    private fun peekIsColon(): Boolean = peek { isPunct(":") }

    private fun <R> peek(f: () -> R): R {
        val save = pos
        val saveTok = tok
        val saveLast = last
        next()
        val r = f()
        pos = save
        tok = saveTok
        last = saveLast
        return r
    }

    private fun parseBlock() {
        expect("{")
        while (!isPunct("}")) {
            if (tok.type == T.EOF) unexpected()
            parseStatement()
        }
        next()
    }

    private fun parseParenExpression() {
        expect("(")
        parseExpression()
        expect(")")
    }

    private fun parseIf() {
        next()
        parseParenExpression()
        parseStatement()
        if (eatKeyword("else")) parseStatement()
    }

    private fun parseVarDeclarations(noIn: Boolean = false) {
        do {
            parseBindingTarget()
            if (eat("=")) parseMaybeAssign(noIn)
        } while (eat(","))
    }

    private fun parseBindingTarget() {
        when {
            tok.type == T.NAME -> next()
            isPunct("[") || isPunct("{") -> parseExprAtom()
            else -> unexpected()
        }
    }

    private fun parseFor() {
        next()
        if (isName("await")) next()
        expect("(")
        if (eat(";")) {
            parseForRest()
            return
        }
        val isDecl = isKeyword("var") || isKeyword("const") || (isName("let") && peekIsBinding())
        if (isDecl) {
            next()
            parseBindingTarget()
            if (isKeyword("in") || isName("of")) {
                next()
                parseMaybeAssign()
                expect(")")
                parseStatement()
                return
            }
            if (eat("=")) parseMaybeAssign(noIn = true)
            while (eat(",")) {
                parseBindingTarget()
                if (eat("=")) parseMaybeAssign(noIn = true)
            }
        } else {
            parseExpression(noIn = true)
            if (isKeyword("in") || isName("of")) {
                next()
                parseMaybeAssign()
                expect(")")
                parseStatement()
                return
            }
        }
        expect(";")
        parseForRest()
    }

    private fun parseForRest() {
        if (!isPunct(";")) parseExpression()
        expect(";")
        if (!isPunct(")")) parseExpression()
        expect(")")
        parseStatement()
    }

    private fun parseTry() {
        next()
        parseBlock()
        var handled = false
        if (eatKeyword("catch")) {
            handled = true
            if (eat("(")) {
                parseBindingTarget()
                expect(")")
            }
            parseBlock()
        }
        if (eatKeyword("finally")) {
            handled = true
            parseBlock()
        }
        if (!handled) raise("Missing catch or finally clause", tok.start, tok.end)
    }

    private fun parseSwitch() {
        next()
        parseParenExpression()
        expect("{")
        while (!isPunct("}")) {
            when {
                eatKeyword("case") -> {
                    parseExpression()
                    expect(":")
                }

                eatKeyword("default") -> {
                    expect(":")
                }

                tok.type == T.EOF -> {
                    unexpected()
                }

                else -> {
                    parseStatement()
                }
            }
        }
        next()
    }

    private fun parseFunctionRest(isStatement: Boolean) {
        val generator = eat("*")
        if (tok.type == T.NAME) {
            next()
        } else if (isStatement) {
            unexpected()
        }
        parseFunctionParamsAndBody(generator)
    }

    private fun parseFunctionParamsAndBody(generator: Boolean) {
        expect("(")
        parseParams()
        expect(")")
        inFunction++
        if (generator) inGenerator++
        parseBlock()
        if (generator) inGenerator--
        inFunction--
    }

    private fun parseParams() {
        while (!isPunct(")")) {
            if (eat("...")) {
                parseBindingTarget()
                break
            }
            parseBindingTarget()
            if (eat("=")) parseMaybeAssign()
            if (!eat(",")) break
        }
    }

    private fun parseClassRest(isStatement: Boolean) {
        if (tok.type == T.NAME && !isName("extends")) {
            next()
        } else if (isStatement) {
            unexpected()
        }
        if (eatKeyword("extends")) parseExprSubscripts()
        expect("{")
        while (!isPunct("}")) {
            if (eat(";")) continue
            if (tok.type == T.EOF) unexpected()
            if (isName("static") && peek { !isPunct("(") && !isPunct("=") }) next()
            if (isPunct("{")) {
                // A static initialisation block.
                parseBlock()
                continue
            }
            parseMethodOrProperty(inClass = true)
        }
        next()
    }

    /**
     * A class member or an object literal member after any `static`: `get x() {}`, `async *f() {}`, `[k]: v`, `x = 1`, `...rest`.
     * Returns whether the member may sit in a destructuring pattern.
     */
    private fun parseMethodOrProperty(inClass: Boolean): Boolean {
        if (eat("...")) {
            parseMaybeAssign()
            return lastAtomWasTarget
        }
        var isModifier = false
        if ((isName("get") || isName("set") || isName("async")) &&
            peek { !isPunct("(") && !isPunct(",") && !isPunct(":") && !isPunct("=") && !isPunct("}") }
        ) {
            next()
            isModifier = true
        }
        val generator = eat("*")
        val shorthandName = tok.type == T.NAME
        parsePropertyName()
        return when {
            isPunct("(") -> {
                parseFunctionParamsAndBody(generator)
                false
            }

            !inClass && eat(":") -> {
                parseMaybeAssign()
                lastPatternOk
            }

            inClass -> {
                if (eat("=")) parseMaybeAssign()
                semicolon()
                false
            }

            // Shorthand `{a}` or a pattern default `{a = 1}`.
            shorthandName && !isModifier && !generator -> {
                if (eat("=")) parseMaybeAssign()
                true
            }

            else -> {
                unexpected()
            }
        }
    }

    private fun parsePropertyName() {
        when {
            isPunct("[") -> {
                next()
                parseMaybeAssign()
                expect("]")
            }

            tok.type == T.NAME || tok.type == T.KEYWORD || tok.type == T.NUM || tok.type == T.STRING || tok.type == T.PRIVATE_NAME -> {
                next()
            }

            else -> {
                unexpected()
            }
        }
    }

    // ---- expressions ---------------------------------------------------------------------------

    private fun parseExpression(noIn: Boolean = false) {
        parseMaybeAssign(noIn)
        while (eat(",")) parseMaybeAssign(noIn)
    }

    private fun parseMaybeAssign(noIn: Boolean = false) {
        if (isName("yield") && inGenerator > 0) {
            next()
            if (!canInsertSemicolon() && !isPunct(")") && !isPunct("]") && !isPunct("}") && !isPunct(",")) {
                eat("*")
                parseMaybeAssign(noIn)
            }
            return
        }
        if (isArrowAhead()) {
            parseArrow()
            return
        }
        val start = tok
        val startPos = tok.start
        parseMaybeConditional(noIn)
        if (tok.type == T.PUNCT && tok.value in ASSIGN_OPS) {
            checkAssignable(start, startPos)
            val plain = tok.value == "="
            next()
            parseMaybeAssign(noIn)
            lastAtomWasTarget = false
            lastPatternOk = plain
            return
        }
        lastPatternOk = lastAtomWasTarget
    }

    private var lastAtomWasTarget = false
    private var lastExprEnd = 0

    /** Whether the expression just parsed may sit inside a destructuring pattern: a target, or a target with a default. */
    private var lastPatternOk = false

    private fun checkAssignable(
        start: JsToken,
        startPos: Int,
    ) {
        // The left side parsed so far runs from [startPos] to [lastExprEnd]; it is assignable when it
        // is a name, a member access or a destructuring pattern, and never a literal or a call.
        if (!lastAtomWasTarget) raise("Assigning to rvalue", startPos, maxOf(lastExprEnd, startPos + 1))
        if (start.type == T.KEYWORD && start.value !in setOf("this", "super")) raise("Assigning to rvalue", startPos, start.end)
    }

    private fun isArrowAhead(): Boolean {
        if (tok.type == T.NAME && !isName("async")) return peek { isPunct("=>") && !tok.newlineBefore }
        if (isName("async")) {
            return peek {
                when {
                    tok.newlineBefore -> false
                    tok.type == T.NAME -> peek { isPunct("=>") }
                    isPunct("(") -> parenFollowedByArrow(tok.start)
                    else -> false
                }
            }
        }
        if (isPunct("(")) return parenFollowedByArrow(tok.start)
        return false
    }

    /** Is the `(` at [open] closed by a `)` that is followed by `=>`? A scan of the raw text, skipping strings. */
    private fun parenFollowedByArrow(open: Int): Boolean {
        var depth = 0
        var i = open
        while (i < src.length) {
            when (val c = src[i]) {
                '(', '[', '{' -> {
                    depth++
                }

                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) {
                        var j = i + 1
                        while (j < src.length && src[j].isWhitespace() && src[j] != '\n') j++
                        return src.startsWith("=>", j)
                    }
                }

                '\'', '"', '`' -> {
                    i++
                    while (i < src.length && src[i] != c) {
                        if (src[i] == '\\') i++
                        i++
                    }
                }

                '/' -> {
                    if (src.getOrNull(i + 1) == '/' || src.getOrNull(i + 1) == '*') return false
                }
            }
            i++
        }
        return false
    }

    private fun parseArrow() {
        if (isName("async") && peek { !tok.newlineBefore && (tok.type == T.NAME || isPunct("(")) }) next()
        if (tok.type == T.NAME) {
            next()
        } else {
            expect("(")
            parseParams()
            expect(")")
        }
        expect("=>")
        inFunction++
        if (isPunct("{")) parseBlock() else parseMaybeAssign()
        inFunction--
        lastAtomWasTarget = false
        lastExprEnd = last?.end ?: tok.start
    }

    private fun parseMaybeConditional(noIn: Boolean) {
        parseExprOps(noIn)
        if (eat("?")) {
            parseMaybeAssign()
            expect(":")
            parseMaybeAssign(noIn)
            lastAtomWasTarget = false
        }
    }

    private fun parseExprOps(noIn: Boolean) {
        parseMaybeUnary()
        parseExprOp(-1, noIn)
    }

    private fun binaryPrecedence(noIn: Boolean): Int {
        if (tok.type == T.KEYWORD) {
            return when (tok.value) {
                "in" -> if (noIn) -1 else 7
                "instanceof" -> 7
                else -> -1
            }
        }
        if (tok.type != T.PUNCT) return -1
        return when (tok.value) {
            "??" -> 1
            "||" -> 1
            "&&" -> 2
            "|" -> 3
            "^" -> 4
            "&" -> 5
            "==", "!=", "===", "!==" -> 6
            "<", ">", "<=", ">=" -> 7
            "<<", ">>", ">>>" -> 8
            "+", "-" -> 9
            "*", "/", "%" -> 10
            "**" -> 11
            else -> -1
        }
    }

    private fun parseExprOp(
        minPrec: Int,
        noIn: Boolean,
    ) {
        while (true) {
            val prec = binaryPrecedence(noIn)
            if (prec < 0 || prec <= minPrec) return
            val op = tok.value
            next()
            parseMaybeUnary()
            // `**` is right-associative.
            parseExprOp(if (op == "**") prec - 1 else prec, noIn)
            lastAtomWasTarget = false
        }
    }

    private fun parseMaybeUnary() {
        val start = tok.start
        when {
            tok.type == T.KEYWORD && tok.value in setOf("typeof", "void", "delete") -> {
                next()
                parseMaybeUnary()
                lastAtomWasTarget = false
                return
            }

            tok.type == T.PUNCT && tok.value in setOf("!", "~", "+", "-") -> {
                next()
                parseMaybeUnary()
                if (isPunct("**")) raise("Unary operator used immediately before exponentiation expression", start, tok.end)
                lastAtomWasTarget = false
                return
            }

            tok.type == T.PUNCT && (tok.value == "++" || tok.value == "--") -> {
                next()
                val operandStart = tok.start
                parseMaybeUnary()
                if (!lastAtomWasTarget) raise("Assigning to rvalue", operandStart, maxOf(lastExprEnd, operandStart + 1))
                lastAtomWasTarget = false
                return
            }

            isName("await") -> {
                // `await` at the top level or in an async function is an operator; otherwise a plain name.
                if (peek {
                        !tok.newlineBefore && (tok.type != T.PUNCT || tok.value in setOf("(", "[", "{", "!", "~", "+", "-", "++", "--")) &&
                            !isPunct("=>")
                    }
                ) {
                    next()
                    parseMaybeUnary()
                    lastAtomWasTarget = false
                    return
                }
            }
        }
        parseExprSubscripts()
        if (tok.type == T.PUNCT && (tok.value == "++" || tok.value == "--") && !tok.newlineBefore) {
            if (!lastAtomWasTarget) raise("Assigning to rvalue", start, maxOf(lastExprEnd, start + 1))
            next()
            lastAtomWasTarget = false
        }
    }

    private fun parseExprSubscripts() {
        parseExprAtom()
        parseSubscripts()
    }

    private fun parseSubscripts() {
        var optionalChained = false
        while (true) {
            when {
                eat(".") -> {
                    if (tok.type == T.NAME || tok.type == T.KEYWORD || tok.type == T.PRIVATE_NAME) next() else unexpected()
                    lastAtomWasTarget = !optionalChained
                }

                isPunct("?.") -> {
                    next()
                    optionalChained = true
                    when {
                        isPunct("(") -> {
                            parseArguments()
                        }

                        isPunct("[") -> {
                            next()
                            parseExpression()
                            expect("]")
                        }

                        tok.type == T.NAME || tok.type == T.KEYWORD || tok.type == T.PRIVATE_NAME -> {
                            next()
                        }

                        else -> {
                            unexpected()
                        }
                    }
                    lastAtomWasTarget = false
                }

                isPunct("[") -> {
                    next()
                    parseExpression()
                    expect("]")
                    lastAtomWasTarget = !optionalChained
                }

                isPunct("(") -> {
                    parseArguments()
                    lastAtomWasTarget = false
                }

                tok.type == T.TEMPLATE -> {
                    if (optionalChained) {
                        raise(
                            "Optional chaining cannot appear in the tag of tagged template expressions",
                            tok.start,
                            tok.end,
                        )
                    }
                    parseTemplate()
                    lastAtomWasTarget = false
                }

                else -> {
                    lastExprEnd = last?.end ?: tok.start
                    return
                }
            }
        }
    }

    private fun parseArguments() {
        expect("(")
        while (!isPunct(")")) {
            if (eat("...")) parseMaybeAssign() else parseMaybeAssign()
            if (!eat(",")) break
        }
        expect(")")
    }

    private fun parseTemplate() {
        val t = tok
        for (part in t.templateParts) {
            val inner = src.substring(part.first, part.last + 1)
            if (inner.isBlank()) raise("Unexpected token", part.last + 1, part.last + 2)
            try {
                Parser(inner).parseExpressionOnly()
            } catch (e: JsSyntaxError) {
                throw JsSyntaxError(e.message ?: "Unexpected token", part.first + e.pos, part.first + e.raisedAt)
            }
        }
        next()
    }

    fun parseExpressionOnly() {
        next()
        parseExpression()
        if (tok.type != T.EOF) unexpected()
    }

    private fun parseExprAtom() {
        lastAtomWasTarget = false
        when (tok.type) {
            T.NAME -> {
                if (isName("async") && peek { isKeyword("function") && !tok.newlineBefore }) {
                    next()
                    next()
                    parseFunctionRest(isStatement = false)
                    return
                }
                next()
                lastAtomWasTarget = true
            }

            T.KEYWORD -> {
                when (tok.value) {
                    "this", "null", "true", "false" -> {
                        val target = tok.value == "this"
                        next()
                        lastAtomWasTarget = target
                    }

                    "super" -> {
                        next()
                        if (!isPunct("(") && !isPunct(".") && !isPunct("[")) unexpected()
                        lastAtomWasTarget = true
                    }

                    "function" -> {
                        next()
                        parseFunctionRest(isStatement = false)
                    }

                    "class" -> {
                        next()
                        parseClassRest(isStatement = false)
                    }

                    "new" -> {
                        parseNew()
                    }

                    "import" -> {
                        next()
                        if (eat(".")) {
                            if (tok.type == T.NAME) next() else unexpected()
                        } else {
                            parseArguments()
                        }
                    }

                    else -> {
                        unexpected()
                    }
                }
            }

            T.NUM, T.STRING, T.REGEX -> {
                next()
            }

            T.TEMPLATE -> {
                parseTemplate()
            }

            T.PRIVATE_NAME -> {
                // `#x in obj`
                next()
                if (!isKeyword("in")) unexpected()
            }

            T.PUNCT -> {
                when (tok.value) {
                    "(" -> {
                        next()
                        if (isPunct(")")) unexpected()
                        parseExpression()
                        expect(")")
                        // A parenthesised name or member is still assignable: `($count) = 1` is legal JavaScript.
                    }

                    "[" -> {
                        next()
                        var pattern = true
                        while (!isPunct("]")) {
                            if (isPunct(",")) {
                                next()
                                continue
                            }
                            val spread = eat("...")
                            parseMaybeAssign()
                            if (!(if (spread) lastAtomWasTarget else lastPatternOk)) pattern = false
                            if (!isPunct("]")) expect(",")
                        }
                        next()
                        lastAtomWasTarget = pattern
                    }

                    "{" -> {
                        next()
                        var pattern = true
                        while (!isPunct("}")) {
                            if (tok.type == T.EOF) unexpected()
                            if (!parseMethodOrProperty(inClass = false)) pattern = false
                            if (!isPunct("}")) expect(",")
                        }
                        next()
                        lastAtomWasTarget = pattern
                    }

                    else -> {
                        unexpected()
                    }
                }
            }

            T.EOF -> {
                unexpected()
            }
        }
    }

    private fun parseNew() {
        next()
        if (eat(".")) {
            if (isName("target")) next() else unexpected()
            return
        }
        if (isKeyword("new")) {
            parseNew()
        } else {
            parseExprAtom()
        }
        // Member accesses bind to the constructor; the first argument list belongs to `new`.
        while (true) {
            when {
                eat(".") -> {
                    if (tok.type == T.NAME || tok.type == T.KEYWORD || tok.type == T.PRIVATE_NAME) next() else unexpected()
                }

                isPunct("[") -> {
                    next()
                    parseExpression()
                    expect("]")
                }

                else -> {
                    break
                }
            }
        }
        if (isPunct("(")) parseArguments()
        lastAtomWasTarget = false
    }

    private companion object {
        val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "&=", "|=", "^=", "&&=", "||=", "??=")
    }
}

private fun Char.isJsIdentStart(): Boolean = this == '$' || this == '_' || this.isLetter()

private fun Char.isJsIdentPart(): Boolean = isJsIdentStart() || this.isDigit() || this == '\u200C' || this == '\u200D'

private fun Char.isJsHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
