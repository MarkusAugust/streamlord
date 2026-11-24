import org.intellij.lang.annotations.Language

object P98Pages {
    @Language("HTML")
    fun list(items: List<String>): String =
        """
        |R61 <ul>
        |${items.joinToString("\n") { "|  <li>$it</li>" }}
        |</ul>
        """.trimMargin()

    @Language("HTML")
    fun card(t: String) = """
        R62 <div class="card">
          <h2>$t</h2>
          <p>${"""R63 raw in raw"""}</p>
        </div>
    """.trimIndent()

    fun plain() = """
        R64 <div>plain</div>
    """.trimIndent()

    @Language("HTML")
    val static = """R65 <hr>""" + """R66 <br>"""

    // language=HTML
    val commented = """
        R67 <aside></aside>
    """.trimIndent().replace("R68 aside", "R69 nav")
}
