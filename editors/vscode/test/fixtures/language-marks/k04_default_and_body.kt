import org.intellij.lang.annotations.Language

@Language("HTML")
fun f(n: String = "def") = "Body <b>$n</b>"

@Language("HTML")
fun g(n: String = "def2"): String {
    return "Body2 <b>$n</b>"
}
