import org.intellij.lang.annotations.Language

val o = object {
    @Language("HTML")
    fun f(n: String) = "Obj <b>$n</b>"
}

enum class E {
    A;

    @Language("HTML")
    fun f(n: String) = "Enum <b>$n</b>"
}
