// rev309/cases1: b296_comparison_goes_on
object Case24 {
fun f(n: Int, title: String) {
    val big = n >
        3
    val html = "<p>$title</p>"
}
}

// rev309/cases1: b296_lt_goes_on
object Case25 {
fun f(n: Int, m: Int) {
    val small = n <
        m
    println(small)
}
}

// rev309/cases1: b296_generic_call_value
object Case26 {
fun f(m: Map<String, Int>) {
    val x = m.getValue("a") as Comparable<Int>
    val y = "<b>$x</b>"
}
}

// rev309/cases1: b296_nested_generic_end
object Case39 {
fun f(m: Any): String {
    val a = m as Map<String, List<Pair<Int, String?>>>
    val title = "<p>x</p>"
    return title
}
}

// rev309/cases1: b296_shift_compare
object Case40 {
fun f(a: Int, b: Int, c: Int): Boolean {
    val x = a<b || c>
        b
    return x
}
}

// rev309/cases1: b296_typeargs_with_star
object Case41 {
fun f(m: Any) {
    val a = m as List<*>
    val b = m as Array<out Any?>
    val t = "<b>x</b>"
}
}

// rev309/cases2: r_generics_comparisons
object Case85 {
class Box<T : Comparable<T>>(val value: T) {
    fun max(other: Box<T>): Box<T> = if (value > other.value) this else other
    fun isBig(limit: T) = value > limit
    fun <R> map(f: (T) -> R): R = f(value)
}

fun compare(a: Int, b: Int, title: String): String {
    val less = a < b
    val more = a > b
    val both = a < b && b > 0
    val maybe: Int? = if (less) a else null
    val forced = maybe!!
    val list: List<Int?> = listOf(maybe)
    val first = list.first() as Int?
    return "<p>$title $less $more $both $forced $first</p>"
}
}

// rev309/cases3: o_generic_call_eol
object Case120 {
fun f() {
    val a = listOf<Int>
    val b = "<b>x</b>"
}
}

// rev309/cases3: o_comparison_vs_generic
object Case121 {
fun f(a: Int, b: Int, c: Int, d: Int) {
    val x = a < b == c > d
    val y = a<b && c>
        d
}
}

// rev309/cases3: o_string_gt_end
object Case125 {
fun f(n: String) {
    val a = "<b>"
    val b = "</b>"
}
}

// rev309/cases4: p_expr_generic_return
object Case163 {
fun outer(x: String) {
    fun cast(x: Any) = x as List<String>
    println("<p>$x</p>")
}
}

// rev309/cases4: p_generic_class
object Case175 {
class Repo<T : Any, ID : Comparable<ID>>(private val items: MutableMap<ID, T>) {
    fun find(id: ID): T? = items[id]
    fun all(): List<T> = items.values.toList()
    fun <R> map(f: (T) -> R): List<R> = all().map(f)
    fun has(id: ID) = id in items
    fun html(id: ID) = "<p>$id</p>"
}
}

// rev309/cases4: p_comparisons_everywhere
object Case176 {
fun grade(score: Int, name: String): String {
    val pass = score >= 50
    val top = score > 90 &&
        score < 100
    val low = score < 10 ||
        score > 1000
    val label = if (score > 90) "top"
        else if (score >= 50) "pass"
        else "fail"
    return "<p>$name $pass $top $low $label</p>"
}
}

// fresh309-a7f0/a.cases #43
object Case309 {
fun outer(n: String) {
    fun <T> inner(n: T) = listOf<T>(n)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #74
object Case480 {
fun main() {
    val map = mutableMapOf<String, Int>()
    map["a"] = map["a"]!! + 1
    val m = map as MutableMap<String, Int?>
    val t = map.toList() as List<Pair<String, Int>>
    val html = "<p>$m $t</p>"
}
}

// fresh309-a7f0/b.cases #87
object Case493 {
fun f(name: String) {
    val t = Triple<Int, Int, Int>(1, 2, 3)
    val u = listOf<
        Int>(1)
    val title = "<p>$name $t $u</p>"
}
}

// review-309/lines.kt: generic-type
object Case536 {
fun f(q: Any?) {
    val a = q as List<String>
    val title = "<b>$a</b>"
}
}

// review-309/lines.kt: nested-generic
object Case537 {
fun f(q: Any?) {
    val a = q as Map<String, List<Int>>
    val title = "<b>$a</b>"
}
}

// review-309/lines.kt: compare-gt
object Case539 {
fun f(a: Int, b: Int) {
    val ok = a >
        b
    val title = "<b>$ok</b>"
}
}

// review-309/lines.kt: compare-lt-gt-line
object Case540 {
fun f(a: Int, b: Int, c: Int) {
    val ok = a<b && c>
        b
    println(ok)
}
}

// review-309/loops.kt: fun-expr-generic-call
object Case616 {
fun outer(n: String) {
    fun size(n: Int) = listOf<Int>(n)
    println("<p>$n</p>")
}
}

// r7/hand: compare_and
object Case747 {
fun page(n: Int, a: Int): String {
    val c = n<a && n>
        a
    val w = n
    return "<p>$w $c</p>"
}
}

// r7/hand: compare_div
object Case748 {
fun page(n: Int, a: Int): String {
    val c = (n<a/b) == (m>
        a)
    return "<p>$c</p>"
}
}

// r7/hand: compare_eq
object Case749 {
fun page(n: Int, a: Int): String {
    val c = n<a == b>
        a
    return "<p>$c</p>"
}
}

// r7/hand: compare_minus
object Case750 {
fun page(n: Int, a: Int): String {
    val c = n<a - 1 && n>
        a
    return "<p>$c</p>"
}
}

// r7/hand: compare_or
object Case751 {
fun page(n: Int, a: Int): String {
    val c = n<a || n>
        a
    return "<p>$c</p>"
}
}

// r7/hand: compare_times_gt
object Case752 {
fun page(n: Int, a: Int): String {
    val c = x<y * z>
        a
    return "<p>$c</p>"
}
}

// r7/hand: generic_call_with_eq_in_lambda
object Case764 {
fun page(n: Int): String {
    val c = foo<Bar>
    val w = n
    return "<p>$w</p>"
}
}

// r7/hand: type_args_fun_type
object Case793 {
fun page(n: Int): String {
    val m: Map<String, () -> Unit>
    = emptyMap()
    val v: List<String>
    val w = n
    return "<p>$w</p>"
}
}

// r7/hand: type_args_fun_type_value
object Case794 {
fun page(n: Int): String {
    val m = mapOf<String, () -> Unit>
    val w = n
    return "<p>$w</p>"
}
}

