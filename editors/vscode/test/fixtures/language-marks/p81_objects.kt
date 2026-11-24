import org.intellij.lang.annotations.Language

object P81Views {
    @Language("HTML")
    const val HEADER = "Q21 <header>Site</header>"

    const val TITLE = "Q22 Title" // missed: #347

    @Language("HTML")
    val FOOTER = "Q23 <footer>${TITLE}</footer>"

    @Language("HTML")
    fun card(title: String, body: String) = "Q24 <article><h2>$title</h2>$body</article>"

    fun plain(x: String) = "Q25 $x"

    @JvmStatic
    @Language("HTML")
    fun badge(n: Int): String = if (n > 0) "Q26 <b>$n</b>" else "Q27 nothing"
}

class P81Widget(val label: String) {
    companion object {
        @Language("HTML")
        private const val TEMPLATE = "Q28 <button>{}</button>"

        @Language("HTML")
        fun of(label: String) = "Q29 <span>$label</span>"

        val NAME = "Q30 widget"
    }

    @Language("HTML")
    fun render(): String = TEMPLATE.replace("Q31 {}", label)
}
