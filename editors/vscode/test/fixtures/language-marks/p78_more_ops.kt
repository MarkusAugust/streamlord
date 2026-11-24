import org.intellij.lang.annotations.Language

@Language("HTML")
fun p78a(c: Boolean): String {
    val y = c
    return@p78a if (c) "Y01 <p>a</p>" else "Y02 <p>b</p>"
}

@Language("HTML")
fun p78b(c: Boolean): String {
    val y = foo3(c) { if (it) "Y03 a" else "Y04 b" }
    return if (c) "Y05 <p>a</p>" else "Y06 <p>b</p>"
}

fun foo3(c: Boolean, f: (Boolean) -> String) = f(c)

@Language("HTML")
fun p78c(c: Boolean): String {
    val y = arrayOf(1)[if (c) 0 else 0]
    return if (c) "Y07 <p>a</p>" else "Y08 <p>b</p>"
}

@Language("HTML")
fun p78d(c: Boolean, n: Int): String {
    val y = n.coerceAtLeast(if (c) 1 else 2)
    return if (c) "Y09 <p>a</p>" else "Y10 <p>b</p>"
}

@Language("HTML")
fun p78e(c: Boolean, n: Int): String {
    val y = n shl if (c) 1 else 2
    return if (c) "Y11 <p>a</p>" else "Y12 <p>b</p>"
}

@Language("HTML")
fun p78f(c: Boolean): String {
    val y = Pair(1, 2) to if (c) 1 else 2
    if (c) return "Y13 <p>a</p>"
    return when (c) { true -> "Y14 <p>b</p>" else -> "Y15 <p>c</p>" }
}

@Language("HTML")
fun p78g(c: Boolean): String {
    run { c to if (c) 1 else 2 }
    return if (c) "Y16 <p>a</p>" else "Y17 <p>b</p>"
}

@Language("HTML")
fun p78h(c: Boolean): String {
    listOf(c to if (c) 1 else 2,
        3)
    return if (c) "Y18 <p>a</p>" else "Y19 <p>b</p>"
}

@Language("HTML")
fun p78i(c: Boolean): String {
    val y = c to if (c) 1 else 2; val z = if (c) return "Y20 <p>a</p>" else 2
    return if (c) "Y21 <p>a</p>" else "Y22 <p>b</p>"
}

@Language("HTML")
fun p78j(c: Boolean): String {
    val x = c to
        if (c) 1 else 2
    return if (c) "Y23 <p>a</p>" else "Y24 <p>b</p>"
}

@Language("HTML")
fun p78k(c: Boolean): String {
    val x = c
    if (x) "Y25 a" else "Y26 b"
    if (c) return "Y27 <p>a</p>" else return "Y28 <p>b</p>"
}

@Language("HTML")
fun p78l(c: Boolean, d: Boolean): String {
    return if (c) if (d) "Y29 <p>a</p>" else "Y30 <p>b</p>" else "Y31 <p>c</p>"
}

@Language("HTML")
fun p78m(c: Boolean): String {
    val r = c && run { if (c) return "Y32 <p>a</p>"; true }
    return if (r) "Y33 <p>b</p>" else "Y34 <p>c</p>"
}

@Language("HTML")
fun p78n(c: Boolean, s: String): String {
    return s + if (c) "Y35 a" else "Y36 b"
}

@Language("HTML")
fun p78o(c: Boolean, s: String): String {
    return s.plus(if (c) "Y37 a" else "Y38 b")
}

@Language("HTML")
fun p78p(c: Boolean, s: String): String {
    return s ?: if (c) "Y39 <p>a</p>" else "Y40 <p>b</p>"
}
