package io.github.markusaugust.streamlord.analysis

/*
 * The operands of `+` around a string literal, read as Kotlin's grammar has them or not at all.
 *
 * Plus binds tighter than a range, an infix call, `?:`, `in`, `is`, a comparison, `==`, `&&` and
 * `||`, and less tightly than `*`, `/`, `%`, a cast, a sign and whatever is read from or called on
 * a value. A line break before `+` ends a statement, so the plus is a sign there, unless brackets
 * are open around it; one after `+` goes on. What this cannot read it says it cannot, and the
 * caller then rewrites nothing.
 */

/** What a `+` joins before a string literal, from [start] to [end]; not [read] where it could not be told apart as one value. */
internal class Leading(
    val start: Int,
    val end: Int,
    val read: Boolean,
)

/** The words that start no value this reads. */
private val NO_VALUE = "if else try object fun return throw break continue do while for val var class is in as".split(" ").toSet()

/** The characters operators are made of, beside the words. */
private const val OPERATOR_CHARS = "=!<>&|?:.+-*/%"

/** How far back the statement a literal stands in is looked for. */
private const val REACH = 4000

/** [mask] is the lexer's: `false` inside literals and comments. */
internal class Operands(
    private val src: String,
    private val mask: BooleanArray,
) {
    private fun ch(k: Int): Char? = src.getOrNull(k)

    /** Past spaces, tabs and comments from [from], and past line breaks too with [newlines]. */
    fun skip(
        from: Int,
        newlines: Boolean,
    ): Int {
        var k = from
        while (k < src.length) {
            val c = src[k]
            if (c == ' ' || c == '\t' || (newlines && (c == '\n' || c == '\r'))) {
                k++
            } else if (src.startsWith("/*", k)) {
                k = src.indexOf("*/", k + 2).let { if (it < 0) return k else it + 2 }
            } else if (newlines && src.startsWith("//", k)) {
                k = src.indexOf('\n', k).let { if (it < 0) return k else it }
            } else {
                break
            }
        }
        return k
    }

    /** Back over spaces, tabs, line breaks and comments that end before [from]. */
    private fun skipBack(from: Int): Int {
        var k = from
        while (k > 0) {
            if (src[k - 1].isWhitespace()) {
                k--
            } else if (!mask[k - 1]) {
                // A comment is a run outside the code that opens as one; anything else there is a literal.
                var run = k - 1
                while (run > 0 && !mask[run - 1]) run--
                if (!src.startsWith("//", run) && !src.startsWith("/*", run)) return k
                k = run
            } else {
                return k
            }
        }
        return k
    }

    /** The bracket that matches the one at [at], after it or before it, strings and comments stepped over; -1 where none does. */
    fun matching(at: Int): Int {
        val step = if (src[at] in "([{") 1 else -1
        var depth = 0
        var k = at
        while (k in src.indices) {
            if (mask[k]) {
                when (src[k]) {
                    '(', '[', '{' -> depth += step
                    ')', ']', '}' -> depth -= step
                }
                if (depth == 0) return k
            }
            k += step
        }
        return -1
    }

    /** Just past the bracket that closes the one at [open], or -1. */
    private fun closed(open: Int): Int = matching(open).let { if (it < 0) -1 else it + 1 }

    /** Just past the name at [from], in backticks or not; [from] where none stands. */
    private fun wordEnd(from: Int): Int {
        if (ch(from) == '`') return src.indexOf('`', from + 1).let { if (it < 0) from else it + 1 }
        var k = from
        while (k < src.length && (src[k].isLetterOrDigit() || src[k] == '_')) k++
        return k
    }

    fun word(from: Int): String = if (from >= src.length) "" else src.substring(from, wordEnd(from))

    /**
     * Just past the `<...>` at [open] where it holds types alone, and with [call] a bracket follows,
     * as the type arguments of a call have it: `foo<String>(a)`. -1 where `<` compares.
     */
    private fun typeArgumentsEnd(
        open: Int,
        call: Boolean,
    ): Int {
        var depth = 0
        var k = open
        while (k < src.length) {
            val c = src[k]
            if (c == '<') {
                depth++
            } else if (c == '>') {
                if (--depth == 0) break
            } else if (!(c.isLetterOrDigit() || c in "_.,?* \t\n\r`")) {
                return -1
            }
            k++
        }
        if (k >= src.length) return -1
        return if (!call || ch(skip(k + 1, false)).let { it == '(' || it == '{' }) k + 1 else -1
    }

    /**
     * Just past what is read from or called on the value that ends at [from]: `.name`, which may
     * start the next line, and on the same line arguments, an index, `!!`, type arguments and,
     * after something [callable], a lambda. A string literal takes no lambda.
     */
    fun postfixEnd(
        from: Int,
        callable: Boolean,
    ): Int {
        var k = from
        var call = callable
        while (true) {
            val next = skip(k, true)
            val c = ch(next)
            val member = (c == '.' && ch(next + 1) != '.') || src.startsWith("?.", next) || src.startsWith("::", next)
            if (!member && next != skip(k, false)) return k
            k =
                when {
                    member -> {
                        val name = skip(next + (if (c == '.') 1 else 2), true)
                        wordEnd(name).takeIf { it > name } ?: return k
                    }

                    c == '(' || c == '[' || (c == '{' && call) -> {
                        closed(next).takeIf { it > 0 } ?: return k
                    }

                    c == '<' && call -> {
                        typeArgumentsEnd(next, true).takeIf { it > 0 } ?: return k
                    }

                    src.startsWith("!!", next) -> {
                        next + 2
                    }

                    else -> {
                        return k
                    }
                }
            call = c != '{'
        }
    }

    /** Just past the value that starts at [from] with what is read from and called on it, or -1 where this reads none. */
    private fun primaryEnd(from: Int): Int {
        val c = ch(from) ?: return -1
        if (isStringStart(src, from)) {
            val literal = readKotlinStringAt(src, from)?.takeIf { !it.unterminated } ?: return -1
            return postfixEnd(literal.end, false)
        }
        if (c == '\'') return postfixEnd(minOf(src.length, charLiteralEnd(src, from)), false)
        if (c == '(' || c == '[' || c == '{') return closed(from).let { if (it < 0) -1 else postfixEnd(it, c == '(') }
        if (c.isDigit()) {
            var k = wordEnd(from)
            if (ch(k) == '.' && ch(k + 1)?.isDigit() == true) k = wordEnd(k + 1)
            if (src[k - 1] in "eE" && ch(k).let { it == '+' || it == '-' } && ch(k + 1)?.isDigit() == true) k = wordEnd(k + 1)
            return postfixEnd(k, false)
        }
        val name = if (src.startsWith("::", from)) from + 2 else from
        val end = wordEnd(name)
        if (end == name) return -1
        return when (val word = src.substring(name, end)) {
            "when" -> {
                var k = skip(end, false)
                if (ch(k) == '(') k = skip(closed(k).takeIf { it > 0 } ?: return -1, false)
                if (ch(k) == '{') closed(k).let { if (it < 0) -1 else postfixEnd(it, false) } else -1
            }

            in NO_VALUE -> {
                -1
            }

            else -> {
                // `this@Outer` is a value; any other `name@` labels what follows.
                val labelled = ch(end) == '@'
                if (labelled && word != "this" && word != "super") -1 else postfixEnd(if (labelled) wordEnd(end + 1) else end, true)
            }
        }
    }

    /** Just past the operand of an operator that starts at [from]: its signs, one value and the casts of it; -1 where this reads none. */
    private fun unaryEnd(from: Int): Int {
        var k = from
        while (ch(k).let { it == '-' || it == '+' || it == '!' }) k = skip(k + 1, false)
        var end = primaryEnd(k)
        while (end >= 0) {
            val cast = skip(end, true)
            if (word(cast) != "as") break
            val type = skip(cast + (if (ch(cast + 2) == '?') 3 else 2), true)
            var k2 = wordEnd(type)
            if (k2 == type) return -1
            while (ch(k2) == '.' && wordEnd(k2 + 1) > k2 + 1) k2 = wordEnd(k2 + 1)
            if (ch(k2) == '<') k2 = typeArgumentsEnd(k2, false).takeIf { it > 0 } ?: return -1
            end = if (ch(k2) == '?' && ch(k2 + 1) != '.' && ch(k2 + 1) != ':') k2 + 1 else k2
        }
        return end
    }

    /** Whether a `+` that joins stands at [at]: not `++` and not `+=`. */
    fun plusAt(at: Int): Boolean = ch(at) == '+' && ch(at + 1) != '+' && ch(at + 1) != '='

    /**
     * Just past the term of a sum that starts at [from], operands joined with `*`, `/` and `%`, each
     * of which binds tighter than plus; -1 where this cannot read it. With [newlines] a line break
     * ends nothing, as inside brackets.
     */
    fun termEnd(
        from: Int,
        newlines: Boolean,
    ): Int {
        var end = unaryEnd(from)
        while (end >= 0) {
            val op = skip(end, newlines)
            val times = ch(op).let { it == '*' || it == '%' || (it == '/' && ch(op + 1) != '/') }
            if (!times || ch(op + 1) == '=') break
            end = unaryEnd(skip(op + 1, true))
        }
        return end
    }

    /**
     * Where the expression that [from] stands in ends: at the bracket that closes around it, a comma,
     * a semicolon or a comment, or with the line, unless [newlines] end nothing or `else` goes on.
     */
    fun expressionEnd(
        from: Int,
        newlines: Boolean,
    ): Int {
        var depth = 0
        var k = from
        while (k < src.length) {
            val c = src[k]
            if (!mask[k]) {
                if (depth == 0 && src.startsWith("//", k)) break
            } else if (c == '(' || c == '[' || c == '{') {
                depth++
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth-- == 0) break
            } else if (depth == 0 && (c == ',' || c == ';' || (c == '\n' && !newlines && word(skip(k, true)) != "else"))) {
                break
            }
            k++
        }
        while (k > from && src[k - 1].isWhitespace()) k--
        return k
    }

    /** The bracket open around [at], or -1 where none is within reach: a block or the file, where a line break ends a statement. */
    private fun openBefore(at: Int): Int {
        var depth = 0
        for (k in at - 1 downTo maxOf(0, at - REACH)) {
            if (!mask[k]) continue
            when (src[k]) {
                ')', ']', '}' -> depth++
                '(', '[', '{' -> if (depth-- == 0) return k
            }
        }
        return -1
    }

    /** Whether a line break before [at] ends nothing: round or square brackets are open around it. */
    fun inBrackets(at: Int): Boolean = openBefore(at).let { it >= 0 && src[it] != '{' }

    /**
     * Where the statement or the argument that holds [plus] starts: after the bracket open around
     * it, a semicolon, an assignment or an arrow, or, in a block, after the last line break that
     * ends a statement. That is every one but those a `.name` goes on after or a sign of a sum or a
     * product stands before. -1 where it is out of reach.
     */
    private fun startBefore(plus: Int): Int {
        val open = openBefore(plus)
        val block = open < 0 || src[open] == '{'
        var depth = 0
        for (k in plus - 1 downTo maxOf(open + 1, plus - REACH, 0)) {
            if (!mask[k]) continue
            val c = src[k]
            if (c == ')' || c == ']' || c == '}') depth++
            if (c == '(' || c == '[' || c == '{') depth--
            if (depth != 0) continue
            val assigns = c == '=' && ch(k + 1) != '=' && (k == 0 || src[k - 1] !in "=!<>")
            if (c == ';' || assigns || (c == '>' && k > 0 && src[k - 1] == '-')) return k + 1
            if (c == '\n' && block) {
                val after = skip(k, true)
                val member = (ch(after) == '.' && ch(after + 1) != '.') || src.startsWith("?.", after)
                val before = skipBack(k)
                if (!member && (before == 0 || src[before - 1] !in "+-*/%")) return k + 1
            }
        }
        return if (open >= 0 || plus <= REACH) open + 1 else -1
    }

    /**
     * What the `+` in front of the literal at [literal] joins to it: the sum before it as one
     * value, since plus is read from the left and `'a' + 1 + "<p>"` adds before it joins. Null
     * where no plus stands there or the plus is a sign, as on a line of its own in a block.
     */
    fun leading(literal: Int): Leading? {
        val plus = skipBack(literal) - 1
        if (plus < 0 || src[plus] != '+' || !mask[plus] || ch(plus - 1) == '+') return null
        val start = startBefore(plus)
        // Out of reach: something is joined, and nothing of it is known.
        if (start < 0) return Leading(plus, plus, false)
        val newlines = inBrackets(plus)
        var pos = skip(start, true)
        val from = pos
        // Where the sum that the term at [pos] belongs to starts, or -1 where a new one may start.
        var sum = -1
        while (pos < plus) {
            val first = word(pos)
            if (sum < 0 && (first == "return" || first == "throw" || first == "else" || first == "in" || first == "is")) {
                pos = skip(wordEnd(pos).let { if (ch(it) == '@') wordEnd(it + 1) else it }, true)
                continue
            }
            if (sum < 0 && (first == "if" || first == "while" || first == "for") && ch(skip(pos + first.length, false)) == '(') {
                pos = skip(closed(skip(pos + first.length, false)).takeIf { it > 0 } ?: break, true)
                continue
            }
            if (sum < 0 && ch(pos) == '*') {
                // A spread argument.
                pos++
                continue
            }
            val end = termEnd(pos, newlines)
            // A term that holds the plus has it for a sign of its own.
            if (end > plus) return null
            if (end < 0) break
            if (sum < 0) sum = pos
            val op = skip(end, newlines)
            if (op == plus) return Leading(sum, end, true)
            if (plusAt(op) || (ch(op) == '-' && ch(op + 1).let { it != '-' && it != '=' && it != '>' })) {
                pos = skip(op + 1, true)
                continue
            }
            // Anything else between two terms binds less tightly than plus or parts two statements: a new sum starts after it.
            val name = wordEnd(op)
            var after = if (name > op) name else op + 1
            if (name == op && src[op] in OPERATOR_CHARS) {
                while (after < plus && src[after] in OPERATOR_CHARS) after++
            } else if (name == op && src[op] !in ",;\n\r") {
                break
            }
            sum = -1
            pos = skip(after, true)
        }
        // A term that starts at the plus has it for a sign.
        if (pos == plus) return null
        var end = plus
        while (end > from && src[end - 1].isWhitespace()) end--
        return Leading(from, end, false)
    }
}
