import org.intellij.lang.annotations.Language
@Language("HTML") fun w(x: Int, n: String?): String {
    if (x > 1) return "R1 <b>x</b>"
    return when (x) { 0 -> "R2 <b>x</b>" else -> n ?: "R3 <i>x</i>" }
}
