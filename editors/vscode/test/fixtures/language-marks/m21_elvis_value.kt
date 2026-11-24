import org.intellij.lang.annotations.Language

@Language("HTML")
val e1 = cached ?: "Elvis <b>x</b>"

@Language("HTML")
fun e2(n: String?) = n ?: "Elvis2 <b>x</b>"

@Language("HTML")
fun e3(n: String): String {
    return n.ifEmpty { "Lambda <b>x</b>" }
}
