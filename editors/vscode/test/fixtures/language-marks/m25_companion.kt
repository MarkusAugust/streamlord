import org.intellij.lang.annotations.Language

class Q {
    companion object {
        @Language("HTML")
        const val TPL = "Tpl <b>x</b>"

        @Language("HTML")
        fun make(n: String) = "Make <b>$n</b>"
    }
}
