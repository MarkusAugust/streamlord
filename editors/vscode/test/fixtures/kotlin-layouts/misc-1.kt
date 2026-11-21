import org.intellij.lang.annotations.Language

// rev309/cases1: b296_inc
object Case23 {
fun f(n: Int) {
    var k = n
    val a = k++
    val title = "<b>$a</b>"
}
}

// rev309/cases1: b296_is_in
object Case31 {
fun f(n: Any, xs: List<Int>): String {
    val a = n is
        String
    val b = n in
        xs
    val c = n !in
        xs
    val d = n !is
        Int
    return "<p>$a $b $c $d</p>"
}
}

// rev309/cases1: b296_catchup_call
object Case34 {
fun catchUp(a: String) {}
fun f(n: String) {
    val a = run { n }
    catchUp("<p>$a</p>")
}
}

// rev309/cases1: b296_finally_name
object Case35 {
fun finallyDo(a: String) {}
fun f(n: String) {
    val a = run { n }
    finallyDo("<p>$a</p>")
}
}

// rev309/cases1: b296_arrow_end
object Case38 {
fun f(n: Int): String {
    val g = { x: Int ->
        x + 1
    }
    val h: (Int) -> Int = { it }
    return "<p>${g(n)}</p>"
}
}

// rev309/cases1: b296_greater_number
object Case42 {
fun f(n: Int) {
    val a = n>
        3
    val t = "<b>$a</b>"
}
}

