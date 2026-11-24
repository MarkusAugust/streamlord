import org.intellij.lang.annotations.Language

@Language("HTML")
fun h(n: String) =
    "Hey <b>$n</b>"

@Language("HTML")
fun h2(n: String): String =
    """
    Raw <b>$n</b>
    """.trimIndent()
