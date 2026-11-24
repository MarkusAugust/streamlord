import org.intellij.lang.annotations.Language
@Language("HTML") fun page() = layout()
fun layout() = "x"
fun title(t: String) = "Title <b>$t</b>"