// rev309/cases1: c283_expr_anonymous_fun
object Case69 {
fun outer(n: String) {
    val g = fun(n: Int) = n + 1
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_returns_block_next
object Case73 {
fun outer(n: String): String {
    fun size(n: Int) = n
    return "<p>$n</p>"
}
}

// rev309/cases1: c283_member_expression
object Case76 {
class A(val n: String) {
    fun size(n: Int) = pad(n)
    fun show() = "<p>$n</p>"
}
}

// rev309/cases3: o_backtick_names
object Case111 {
fun f(`in`: String, rows: List<Int>) {
    val a = `in`
    val b = "<p>$a</p>"
}
}

// rev309/cases3: o_while_window_start
object Case112 {
    fun case112() {
if(x)
    y
    }
}

// rev309/cases3: o_window_start_spaces
object Case113 {
    fun case113() {
xwhile       (x)
    val a = 1
    }
}

// rev309/cases3: o_label_at_call
object Case117 {
fun f(rows: List<String>) = rows.forEach { return@forEach(g("<i>label</i>")) }
}

// rev309/cases3: o_long_line_close
object Case124 {
fun f(n: String) {
    val a = foo(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50)
    val b = "<p>$n</p>"
}
}

// rev309/cases3: o_char_lits
object Case126 {
fun f(n: String) {
    val a = '>'
    val b = '!'
    val c = '-'
    val d = "<p>$n</p>"
}
}

// rev309/cases4: p_value_ends_paren_name_with_if
object Case141 {
fun outer(n: String) {
    val a = elif(n)
    val b = "<p>$a</p>"
    val c = notif (n)
    val d = "<p>$c</p>"
}
}

// rev309/cases4: p_catch_after_non_try_block
object Case147 {
fun outer(n: String) {
    val a = run {
        n
    }
    catchAll(n)
    val b = "<p>$a</p>"
}
}

// rev309/cases5: q_value_then_literal_call
object Case183 {
fun f(n: String) = run {
    val a = 1
    "<p>$a</p>".also(::println)
}
}

// rev309/cases6: s_expr_then_literal_call
object Case192 {
fun outer(n: String) = run {
    fun size(n: Int) = -n
    "<p>$n</p>".also(::println)
}
}

// fresh309-a7f0/a.cases #6
object Case272 {
fun a(b: Int, c: Int, d: Int, e: Int, name: String) {
    val x = b<c && d>
        e
    println("<p>$name $x</p>")
}
}

// fresh309-a7f0/a.cases #7
object Case273 {
fun a(b: Int, c: Int, d: Int, e: Int, name: String) {
    val x = b < c && d >
        e
    println("<p>$name $x</p>")
}
}

// fresh309-a7f0/a.cases #8
object Case274 {
fun a(i: Int, n: Int, j: Int, m: Int, name: String) {
    val inside = i<n && j>
        m
    val title = "<b>$name</b>"
}
}

// fresh309-a7f0/a.cases #86
object Case352 {
class A {
    var count = 0
    val x = count++
    val y: String
        get() = "<p>$x</p>"
}
}

// fresh309-a7f0/a.cases #88
object Case354 {
class A {
    val x = check(ok())
    val y: String
        get() = "<p>$x</p>"
}
}

// fresh309-a7f0/a.cases #89
object Case355 {
class A {
    val x by lazy { load() }
    val y: String
        get() = "<p>$x</p>"
}
}

// fresh309-a7f0/a.cases #91
object Case357 {
fun f(
    a: String,
    b: Int
): String = "<p>$a $b</p>"
}

// fresh309-a7f0/a.cases #95
object Case361 {
fun f(n: Int) {
    val a = n
    -1
    val b = "<b>$a</b>"
}
}

// fresh309-a7f0/a.cases #137
object Case403 {
fun f(content: @Composable (String) -> Unit) = content("Cmp2 <b>x</b>")
}

// fresh309-a7f0/b.cases #10
object Case416 {
fun outer(name: String, flag: Boolean) {
    val shown = name
    catch_("<p>$shown</p>")
}
}

// fresh309-a7f0/b.cases #12
object Case418 {
fun cmp(lo: Int, hi: Int, x: Int, name: String) {
    val inside = lo<x && x>
        hi
    println("<p>$name $inside</p>")
}
}

// fresh309-a7f0/b.cases #21
object Case427 {
interface I {
    fun inner(n: Int): Int
    fun other(n: String) = "<p>$n</p>"
}
}

// fresh309-a7f0/b.cases #22
object Case428 {
abstract class A(val n: String) {
    abstract fun inner(n: Int): Int
    val html get() = "<p>$n</p>"
}
}

// fresh309-a7f0/b.cases #23
object Case429 {
abstract class A(val n: String) {
    abstract fun inner(n: Int): Int
    init { println("<p>$n</p>") }
}
}

// fresh309-a7f0/b.cases #24
object Case430 {
class A(val n: String) {
    constructor(n: Int) : this(n.toString())
    val html = "<p>$n</p>"
}
}

// fresh309-a7f0/b.cases #25
object Case431 {
class A(val n: String) {
    constructor(n: Int) : this(n.toString()) {
        println(n)
    }
    val html = "<p>$n</p>"
}
}

// fresh309-a7f0/b.cases #26
object Case432 {
fun outer(n: String) {
    val f = fun(n: Int) = n + 1
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #27
object Case433 {
fun outer(n: String) {
    val f = fun(n: Int) = pad(n)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #28
object Case434 {
fun outer(n: String) {
    val f = { n: Int -> pad(n) }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #44
object Case450 {
fun outer(n: String, xs: List<Int>) {
    xs.forEach { n -> println(n) }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #76
object Case482 {
fun f(a: Int, b: Int, name: String) {
    val c = a shr
        b
    val d = a xor b
    val e = a..b
    val g = a ..
        b
    val h = a ..< b
    println("<p>$name $c $d $e $g $h</p>")
}
}

// fresh309-a7f0/b.cases #77
object Case483 {
fun f(a: Int, name: String) {
    val e = a ..<
        5
    println("<p>$name $e</p>")
}
}

// fresh309-a7f0/b.cases #78
object Case484 {
fun f(a: Any, name: String) {
    val e = a is List<*>
    val g = a !is Map<*, *>
    println("<p>$name $e $g</p>")
}
}

// fresh309-a7f0/b.cases #80
object Case486 {
fun f(name: String) {
    val e = name
        .length > 3
    val g = name.length>
        3
    println("<p>$name $e $g</p>")
}
}

// fresh309-a7f0/b.cases #81
object Case487 {
fun f(name: String) {
    val arrow = { x: Int ->
        x }
    val title = "<p>$name</p>"
}
}

// fresh309-a7f0/b.cases #84
object Case490 {
fun f(name: String) {
    val bang = !
        name.isEmpty()
    val neg = -
        1
    val title = "<p>$name</p>"
}
}

// fresh309-a7f0/b.cases #85
object Case491 {
fun f(name: String, i: Int) {
    var k = i
    val a = k--
    val b = k++
    val c = k -
        -1
    val title = "<p>$name $a $b $c</p>"
}
}

// fresh309-a7f0/b.cases #86
object Case492 {
fun f(name: String) {
    var k = 0
    val a = k+
        1
    val b = k+++
        1
    val title = "<p>$name $a $b</p>"
}
}

// fresh309-a7f0/b.cases #102
object Case508 {
fun f(name: String) {
    val x = name.`is`
    val title = "<p>$x</p>"
}
}

// fresh309-a7f0/b.cases #117
object Case523 {
@Deprecated("<p>deprecated</p>", level = DeprecationLevel.WARNING)
val page = "<p>$x</p>"
}

// fresh309-a7f0/b.cases #118
object Case524 {
class A {
    @get:JvmName("<b>x</b>")
    val b = "<i>$y</i>"
}
}

// fresh309-a7f0/b.cases #121
object Case527 {
fun f(email: String) = "mailto:$email@host(\"<b>x</b>\")"
}

// review-309/lines.kt: not-at-end
object Case534 {
fun f(flag: Boolean, other: Boolean) {
    val a = flag && !
        other
    val title = "<b>$a</b>"
}
}

// review-309/lines.kt: shift-gt
object Case541 {
fun f(a: Int) {
    val ok = a > 1 && a<2 || a>
        3
    println(ok)
}
}

// review-309/lines.kt: lambda-type
object Case542 {
fun f() {
    val g: () -> Unit = {
    }
    val h: (Int) -> List<Int> = { listOf(it) }
    val t = "<b>$h</b>"
}
}

// review-309/lines.kt: minus-continue
object Case545 {
fun f(a: Int, b: Int) {
    val x = a -
        b
    val y = a +
        b
    val z = a - -
        b
    println("$x $y $z")
}
}

// review-309/lines.kt: decrement-value
object Case546 {
fun f(a: Int) {
    var b = a
    val x = b--
    val y = "<i>$x</i>"
}
}

// review-309/lines.kt: is-end
object Case549 {
fun f(q: Any) {
    val a = q is
        String
    val t = "<b>$a</b>"
}
}

// review-309/lines.kt: in-end
object Case550 {
fun f(q: Int, r: List<Int>) {
    val a = q in
        r
    val t = "<b>$a</b>"
}
}

// review-309/lines.kt: name-ending-in-is
object Case551 {
fun f(this1: Int, axis: Int) {
    val a = axis
    val t = "<b>$a</b>"
}
}

// review-309/lines.kt: name-in
object Case552 {
fun f(margin: Int, pin: Int) {
    val a = margin
    val b = pin
    val t = "<b>$a$b</b>"
}
}

