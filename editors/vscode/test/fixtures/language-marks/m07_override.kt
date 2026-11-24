import org.intellij.lang.annotations.Language

open class Base {
    @Language("HTML")
    open fun g(n: String): String = "Base <b>$n</b>"
}

class C : Base() {
    @Language("HTML")
    override fun g(n: String): String {
        if (n.isEmpty()) return "Empty <a>x</a>"
        val other = "plain $n"
        return "Hey <b>$n</b>"
    }
}
