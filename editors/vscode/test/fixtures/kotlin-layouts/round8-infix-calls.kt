// review309/cases/c23
object Case15 {
val a = "<b>$x</b>" to
"<p>$y</p>"
}

// review309/cases/infix
object Case39 {
fun f(a: Int, b: Int, name: String) {
    val p = a to
        b
    val q = a to b
    val r = mapOf("x" to
        "y")
    val s = a shl
        2
    val t = a until b step
        2
    val u = a until b step 2
    val v = name
    println("<p>$v</p>")
    val w = a downTo
        0
    val x = name matches
        Regex("x")
    val y = "<b>" + name
    val z = a and b
    val zz = a or
        b
    val yy = name.length to
        "<b>x</b>"
    val xx = this to
        name
    val ww = (a) to
        b
    val vv = listOf(a)[0] to
        b
    val uu = name ?: a
    val tt = null to
        b
}
fun g(step: String) {
    val current = step
    val title = "<h1>x</h1>"
}
fun h(a: Int) = a to
    "<b>$a</b>"
fun k(a: Int) = a
fun m(): String = "<p>${k(1)}</p>"
fun n(name: String) {
    val v = yield
    val title = "<h1>$v</h1>"
}
}

// review309/cases3/v_return_value
object Case66 {
fun f(n: String): String {
    fun g(n: Int): String = return "x"
    return "<p>$n</p>"
}
fun h(q: String?): String {
    val a = q ?: return ""
    val t = "<b>$a</b>"
    return t
}
}

