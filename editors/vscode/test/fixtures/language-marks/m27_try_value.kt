import org.intellij.lang.annotations.Language

@Language("HTML")
fun t(n: String) = try { "Try <b>$n</b>" } catch (e: Exception) { "Catch <i>x</i>" }

@Language("HTML")
val l = lazyText { "Lambda <b>x</b>" }
