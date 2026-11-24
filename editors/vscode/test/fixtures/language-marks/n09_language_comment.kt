import org.intellij.lang.annotations.Language
fun f(n: String): String {
    // language=HTML
    val a = "LC1 <b>$n</b>"
    // language=HTML
    val b = if (n.isEmpty()) "LC2 <b>$n</b>" else "LC3 <i>$n</i>"
    // language=HTML
    return "LC4 <b>$n</b>"
}
