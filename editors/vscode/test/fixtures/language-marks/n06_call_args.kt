import org.intellij.lang.annotations.Language
fun render(s: String) = s
@Language("HTML") val r = render("Arg <b>x</b>")
@Language("HTML") fun g(n: String) = listOf("L1 <b>x</b>", "L2").joinToString(n)
