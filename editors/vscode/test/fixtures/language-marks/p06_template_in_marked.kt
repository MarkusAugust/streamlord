import org.intellij.lang.annotations.Language

@Language("HTML")
val t = "Outer <i>${"Inner <b>x</b>"}</i>"

@Language("HTML")
fun u(n: String) = "Outer <i>${if (n.isEmpty()) "Empty <b>x</b>" else n}</i>"
