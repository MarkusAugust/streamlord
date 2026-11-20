// rev309/cases1: b296_elvis_next
object Case37 {
fun f(n: String?): String {
    val a = n
        ?: "none"
    return "<p>$a</p>"
}
}

// rev309/cases2: r_infix
object Case91 {
fun pairs(a: String, b: String): String {
    val p = a to b
    val r = 0 until 10
    val s = 1 shl 3
    return "<p>$p $r $s</p>"
}
}

// rev309/cases4: p_infix_expr_body
object Case135 {
val name = "x"
fun pair(name: Int) = name to
    "<b>$name</b>"
}

// rev309/cases4: p_infix_expr_body_local
object Case136 {
fun outer(name: String) {
    fun pair(name: Int) = name to
        "<b>$name</b>"
    println(pair(1))
}
}

// rev309/cases4: p_infix_and_or
object Case137 {
fun outer(name: String) {
    fun ok(name: Int) = name.isPositive() and
        "<b>$name</b>".isEmpty()
}
}

// rev309/cases4: p_expr_elvis_return
object Case166 {
fun outer(x: String) {
    fun get(x: String?) = x
        ?: return
    println("<p>$x</p>")
}
}

// rev309/cases5: q_infix_until_expr_local
object Case185 {
fun outer(n: String) {
    fun range(n: Int) = n until
        "<b>$n</b>".length
    println(range(1))
}
}

// fresh309-a7f0/a.cases #44
object Case310 {
fun outer(n: String) {
    fun inner(n: Int) = n to
        1
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #94
object Case360 {
fun f() {
    val x = 1
    val y = x shl
        2
    val z = x until
        3
}
}

// fresh309-a7f0/b.cases #34
object Case440 {
fun outer(n: String) {
    fun inner(n: Int?) = n
        ?: 0
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-elvis-next-line
object Case579 {
fun outer(n: String) {
    fun size(n: Int?) = n
        ?: 0
    println("<p>$n</p>")
}
}

// r7/hand: elvis_next
object Case756 {
fun page(n: Int?): String {
    val v = n
        ?: 3
    return "<p>$v</p>"
}
}

// r7/probes6: h07t-anon-fun-infix
object Case896 {
open class P {
    val a = b open fun() = 1
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes6: h07u-object-infix
object Case897 {
open class P {
    val a = b open object : R { }
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes7: m3-infix
object Case938 {
fun a() = b private const val title = "<b>x</b>"
fun page() = "<p>$title</p>"
}

// r7/probes8: p2-lambda-infix-fun
object Case957 {
open class P {
    val a = b { } open fun() = 1
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-lambda-infix-in-call
object Case958 {
open class P {
    val a = f(b { } open value)
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-lambda-infix-lambda
object Case959 {
open class P {
    val a = b { } open { }
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-lambda-infix-object
object Case960 {
open class P {
    val a = b { } open object : R { }
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-let-infix-newline-operand
object Case961 {
open class P {
    val a = b.let { } open
        c
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-let-infix-two
object Case963 {
open class P {
    val a = b.let { } open value
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-let-infix-word
object Case964 {
open class P {
    val a = b.let { } open c
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-object-infix
object Case965 {
open class P {
    val a = object : R { } open d
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

// r7/probes8: p2-template-infix
object Case966 {
open class P {
    val a = "${b}" open d
    val title = "<b>x</b>"
    fun page() = "<p>$title</p>"
}
}

