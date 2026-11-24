import org.intellij.lang.annotations.Language

@Language("HTML")
fun p68a(c: Boolean): String {
    val (a, b) = if (c) return "M01 <p>a</p>" else Pair("M02 x", "M03 y")
    return a + b
}

@Language("HTML")
fun p68b(c: Boolean): String {
    val (a, b) = Pair("M04 x", "M05 y")
    return "M06 <p>q</p>" + a + b
}

@Language("HTML")
fun p68c(c: Boolean): String {
    val (a, b) = "M07 x" to "M08 y"
    if (c) return a
    return "M09 <p>z</p>"
}

@Language("HTML")
fun p68d(c: Boolean): String {
    val (a, b) = Pair("M10 x", "M11 y"); return "M12 <p>z</p>"
}

fun p68e(c: Boolean): String {
    @Language("HTML")
    val (a, b) = if (c) return "M13 a" else Pair("M14 x", "M15 y")
    return a
}

@Language("HTML")
fun p68f(m: Map<String, String>): String {
    for ((k, v) in m) {
        if (k == "M16 k") return "M17 <p>a</p>"
    }
    m.forEach { (k, v) -> println("M18 p") }
    val (x, _) = m.entries.first().let { it.key to "M19 y" }
    return x
}

@Language("HTML")
fun p68g(c: Boolean): String {
    val (a, b) = when {
        c -> return "M20 <p>a</p>"
        else -> "M21 x" to "M22 y"
    }
    return "M23 <p>b</p>"
}

@Language("HTML")
fun p68h(c: Boolean): String {
    val (a, b) =
        if (c) return "M24 <p>a</p>"
        else Pair("M25 x", "M26 y")
    return a
}

@Language("HTML")
fun p68i(c: Boolean): String {
    val (a: String, b: String) = Pair("M27 x", "M28 y")
    return if (c) "M29 <p>a</p>" else a
}
