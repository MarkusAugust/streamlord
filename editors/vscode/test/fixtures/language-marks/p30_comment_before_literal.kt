import org.intellij.lang.annotations.Language

val p30a = /* language=HTML */"C1 <b>x</b>"

val p30g = /* language=HTML */ "C7 <b>x</b>"

@Language("HTML")
val p30b = /*c*/"C2 <b>x</b>"

@Language("HTML")
fun p30c(): String {
    return /*c*/"C3 <b>x</b>"
}

@Language("HTML")
fun p30d(): String {
    val s = /* c */ "C4 <b>x</b>"
    return s
}

@Language("HTML")
var p30page = ""

fun p30e(c: Boolean, a: String, b: String) {
    p30page = if (c) a + b else p30f("C5 <b>x</b>") // missed: #347
    p30page = if (c) a + b else ("C6 <b>x</b>") // missed: #347
}

fun p30f(s: String) = s
