package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.StreamlordException

/**
 * Raised when a Datastar expression bears the marks of a Kotlin string template that ate a
 * signal: `"$count++"` reaches the DSL as `"++"`, `"$open = !$open"` as `" = !"`, `"$count"` as
 * nothing at all. The browser would accept the attribute and silently do nothing. Streamlord
 * refuses instead, at render time, where the stack trace points at the line that wrote it.
 *
 * @property expression The text that arrived, after Kotlin was done with it.
 * @property attribute The `data-*` attribute the text sat in, when [ElementsGuard] found it in
 *   HTML written as a string; `null` when a DSL helper caught it.
 */
public class InterpolatedExpressionException(
    public val expression: String,
    reason: String,
    public val attribute: String? = null,
) : StreamlordException(message(expression, reason, attribute)) {
    private companion object {
        fun message(
            expression: String,
            reason: String,
            attribute: String?,
        ): String =
            if (attribute == null) {
                "Datastar expression \"$expression\" $reason. This is what a Kotlin string template leaves behind when " +
                    "it interpolates a signal such as \$count. Write the signal with the helpers " +
                    "(signal(\"count\"), increment(\"count\"), toggle(\"open\")), or make the string literal keep " +
                    "its dollars: \$\$\"\$count++\" (Kotlin 2.2+) or \"\${'\$'}count++\"."
            } else {
                "Datastar attribute $attribute=\"$expression\" $reason. This is what a Kotlin string template leaves " +
                    "behind when it interpolates a signal such as \$count. Make the string literal keep its " +
                    "dollars: \$\$\"\"\"...\"\"\" (Kotlin 2.2+), where a single \$ is just a dollar, or write " +
                    "\${'\$'}count."
            }
    }
}

/**
 * The wall against the `$` trap. Pure function: returns the expression unchanged, or throws
 * [InterpolatedExpressionException]. It only flags shapes that no working Datastar expression
 * has: nothing but operators, an operator with nothing on the side it needs, a leading member
 * access. Legitimate prefix forms such as `++$count`, `-1` or `!$open` pass.
 *
 * The kotlinx.html helpers in `streamlord-html` call it on every expression. For HTML written
 * as a string, [ElementsGuard] runs it over every expression-valued `data-*` attribute.
 */
public object ExpressionGuard {
    /** Operators that need an operand on their left, longest first so `===` wins over `=`. */
    private val NEEDS_LEFT =
        listOf(
            "===",
            "!==",
            "==",
            "!=",
            "<=",
            ">=",
            "&&",
            "||",
            "??",
            "?.",
            "=",
            "?",
            ":",
            ")",
            "]",
            "}",
            ".",
            "*",
            "/",
            "%",
            "<",
            ">",
            ",",
        )

    /** Operators that need an operand on their right, longest first. A trailing `++`/`--` is checked first, so `$count++` is fine. */
    private val NEEDS_RIGHT =
        listOf(
            "===",
            "!==",
            "==",
            "!=",
            "<=",
            ">=",
            "&&",
            "||",
            "??",
            "?.",
            "=>",
            "=",
            "?",
            ":",
            "(",
            "[",
            "{",
            ".",
            "*",
            "/",
            "%",
            "<",
            ">",
            ",",
            "!",
            "@",
            "+",
            "-",
        )

    private val ONLY_OPERATORS = Regex("^[\\s=!<>&|?:.+\\-*/%,;()\\[\\]{}]*$")

    /** Keyword operators: what is left of `$a in $b`, `typeof $x` or `void $x` once the signals are eaten. */
    private val ONLY_KEYWORDS = Regex("^(?:\\s*\\b(?:in|instanceof|typeof|void|delete|new|await)\\b)+\\s*$")
    private val KEYWORD_NEEDS_LEFT = Regex("^(in|instanceof)\\b")
    private val KEYWORD_NEEDS_RIGHT = Regex("(?<![.\\w$])(in|instanceof|typeof|void|delete|new|await)$")

    /** A binary `+` or `-` at the start, set apart from what follows: `$a + 1` without its `$a`. */
    private val LEADING_BINARY = Regex("^[+\\-]\\s")

    /** A template literal's `${}` with nothing in it, which is not JavaScript at all: `` `${$n}` `` without its `$n`. */
    private val EMPTY_SUBSTITUTION = Regex("\\$\\{\\s*}")

    /** `[]`, `{}`, `[{}]`: empty literals are operands, the usual way to start a list or object signal. */
    private val CLOSERS = setOf(")", "]", "}")

    private val ONLY_BRACKETS = Regex("^[\\s\\[\\]{}]+$")

    /**
     * A statement that is nothing but `++` or `--` (`$count ++`, with its operand, is fine); an operator that needs a left operand right
     * after an opening `(`, `[`, `{` or a `,`; or an operator that needs a right operand right
     * before a closing `)`, `]`, `}` or a `,`. That last shape is what `{count: $count}` and
     * `@post('/x', {n: $n})` leave behind: `{count: }`. A `.` counts unless it starts a spread
     * (`...$items`) or a decimal (`.5`); a trailing comma (`[1, 2,]`) is valid and not counted;
     * String and regex literals are blanked first (`':)'`, `split(/[,;]/)`), so their contents
     * never count.
     */
    private val ORPHANS =
        Regex(
            "(?:^|[;(])\\s*(\\+\\+|--)(?=\\s*(?:;|$))" +
                "|[(\\[{,](?:\\s*)(===|!==|==|!=|<=|>=|&&|\\|\\||\\?\\?|[=?:*%<>,]|\\.(?![.\\d]))" +
                "|(?<![+\\-])([:=*%<>&|?!@]|[+\\-](?![+\\-]))(?:\\s*)(?=[)\\]},])",
        )

