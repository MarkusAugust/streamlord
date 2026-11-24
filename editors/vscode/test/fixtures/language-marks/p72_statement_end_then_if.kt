import org.intellij.lang.annotations.Language

@Language("HTML")
fun p72a(c: Boolean): String {
    println("R01 p")
    if (c) return "R02 <p>a</p>"
    return "R03 <p>b</p>"
}

@Language("HTML")
fun p72b(c: Boolean): String {
    val x = listOf(1, 2)
        .map { it + 1 }
    if (c) return "R04 <p>a</p>"
    return "R05 <p>b</p>" + x
}

@Language("HTML")
fun p72c(c: Boolean): String {
    val x = c &&
        c
    if (x) return "R06 <p>a</p>"
    return "R07 <p>b</p>"
}

@Language("HTML")
fun p72d(c: Boolean): String {
    val x = "R08 a"
    // a comment
    if (c) return "R09 <p>a</p>"
    return x
}

@Language("HTML")
fun p72e(c: Boolean): String {
    val x = if (c) 1 else 2
    if (x == 1) return "R10 <p>a</p>"
    return "R11 <p>b</p>"
}

@Language("HTML")
fun p72f(c: Boolean): String {
    val x = when { c -> 1; else -> 2 }
    if (x == 1) return "R12 <p>a</p>"
    return "R13 <p>b</p>"
}

@Language("HTML")
fun p72g(c: Boolean): String {
    val s = "R14 a" + "R15 b"
    if (c) return s
    return "R16 <p>b</p>"
}

@Language("HTML")
fun p72h(c: Boolean): String {
    val x = c == true
    if (x) "R17 a" else "R18 b"
    return if (x) "R19 <p>a</p>" else "R20 <p>b</p>"
}

@Language("HTML")
fun p72i(c: Boolean): String {
    val x = 1 ==
        if (c) 1 else 2
    if (x) return "R21 <p>a</p>"
    return "R22 <p>b</p>"
}

@Language("HTML")
fun p72j(c: Boolean): String {
    val x = 1 == if (c) {
        1
    } else {
        2
    }
    if (x) return "R23 <p>a</p>"
    return if (c) "R24 <p>b</p>" else "R25 <p>c</p>"
}

@Language("HTML")
fun p72k(c: Boolean): String {
    val y = "R26 a" to if (c) {
        "R27 b"
    } else {
        "R28 c"
    }
    return if (c) "R29 <p>b</p>" else "R30 <p>c</p>"
}

@Language("HTML")
fun p72l(c: Boolean): String {
    val y = "R31 a" to when (c) {
        true -> "R32 b"
        else -> "R33 c"
    }
    return when (c) {
        true -> "R34 <p>b</p>"
        else -> "R35 <p>c</p>"
    }
}

@Language("HTML")
fun p72m(c: Boolean): String {
    c == if (c) true else false
    if (c) "R36 a" else return "R37 <p>b</p>"
    return "R38 <p>c</p>"
}

@Language("HTML")
fun p72o(c: Boolean): String {
    val q = c ==
        if (c) true else false; return if (c) "R41 <p>b</p>" else "R42 <p>c</p>"
}
