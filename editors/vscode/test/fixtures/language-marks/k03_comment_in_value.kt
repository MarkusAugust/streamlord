import org.intellij.lang.annotations.Language

@Language("HTML")
fun f(n: String) = /* the page */ "Comment <b>$n</b>"

@Language("HTML")
fun g(n: String) = // the page
    "LineComment <b>$n</b>"

@Language("HTML")
val v = /* v */ "ValComment <b>x</b>"
