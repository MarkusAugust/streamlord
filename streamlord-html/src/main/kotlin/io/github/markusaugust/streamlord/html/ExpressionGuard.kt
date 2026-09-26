package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.StreamlordException

/**
 * Raised when a Datastar expression bears the marks of a Kotlin string template that ate a
 * signal: `"$count++"` reaches the DSL as `"++"`, `"$open = !$open"` as `" = !"`, `"$count"` as
 * nothing at all. The browser would accept the attribute and silently do nothing. Streamlord
 * refuses instead, at render time, where the stack trace points at the line that wrote it.
 */
public class InterpolatedExpressionException(public val expression: String, reason: String) :
    StreamlordException(
        "Datastar expression \"$expression\" $reason. This is what a Kotlin string template leaves behind when " +
            "it interpolates a signal such as \$count. Write the signal with the helpers " +
            "(signal(\"count\"), increment(\"count\"), toggle(\"open\")), or make the string literal keep " +
            "its dollars: \$\$\"\$count++\" (Kotlin 2.2+) or \"\${'\$'}count++\".",
    )

/**
 * The wall against the `$` trap. Pure function: returns the expression unchanged, or throws
 * [InterpolatedExpressionException]. It only flags shapes that no working Datastar expression
 * has: nothing but operators, an operator with nothing on the side it needs, a leading member
 * access. Legitimate prefix forms such as `++$count`, `-1` or `!$open` pass.
 */
public object ExpressionGuard {
    /** Operators that need an operand on their left, longest first so `===` wins over `=`. */
    private val NEEDS_LEFT = listOf("===", "!==", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "=", "?", ":", ")", "]", "}", ".", "*", "/", "%", ">", ",")

    /** Operators that need an operand on their right, longest first. Postfix `++`/`--` are not here: `$count++` is fine. */
    private val NEEDS_RIGHT = listOf("===", "!==", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "=>", "=", "?", ":", "(", "[", "{", ".", "*", "/", "%", "<", ">", ",", "!", "@")

    private val ONLY_OPERATORS = Regex("^[\\s=!<>&|?:.+\\-*/%,()\\[\\]{}]*$")

    /** A statement that is nothing but `++` or `--`, or an operator that needs a left operand right after an opening `(`. */
    private val ORPHANS = Regex("(?:^|[\\s;(])(\\+\\+|--)(?=\\s*(?:;|$))|\\((?:\\s*)(===|!==|==|!=|<=|>=|&&|\\|\\||\\?\\?|[=?:*/%>,.])")

    public fun check(expression: String): String {
        val t = expression.trim()
        if (t.isEmpty()) throw InterpolatedExpressionException(expression, "is empty")
        if (ONLY_OPERATORS.matches(t)) throw InterpolatedExpressionException(expression, "contains no operands, only operators")
        NEEDS_LEFT.firstOrNull { t.startsWith(it) }?.let {
            throw InterpolatedExpressionException(expression, "starts with '$it', which needs something on its left")
        }
        val tail = t.trimEnd(';')
        if (!(tail.endsWith("++") || tail.endsWith("--"))) {
            NEEDS_RIGHT.firstOrNull { tail.endsWith(it) }?.let {
                throw InterpolatedExpressionException(expression, "ends with '$it', which needs something on its right")
            }
        }
        ORPHANS.find(t)?.let {
            throw InterpolatedExpressionException(expression, "has a dangling '${it.groupValues.drop(1).first { g -> g.isNotEmpty() }}' with nothing to apply it to")
        }
        return expression
    }
}
