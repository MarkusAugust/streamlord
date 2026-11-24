import org.intellij.lang.annotations.Language

@Language("HTML")
fun f(n: String) = render(n) + "Tail <b>$n</b>"

@Language("HTML")
val g = (n) + "Tail2 <b>x</b>"
