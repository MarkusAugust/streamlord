import org.intellij.lang.annotations.Language

@Language("HTML")
fun r(n: String): String {
    if (n.isEmpty()) return render("plain arg")
    return (
        "Paren <b>$n</b>"
    )
}

@Language("HTML")
fun r2(n: String): String = ("Paren2 <b>$n</b>")
