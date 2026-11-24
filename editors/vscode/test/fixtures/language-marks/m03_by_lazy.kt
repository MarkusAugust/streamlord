import org.intellij.lang.annotations.Language

class A(val name: String) {
    @Language("HTML")
    val c by lazy { "Hey <b>$name</b>" }
    @Language("HTML")
    val d: String by lazy {
        "Ho <b>$name</b>"
    }
}
