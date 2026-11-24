import org.intellij.lang.annotations.Language
@Language("HTML")
val a: String =
    "NextLine <b>x</b>"
@Language("HTML") val b by lazy { "Lazy <b>x</b>" }
