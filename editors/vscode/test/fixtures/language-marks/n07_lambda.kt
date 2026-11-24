import org.intellij.lang.annotations.Language
@Language("HTML") fun h(xs: List<String>) = xs.joinToString { "Lam <b>$it</b>" }
@Language("HTML") val k = run { "Run <b>x</b>" }
