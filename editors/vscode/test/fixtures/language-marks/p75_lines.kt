import org.intellij.lang.annotations.Language

@Language("HTML")
fun p75a(c: Boolean): String {
    val a = "U01 a" +
        "U02 b"
    if (c) return a
    return "U03 <p>c</p>"
}

@Language("HTML")
fun p75b(c: Boolean): String {
    val a = c
        .not()
    if (a) return "U04 <p>a</p>"
    return "U05 <p>b</p>"
}

@Language("HTML")
fun p75c(c: Boolean): String {
    val a = c ?:
        false
    if (a) return "U06 <p>a</p>"
    return "U07 <p>b</p>"
}

@Language("HTML")
fun p75d(c: Boolean): String {
    val a = if (c)
        "U08 a"
    else
        return "U09 <p>b</p>"
    if (c) return a
    return "U10 <p>c</p>"
}

@Language("HTML")
fun p75e(c: Boolean): String {
    val a = listOf(1).filter {
        it > 0
    }.size
    if (a > 0) return "U11 <p>a</p>"
    return "U12 <p>b</p>"
}

@Language("HTML")
fun p75f(c: Boolean, x: Any): String {
    val a = x as String
    if (c) return a
    val b = x as? String
    if (b != null) return "U13 <p>a</p>"
    return "U14 <p>b</p>"
}

@Language("HTML")
fun p75g(c: Boolean, x: Any): String {
    val a = x is String
    if (a) return "U15 <p>a</p>"
    val b = "U16 x" in listOf("y")
    if (b) return "U17 <p>b</p>"
    return "U18 <p>c</p>"
}

@Language("HTML")
fun p75h(c: Boolean): String {
    val a = 1 to 2
    if (c) return "U19 <p>a</p>"
    val b = 1 until 2
    if (c) return "U20 <p>b</p>"
    return "U21 <p>c</p>"
}

@Language("HTML")
fun p75i(c: Boolean): String {
    if (c) {
        println("U22 p")
    }
    if (!c) return "U23 <p>a</p>"
    return if (c) "U24 <p>b</p>" else "U25 <p>c</p>"
}

@Language("HTML")
fun p75j(c: Boolean): String {
    val a = c; if (a) return "U26 <p>a</p>"
    c == true; if (c) return "U27 <p>b</p>"
    return "U28 <p>c</p>"
}
