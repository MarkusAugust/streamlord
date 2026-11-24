import org.intellij.lang.annotations.Language
@Language("HTML") fun p(n: String) = ("Paren <b>$n</b>")
@Language("HTML") fun s(n: String) = "Sum1 <b>" + n + "Sum2</b>"
@Language("HTML") fun t(n: String): String { return ("RetParen <b>$n</b>") }
