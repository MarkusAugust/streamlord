import org.intellij.lang.annotations.Language

@Language("HTML")
val page = """
    Page <b>$name</b>
""".trimIndent()

@Language("HTML")
fun page2(name: String) = """
    Page2 <b>$name</b>
""".trimIndent()
