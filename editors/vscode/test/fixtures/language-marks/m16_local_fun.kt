import org.intellij.lang.annotations.Language

@Language("HTML")
fun outer(n: String): String {
    fun inner(): String {
        return "plain one"
    }
    val list = listOf(1).map { return "lambda <b>x</b>" }
    return "Outer <b>$n</b>"
}
