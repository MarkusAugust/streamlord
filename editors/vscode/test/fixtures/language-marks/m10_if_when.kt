import org.intellij.lang.annotations.Language

@Language("HTML")
fun w(x: Boolean, n: String) = if (x) "Hey <b>$n</b>" else "Ho <i>$n</i>"

@Language("HTML")
fun v(x: Int, n: String) = when (x) {
    1 -> "One <b>$n</b>"
    else -> "Other <i>$n</i>"
}

@Language("HTML")
val u = if (flag) "Val <b>x</b>" else "Val2 <i>x</i>"
