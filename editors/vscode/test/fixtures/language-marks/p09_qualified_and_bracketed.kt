import org.intellij.lang.annotations.Language

@Language("HTML")
var p09title = ""

class P09 {
    var p09title = ""
}

fun p09a(p: P09) {
    p.p09title = "J1 <b>x</b>"
}

@Language("HTML")
fun p09b(n: String): String {
    return error("A1 <b>" + n)
}

@Language("HTML")
fun p09c(n: String): String {
    return ("A2 <b>" + n)
}

@Language("HTML")
fun p09d(c: Boolean, n: String): String {
    return if (c) { "A4 <b>" + n } else n
}

fun p09e(c: Boolean) {
    if (c) p09title = "B2 <b>x</b>" // missed: #347
}
