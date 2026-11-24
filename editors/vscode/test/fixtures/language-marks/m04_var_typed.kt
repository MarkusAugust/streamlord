import org.intellij.lang.annotations.Language

class A(val name: String) {
    @Language("HTML")
    var d: String = "Hey <b>$name</b>"
    @Language("HTML") private var e: String? = "Ho <b>$name</b>"
}
