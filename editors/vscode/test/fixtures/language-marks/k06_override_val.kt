import org.intellij.lang.annotations.Language

abstract class B {
    abstract val x: String
}

class C(val n: String) : B() {
    @Language("HTML")
    override val x: String = "Ov <b>$n</b>"

    @Language("HTML")
    override fun toString(): String = "ToS <b>$n</b>"
}
