import org.intellij.lang.annotations.Language

infix fun String.cat(o: String) = this + o

@Language("HTML")
fun p69a(c: Boolean): Any {
    return "N01 a" to if (c) "N02 b" else "N03 c"
}

@Language("HTML")
fun p69b(c: Boolean): String {
    return "N04 a" cat if (c) "N05 b" else "N06 c"
}

@Language("HTML")
fun p69c(c: Boolean, l: List<String>): Any {
    return "N07 a" in if (c) l else listOf("N08 b")
}

@Language("HTML")
fun p69d(c: Boolean, x: Any): Any {
    return x is String && if (c) true else "N09 b" == x
}

@Language("HTML")
fun p69e(c: Boolean): Any {
    return 1 until if (c) 2 else 3
}

@Language("HTML")
fun p69f(c: Boolean): String {
    val r = 10 downTo if (c) 1 else 2 step if (c) 1 else 2
    if (c) return "N10 <p>a</p>"
    return "N11 <p>b</p>" + r
}

@Language("HTML")
fun p69g(c: Boolean): Any {
    return c and if (c) true else false
}

@Language("HTML")
fun p69h(c: Boolean): String {
    val q = c or if (c) true else false
    return if (c) "N12 <p>a</p>" else "N13 <p>b</p>"
}

@Language("HTML")
fun p69i(c: Boolean, x: Any): String {
    val s = x as? String ?: if (c) return "N14 <p>a</p>" else "N15 b"
    return s
}

@Language("HTML")
fun p69j(c: Boolean): String {
    return "N16 a" cat when { c -> "N17 b"; else -> "N18 c" }
}

@Language("HTML")
fun p69k(c: Boolean): String {
    val a = "N19 a" cat if (c) "N20 b" else "N21 c"
    return if (c) "N22 <p>x</p>" else a
}

@Language("HTML")
fun p69l(c: Boolean): String {
    val p = "N23 a" to if (c) "N24 b" else "N25 c"
    if (c) return "N26 <p>x</p>"
    return when { c -> "N27 <p>y</p>"; else -> "N28 <p>z</p>" }
}

@Language("HTML")
fun p69m(c: Boolean): String {
    "N29 a" to if (c) "N30 b" else "N31 c"
    return if (c) "N32 <p>x</p>" else "N33 <p>y</p>"
}

@Language("HTML")
fun p69n(c: Boolean): String {
    "N34 a" to
        if (c) "N35 b" else "N36 c"
    return if (c) "N37 <p>x</p>" else "N38 <p>y</p>"
}

@Language("HTML")
fun p69o(c: Boolean, xs: List<Int>): String {
    for (x in if (c) xs else listOf()) println("N39 p")
    return if (c) "N40 <p>x</p>" else "N41 <p>y</p>"
}
