import org.intellij.lang.annotations.Language

@Language("HTML")
fun p62a(c: Boolean): String {
    if (c)
        return "G01 <p>a</p>"
    else
        println("G02 x")
    if (c) {
        return "G03 <p>b</p>"
    }
    else {
        return "G04 <p>c</p>"
    }
}

@Language("HTML")
fun p62b(c: Boolean): String {
    if (c) return "G05 <p>a</p>"
        + "G06 <p>b</p>"
    return "G07 <p>c</p>" +
        "G08 <p>d</p>"
}

@Language("HTML")
fun p62c(c: Boolean, d: Boolean): String {
    if (c) return "G09 <p>a</p>" else
        if (d) return "G10 <p>b</p>"
        else println("G11 x")
    return "G12 <p>c</p>"
}

@Language("HTML")
fun p62d(c: Boolean): String {
    var z = "G13 a"
    z =
        if (c)
            return "G14 <p>a</p>"
        else
            "G15 b"
    return z
}

@Language("HTML")
fun p62e(c: Boolean): String {
    if (c) return "G16 <p>a</p>" // trailing
    // between
    else return "G17 <p>b</p>"
}

@Language("HTML")
fun p62f(c: Boolean): String {
    if (c) return "G18 <p>a</p>" /* block */ else return "G19 <p>b</p>"
}

@Language("HTML")
fun p62g(c: Boolean): String {
    val z = if (c)
        "G20 a"
    else return "G21 <p>r</p>"
    if (c) return "G22 <p>s</p>"; else return z
}
