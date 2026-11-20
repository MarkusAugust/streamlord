// rev309/cases1: b296_else_goes_on
object Case28 {
fun f(n: Int): String {
    val a = if (n > 0) "x" else
        "y"
    return "<p>$a</p>"
}
}

// rev309/cases1: b296_if_paren_goes_on
object Case29 {
fun f(n: Int): String {
    val a = if (n > 0)
        "x"
    else
        "y"
    return "<p>$a</p>"
}
}

// rev309/cases1: b296_when_paren_goes_on
object Case30 {
fun f(n: Int): String {
    val a = when (n)
    {
        1 -> "one"
        else -> "many"
    }
    return "<p>$a</p>"
}
}

// rev309/cases1: c283_expr_when
object Case50 {
fun outer(n: String) {
    fun size(n: Int) = when (n) {
        1 -> "a"
        else -> "b"
    }
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_if_else
object Case51 {
fun outer(n: String) {
    fun size(n: Int) = if (n > 0) "a"
        else "b"
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_if_next_line
object Case52 {
fun outer(n: String) {
    fun size(n: Int) =
        if (n > 0) "a" else "b"
    println("<p>$n</p>")
}
}

// rev309/cases2: r_when_is
object Case90 {
fun describe(x: Any, title: String): String {
    val kind = when (x) {
        is String -> "s"
        is Int -> "i"
        in listOf(1, 2) -> "l"
        else -> "o"
    }
    val ok = x is
        String
    return "<p>$title $kind $ok</p>"
}
}

// rev309/cases3: o_else_if_chain_value
object Case129 {
fun f(n: Int): String {
    val a = if (n > 0) "a"
    else if (n < 0) "b"
    else "c"
    return "<p>$a</p>"
}
}

// rev309/cases3: o_for_in_if_cond_val
object Case130 {
fun f(n: Int, xs: List<Int>): String {
    val a = if (n in
        xs) "a" else "b"
    return "<p>$a</p>"
}
}

// rev309/cases4: p_expr_ends_with_if_inside_call
object Case140 {
fun outer(n: String) {
    fun f(n: Int) = listOf(1).filter { if (it > n) true else false }
    println("<p>$n</p>")
}
}

// rev309/cases4: p_value_when_paren_newline_brace
object Case144 {
fun outer(n: Int): String {
    val a = when (n)
    {
        1 -> "<b>one</b>"
        else -> "x"
    }
    return a
}
}

// rev309/cases4: p_expr_if_paren_newline_brace
object Case145 {
fun outer(n: String) {
    fun f(n: Int) = if (n > 0)
    {
        "a"
    } else {
        "b"
    }
    println("<p>$n</p>")
}
}

// rev309/cases6: s_when_allman
object Case188 {
fun outer(n: String): String {
    val a = when
    {
        n.isEmpty() -> "x"
        else -> "y"
    }
    return "<p>$a</p>"
}
}

// fresh309-a7f0/a.cases #33
object Case299 {
fun f(n: Int) = if (n > 0)
    "<b>$n</b>"
else
    "<i>$n</i>"
fun g(n: String) = "<p>$n</p>"
}

// fresh309-a7f0/a.cases #40
object Case306 {
fun outer(n: String) {
    fun inner(n: Int) = when (n) {
        1 -> "one"
        else -> "x"
    }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #41
object Case307 {
fun outer(n: String) {
    fun inner(n: Int) = when {
        n > 1 -> "one"
        else -> "x"
    }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #53
object Case319 {
fun outer(n: String) {
    fun inner(n: Int) = if (n > 0) n else
        -n
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #73
object Case339 {
fun outer(n: String, flag: Boolean) {
    val x = flag
    if (x)
        println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #74
object Case340 {
fun outer(n: String, flag: Boolean) {
    val x = if (flag) 1 else 2
    println("<p>$n $x</p>")
}
}

// fresh309-a7f0/a.cases #75
object Case341 {
fun outer(n: String, flag: Boolean) {
    val x = if (flag)
        1
    else
        2
    println("<p>$n $x</p>")
}
}

// fresh309-a7f0/a.cases #76
object Case342 {
fun outer(n: String, flag: Boolean) {
    val x = when (flag)
    {
        true -> 1
        else -> 2
    }
    println("<p>$n $x</p>")
}
}

// fresh309-a7f0/a.cases #87
object Case353 {
class A {
    val x = if (ok()) a() else b()
    val y: String
        get() = "<p>$x</p>"
}
}

// fresh309-a7f0/a.cases #92
object Case358 {
fun <T : Comparable<T>> maxOf2(a: T, b: T): T = if (a > b) a else b
fun g(a: String) = "<p>$a</p>"
}

// fresh309-a7f0/a.cases #106
object Case372 {
fun x(name: String) {
    val greeting = if (name.isEmpty())
        "Hi"
    else name
    println("<p>$greeting</p>")
}
}

// fresh309-a7f0/b.cases #40
object Case446 {
fun outer(n: String) {
    fun inner(n: Int) = if (n > 0) "a"
    else "b"
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #41
object Case447 {
fun outer(n: String) {
    fun inner(n: Int) = if (n > 0) "a" else if (n < 0) "b"
    else "c"
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #42
object Case448 {
fun outer(n: String) {
    fun inner(n: Int) = when (n) { 1 -> "a"; else -> "b" }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #88
object Case494 {
fun f(name: String) {
    val w = when (name) {
        "a" -> 1
        else -> 2
    }
    val title = "<p>$name $w</p>"
}
}

// fresh309-a7f0/b.cases #89
object Case495 {
fun f(name: String) {
    val w = if (name.isEmpty()) {
        1
    }
    else {
        2
    }
    val title = "<p>$name $w</p>"
}
}

// fresh309-a7f0/b.cases #90
object Case496 {
fun f(name: String) {
    val w = if (name.isEmpty()) {
        1
    } else if (name.length > 3)
    {
        2
    } else
        3
    val title = "<p>$name $w</p>"
}
}

// fresh309-a7f0/b.cases #95
object Case501 {
fun f(name: String) {
    val x = foo(name)
    if (x)
    {
        println("<p>$name</p>")
    }
}
}

// review-309/lines.kt: arrow-end
object Case543 {
fun f(x: Int) = when (x) {
    1 ->
        "<b>one</b>"
    else ->
        "two"
}
}

// review-309/lines.kt: else-end
object Case547 {
fun f(ok: Boolean) {
    val a = if (ok) 1 else
        2
    val t = "<b>$a</b>"
}
}

// review-309/lines.kt: if-cond-end
object Case554 {
fun f(ok: Boolean, name: String) {
    val a = if (ok)
        "yes"
    else
        "no"
    val t = "<b>$a $name</b>"
}
}

// review-309/lines.kt: call-paren-end
object Case555 {
fun f(name: String) {
    val a = check(name)
    val b = notify(name)
    val c = modif(name)
    val d = `when`(name)
    val t = "<b>$a$b$c$d</b>"
}
}

// review-309/lines.kt: fun-expr-if-multiline
object Case573 {
fun outer(n: String) {
    fun size(n: Int) = if (n > 0)
        n
    else
        0
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-when
object Case574 {
fun outer(n: String) {
    fun size(n: Int) = when (n) {
        1 -> 1
        else -> 2
    }
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-when-next-line-brace
object Case575 {
fun outer(n: String) {
    fun size(n: Int) = when (n)
    {
        1 -> 1
        else -> 2
    }
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-if-else-next-line
object Case578 {
fun outer(n: String) {
    fun size(n: Int) = if (n > 0) 1
        else 2
    println("<p>$n</p>")
}
}

// review-309/lines.kt: paren-end-long
object Case598 {
fun f(a: Int) {
    val g = if (aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa > 0)
        1
    else 2
    val t = "<b>$g</b>"
}
}

// review-309/lines.kt: if-guard-statement
object Case599 {
fun f(a: Int, title: String) {
    if (a > 0)
        return
    val x = title
    println("<b>$x</b>")
}
}

// review-309/lines.kt: when-subject-next-line
object Case600 {
fun f(a: Int) {
    val x = when (a)
    {
        1 -> "one"
        else -> "two"
    }
    println("<b>$x</b>")
}
}

// review-309/lines.kt: if-in-string
object Case602 {
fun f(a: Int) {
    val x = foo("if (a)")
    val t = "<b>$x</b>"
}
}

// r5review/cases: d13_expr
object Case714 {
fun outer(n: String) {
    fun size(n: Int) = run {
        n + 1
    }
    println("<p>$n</p>")
    fun two(n: Int) =
        // note
        n + 1
    println("<i>$n</i>")
    fun three(n: Int) = n
        .plus(1)
    println("<b>$n</b>")
    fun four(n: Int) = n ?:
        0
    println("<u>$n</u>")
    fun five(n: Int) = if (n > 0) n
        else 0
    println("<s>$n</s>")
    fun six(n: Int) = "x" + n
    println("<em>$n</em>")
}
}

// r5review/cases: d17_lambda_if_value
object Case718 {
fun f(n: String, b: Boolean) {
    val a = run { if (b) 1 else 2 }
    val title = "<b>$n</b>"
    val c = listOf(1).map { if (it > 0)
        it else 0 }
    println("<p>$n</p>")
}
}

// r7/hand: if_else_next
object Case766 {
fun page(n: Int, ok: Boolean): String {
    val v = if (ok) "a"
    else "b"
    return "<p>$v</p>"
}
}

// r7/probes6: h07f-semicolon
object Case882 {
open class P {
    val a = if (c) open else shut; val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h07p-when-entry
object Case892 {
open class P {
    val a = when (x) { open -> 1; else -> 2 }
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-if-else-infix
object Case953 {
open class P {
    val a = if (c) { 1 } else { 2 } open d
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-when-entry-word-first
object Case968 {
fun a(x: Any, open: Any) {
    when (x) { 1 -> { } open -> { } }
    val title = "<b>x</b>"
    println("<p>$title</p>")
}
}

// r7/probes8: p2-when-entry-word
object Case969 {
open class P {
    fun a(x: Int) = when (x) { 1 -> { } else -> open }
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-when-infix
object Case970 {
open class P {
    val a = when (x) { else -> 1 } inner d
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

