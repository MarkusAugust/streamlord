import org.intellij.lang.annotations.Language

interface I {
    @Language("HTML")
    fun f(n: String): String = "Hey <b>$n</b>"

    @Language("HTML")
    fun g(n: String): String {
        return "Ho <b>$n</b>"
    }

    @Language("HTML")
    fun h(): String
}
