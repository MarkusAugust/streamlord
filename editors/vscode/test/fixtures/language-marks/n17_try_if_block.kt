import org.intellij.lang.annotations.Language
@Language("HTML") fun q(c: Boolean) = if (c) { "IfBlock <b>x</b>" } else "IfPlain <i>x</i>"
@Language("HTML") fun u(c: Boolean) = try { "Try <b>x</b>" } catch (e: Exception) { "Catch" }
