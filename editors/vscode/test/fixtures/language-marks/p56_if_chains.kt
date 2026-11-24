import org.intellij.lang.annotations.Language

fun p56sink(s: String) = s

@Language("HTML")
fun p56a(c: Boolean, d: Boolean): String {
    if (c) {
        if (d) return "A01 <p>a</p>" else return "A02 <p>b</p>"
    } else if (d) {
        return "A03 <p>c</p>"
    } else {
        println("A04 x")
    }
    return "A05 <p>end</p>"
}

@Language("HTML")
fun p56b(c: Boolean, d: Boolean): String {
    if (c) return "A06 <p>a</p>"
    else if (d) return "A07 <p>b</p>"
    else println("A08 x")
    val q = "A09 q"
    return q
}

@Language("HTML")
fun p56c(c: Boolean, d: Boolean): String {
    val y = if (c) "A10 a" else if (d) "A11 b" else "A12 c"
    if (c) return y else if (d) return "A13 <p>d</p>" else p56sink("A14 x")
    return "A15 <p>e</p>"
}

@Language("HTML")
fun p56d(c: Boolean, d: Boolean): String {
    return if (c) {
        if (d) "A16 <p>a</p>" else return "A17 <p>b</p>"
    } else if (d) "A18 <p>c</p>" else "A19 <p>d</p>"
}

@Language("HTML")
fun p56e(c: Boolean, d: Boolean): String {
    if (c)
        if (d)
            return "A20 <p>a</p>"
        else
            println("A21 x")
    else
        return "A22 <p>b</p>"
    return "A23 <p>c</p>"
}

@Language("HTML")
fun p56f(c: Boolean, d: Boolean, e: Boolean): String {
    if (c) {
        return "A24 <p>a</p>"
    } else if (d) {
        val z = "A25 z"
        if (e) return z else return "A26 <p>b</p>"
    } else if (e) {
        p56sink("A27 x")
    }
    return "A28 <p>e</p>"
}
