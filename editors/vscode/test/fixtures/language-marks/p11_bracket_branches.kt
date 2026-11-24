import org.intellij.lang.annotations.Language

fun p11render(s: String?) = s ?: ""

@Language("HTML")
fun p11a(c: Boolean): String {
    return p11render(if (c) "K1 <b>x</b>" else "K2 <b>x</b>")
}

@Language("HTML")
fun p11b(n: String?): String {
    return p11render(n ?: "K3 <b>x</b>")
}

@Language("HTML")
fun p11c(c: Boolean, n: String): String {
    return n + (if (c) "K4 <b>x</b>" else "K5 <b>x</b>")
}

@Language("HTML")
fun p11d(n: String?): String {
    return listOf(n ?: "K6 <b>x</b>").first()
}

@Language("HTML")
var p11page = ""

fun p11e(c: Boolean) {
    p11page = p11render(if (c) "K7 <b>x</b>" else "K8 <b>x</b>") // missed: #347
}
