import org.intellij.lang.annotations.Language

@Language("HTML")
fun p10a(n: String): String {
    return "M1 <p>" +
        n +
        "M2 </p>"
}

@Language("HTML")
fun p10b(n: String): String {
    return listOf(
        n,
    ).joinToString() + "M3 <b>x</b>"
}

@Language("HTML")
fun p10c(n: String): String {
    return n.let { a -> a; a } + "M4 <b>x</b>"
}

@Language("HTML")
fun p10d(c: Boolean, n: String): String {
    return if (c) "M5 <b>" + n else n
}

@Language("HTML")
fun p10e(n: String): String {
    val s = "M6 <i>x</i>"
    if (n.isEmpty()) return "M7 <b>" + n; return n + "M8 <b>x</b>"
}

@Language("HTML")
fun p10f(n: String): String {
    if (n.isEmpty()) return n
    "M9 <b>x</b>" + n
    return n
}

@Language("HTML")
fun p10g(n: String): String {
    return n
        .trim() + "Q13 <b>x</b>"
}

@Language("HTML")
fun p10h(n: String): String {
    return n + "Q11 <b>" +
        "Q12 </b>"
}

@Language("HTML")
fun p10i(n: String?): String {
    return n ?: n + "Q10 <b>x</b>"
}
