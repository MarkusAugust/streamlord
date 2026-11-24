import org.intellij.lang.annotations.Language

@Language("HTML")
fun f(n: String): String {
    return "R3a <b>x</b>" + n
}

@Language("HTML")
fun g(n: String): String {
    return n + "R3b <b>x</b>" + n
}

@Language("HTML")
fun h(n: String?): String {
    return n ?: "R3c <b>x</b>" + n
}

@Language("HTML")
fun k(n: String): String {
    return "<p style='a;'>" + n + "R3d </p>"
}
