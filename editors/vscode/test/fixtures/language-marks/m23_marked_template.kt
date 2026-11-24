import org.intellij.lang.annotations.Language

@Language("HTML")
val a = "${"inner"} Outer <b>x</b>"

@Language("HTML")
fun b(n: String) = "Prefix ${n.ifEmpty { "x" }} <b>y</b>"
