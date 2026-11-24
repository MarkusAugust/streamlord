import org.intellij.lang.annotations.Language

class A(val name: String) {
    @Language("HTML")
    val b: String
        get() {
            if (name.isEmpty()) return "Empty <i>x</i>"
            return "Hey <b>$name</b>"
        }
}
