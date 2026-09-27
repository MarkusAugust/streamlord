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
        listOf("===", "!==", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "=", "?", ":", ")", "]", "}", ".", "*", "/", "%", ">", ",")

    /** Operators that need an operand on their right, longest first. Postfix `++`/`--` are not here: `$count++` is fine. */
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
        )

    private val ONLY_OPERATORS = Regex("^[\\s=!<>&|?:.+\\-*/%,()\\[\\]{}]*$")

    /** A statement that is nothing but `++` or `--`, or an operator that needs a left operand right after an opening `(`. */
    private val ORPHANS = Regex("(?:^|[\\s;(])(\\+\\+|--)(?=\\s*(?:;|$))|\\((?:\\s*)(===|!==|==|!=|<=|>=|&&|\\|\\||\\?\\?|[=?:*/%>,.])")

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
        val t = expression.trim()
        if (t.isEmpty()) throw InterpolatedExpressionException(expression, "is empty", attribute)
        if (ONLY_OPERATORS.matches(t)) throw InterpolatedExpressionException(expression, "contains no operands, only operators", attribute)
        NEEDS_LEFT.firstOrNull { t.startsWith(it) }?.let {
            throw InterpolatedExpressionException(expression, "starts with '$it', which needs something on its left", attribute)
        }
        val tail = t.trimEnd(';')
        if (!(tail.endsWith("++") || tail.endsWith("--"))) {
            NEEDS_RIGHT.firstOrNull { tail.endsWith(it) }?.let {
                throw InterpolatedExpressionException(expression, "ends with '$it', which needs something on its right", attribute)
            }
        }
        ORPHANS.find(t)?.let {
            throw InterpolatedExpressionException(
                expression,
                "has a dangling '${it.groupValues.drop(1).first { g -> g.isNotEmpty() }}' with nothing to apply it to",
                attribute,
            )
        }
        return expression
    }
}
