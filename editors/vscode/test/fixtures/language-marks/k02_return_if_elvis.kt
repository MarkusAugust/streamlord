import org.intellij.lang.annotations.Language

@Language("HTML")
fun f(x: Boolean): String {
    return if (x) "RetIf <b>x</b>" else "RetElse <i>x</i>"
}

@Language("HTML")
fun g(x: String?): String {
    return x ?: "RetElvis <b>x</b>"
}

@Language("HTML")
fun h(x: Int): String {
    return when (x) {
        1 -> "RetWhen <b>x</b>"
        else -> "RetWhen2 <i>x</i>"
    }
}
