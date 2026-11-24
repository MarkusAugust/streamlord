import org.intellij.lang.annotations.Language

@Language("HTML")
val x = render("a")

@Language("HTML")
val y = listOf("b", "c").joinToString()

@Language("HTML")
fun z(n: String) = render("d")

@Language("HTML")
fun q(n: String): String {
    return error("x $n")
}
