import org.intellij.lang.annotations.Language

annotation class P66(val s: String = "")

@Language("HTML")
fun p66a(c: Boolean): String {
    val s: List<String>? = null
    if (c) return "K01 <p>a</p>" else println("K02 x")
    val t = s as List<String>
    if (c) return "K03 <p>b</p>"
    return "K04 <p>c</p>" + t
}

@Language("HTML")
fun p66b(c: Boolean): String {
    @P66("K05 x")
    val q = 1
    if (c) return "K06 <p>a</p>" else return "K07 <p>b</p>" + q
}

@Language("HTML")
fun p66c(c: Boolean, n: Int): String {
    val s = n.inc()
    if (c) return "K08 <p>a</p>"
    val t = -n
    if (c) return "K09 <p>b</p>"
    val u = arrayOf(1)[0]
    if (c) return "K10 <p>c</p>"
    return "K11 <p>d</p>" + s + t + u
}

@Language("HTML")
fun p66d(c: Boolean): String {
    val f = { "K12 a" }
    if (c) return "K13 <p>a</p>"
    val g = c.not()
    if (g) return "K14 <p>b</p>"
    return f()
}

@Language("HTML")
fun p66e(c: Boolean, n: Int): String {
    val s = n in 1..2
    if (s) return "K15 <p>a</p>"
    val t = n !in 1..2
    if (t) return "K16 <p>b</p>"
    val u = 1 shl 2
    if (c) return "K17 <p>c</p>"
    return "K18 <p>d</p>" + u
}

@Language("HTML")
fun p66f(c: Boolean, n: Int): String {
    val s = n ==
        if (c) return "K19 <p>a</p>" else 1
    val t = n .. if (c) return "K20 <p>b</p>" else 2
    return "K21 <p>c</p>" + s + t
}

@Language("HTML")
fun p66g(c: Boolean, n: Int): String {
    var s = n
    s +=
        if (c) return "K22 <p>a</p>" else 1
    s =
        if (c) return "K23 <p>b</p>" else 2
    return "K24 <p>c</p>" + s
}

@Language("HTML")
fun p66h(c: Boolean, d: Boolean): String {
    if (c) if (d) return "K25 <p>a</p>" else return "K26 <p>b</p>"
    else return "K27 <p>c</p>"
}

@Language("HTML")
fun p66i(c: Boolean, d: Boolean): String {
    val r = if (c) "K28 a" else if (d) return "K29 <p>b</p>" else return "K30 <p>c</p>"
    return r
}

@Language("HTML")
fun p66j(c: Boolean, d: Boolean): String {
    return if (c) return "K31 <p>a</p>" else if (d) "K32 <p>b</p>" else return "K33 <p>c</p>"
}
