import org.intellij.lang.annotations.Language

@Language("HTML")
fun p33a(c: Boolean, d: Boolean): String {
    1 + if (c) 1 else 2
    return if (d) "B3 <b>x</b>" else "B4 <b>x</b>"
}

@Language("HTML")
fun p33b(x: String?, d: Boolean): String {
    x ?: error("none")
    return if (d) "B5 <b>x</b>" else "B6 <b>x</b>"
}
