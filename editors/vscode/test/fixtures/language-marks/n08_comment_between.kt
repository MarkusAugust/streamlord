import org.intellij.lang.annotations.Language
@Language("HTML") // the page
fun c1(n: String) = "C1 <b>$n</b>"
@Language("HTML") /* the page */ fun c2(n: String) = "C2 <b>$n</b>"