    /**
     * The expression with every string, regex literal and comment blanked to a harmless operand,
     * read left to right as JavaScript reads it, so a quote inside a regex (`/'/`) opens no string
     * and `//` inside a string opens no comment. A regex can only follow the start, an opening
     * bracket or an operator, never an operand: `split(/[,;]/)`, `= /(?:a|b)/g`, but `a / b`.
     */
    private fun blankLiterals(t: String): String {
        val out = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    i = endOfString(t, i)
                    out.append("'s'")
                }

                c == '/' && t.getOrNull(i + 1) == '/' -> {
                    i = t.indexOf('\n', i).let { if (it < 0) t.length else it }
                    out.append(' ')
                }

                c == '/' && t.getOrNull(i + 1) == '*' -> {
                    i = t.indexOf("*/", i + 2).let { if (it < 0) t.length else it + 2 }
                    out.append(' ')
                }

                c == '/' && regexCanStart(out) && endOfRegex(t, i) > 0 -> {
                    i = endOfRegex(t, i)
                    out.append("'r'")
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString().trim()
    }

    private fun endOfString(
        t: String,
        start: Int,
    ): Int {
        val quote = t[start]
        var i = start + 1
        while (i < t.length) {
            when (t[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                else -> i++
            }
        }
        return t.length
    }

    private fun regexCanStart(before: StringBuilder): Boolean {
        val last = before.lastOrNull { !it.isWhitespace() } ?: return true
        return last in "(,=:[!&|?{};>+-*%<~^"
    }

    /** One past a regex literal's flags, or -1 when the slash at [start] opens none. */
    private fun endOfRegex(
        t: String,
        start: Int,
    ): Int {
        var i = start + 1
        var inClass = false
        while (i < t.length) {
            when (t[i]) {
                '\\' -> {
                    i += 2
                    continue
                }

                '\n' -> {
                    return -1
                }

                '[' -> {
                    inClass = true
                }

                ']' -> {
                    inClass = false
                }

                '/' -> {
                    if (!inClass) {
                        if (i == start + 1) return -1
                        var end = i + 1
                        while (end < t.length && t[end] in "dgimsuvy") end++
                        return end
                    }
                }
            }
            i++
        }
        return -1
    }

    /** Returns [expression] untouched, or throws [InterpolatedExpressionException]. */
    public fun check(expression: String): String = check(expression, null)

    /**
     * Returns [expression] untouched, or throws [InterpolatedExpressionException] that names the
     * [attribute] the expression was found in.
     */
    public fun check(
        expression: String,
        attribute: String?,
    ): String {
        if (EMPTY_SUBSTITUTION.containsMatchIn(expression)) {
            throw InterpolatedExpressionException(expression, "has a \${} with nothing in it", attribute)
        }
        val t = blankLiterals(expression.trim())
        if (t.isEmpty()) throw InterpolatedExpressionException(expression, "is empty", attribute)
        if (ONLY_BRACKETS.matches(t)) return expression
        if (ONLY_OPERATORS.matches(t) || ONLY_KEYWORDS.matches(t)) {
            throw InterpolatedExpressionException(expression, "contains no operands, only operators", attribute)
        }
        if (LEADING_BINARY.containsMatchIn(t)) {
            throw InterpolatedExpressionException(expression, "starts with '${t[0]}', which needs something on its left", attribute)
        }
        // Each statement on its own, so `@post('/x'); $done = true` without its signal is caught too.
        // A closing bracket may open a statement and an opening one end it: `if (a) { b; }`, `for (;;)`.
        for (statement in t
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .drop(1)) {
            NEEDS_LEFT.firstOrNull { statement.startsWith(it) && it !in CLOSERS && !(it == "." && statement.startsWith("...")) }?.let {
                throw InterpolatedExpressionException(
                    expression,
                    "has a statement that starts with '$it', which needs something on its left",
                    attribute,
                )
            }
        }
        KEYWORD_NEEDS_LEFT.find(t)?.let {
            throw InterpolatedExpressionException(expression, "starts with '${it.value}', which needs something on its left", attribute)
        }
        val leadingDotIsFine = t.startsWith("...") || (t.startsWith(".") && t.getOrNull(1)?.isDigit() == true)
        NEEDS_LEFT.firstOrNull { t.startsWith(it) && !(it == "." && leadingDotIsFine) }?.let {
            throw InterpolatedExpressionException(expression, "starts with '$it', which needs something on its left", attribute)
        }
        val tail = t.trimEnd(';')
        if (!(tail.endsWith("++") || tail.endsWith("--"))) {
            // `1.` is a number, the one place a trailing dot needs nothing after it.
            val number = tail.endsWith(".") && tail.dropLast(1).lastOrNull()?.isDigit() == true
            NEEDS_RIGHT.firstOrNull { tail.endsWith(it) && !(it == "." && number) }?.let {
                throw InterpolatedExpressionException(expression, "ends with '$it', which needs something on its right", attribute)
            }
            KEYWORD_NEEDS_RIGHT.find(tail)?.let {
                throw InterpolatedExpressionException(expression, "ends with '${it.value}', which needs something on its right", attribute)
            }
        }
        ORPHANS.find(t)?.let {
            throw InterpolatedExpressionException(
                expression,
                "has a dangling '${it.groupValues.drop(1).first { g -> g.isNotEmpty() }.trim()}' with nothing to apply it to",
                attribute,
            )
        }
        return expression
    }
}
