import org.intellij.lang.annotations.Language
@Language("HTML") fun outer(n: String): String {
    fun inner() = "Inner <b>$n</b>"
    return inner() + "Outer <i>$n</i>"
}
