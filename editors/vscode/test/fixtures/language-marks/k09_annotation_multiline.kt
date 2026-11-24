import org.intellij.lang.annotations.Language

@Language(
    "HTML",
)
fun f(n: String) = "Multi <b>$n</b>"

@Language(value = "HTML", prefix = "<ul>")
val v = "Prefix <li>x</li>"

@field:Language("HTML")
val w = "Field <b>x</b>"
