import org.intellij.lang.annotations.Language

class A(val name: String) {
    @Language("HTML")
    val a: String
        get() = "Hey <b>$name</b>"
}
