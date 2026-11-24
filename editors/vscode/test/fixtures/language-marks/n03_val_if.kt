import org.intellij.lang.annotations.Language
val ok = true
@Language("HTML") val v = if (ok) "a <b>x</b>" else "b <i>x</i>"
