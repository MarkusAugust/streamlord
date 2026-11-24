import org.intellij.lang.annotations.Language

@Language("HTML")
fun f(): String = if (flag) "A <b>x</b>" else "B <i>x</i>"

@Language("HTML")
fun g(n: String) = when {
    n.isEmpty() -> "C <b>x</b>"
    else -> "D <i>x</i>"
}
