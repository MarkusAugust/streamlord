import org.intellij.lang.annotations.Language

@Language("HTML")
var p18p = ""

val p18n: String? = null

fun p18r(a: String?, b: String?) = a ?: b ?: ""

@Language("HTML")
val p18v1 = p18n.orEmpty() + p18r("N1 <b>x</b>", null)

@Language("HTML")
val p18v2 = p18r("N2 <b>x</b>", "N3 <b>x</b>")

@Language("HTML")
val p18v3 = (p18n ?: "N4 <b>x</b>")

fun p18a(c: Boolean) {
    p18p = p18r(p18n ?: "N5 <b>x</b>", null)
    p18p = if (c) p18r("N6 <b>x</b>", null) else "" // missed: #347
    p18p = p18n ?: p18r("N7 <b>x</b>", null)
    p18p = p18n.orEmpty() + "N8 <b>x</b>" // missed: #347
    p18p = "N9 <b>x</b>" + p18n // missed: #347
    p18p = p18n + ("P1 <b>x</b>")
    p18p = p18r("P2 <b>x</b>", null) + p18n
}

@Language("HTML")
fun p18b(): String {
    var s = "P3 <b>x</b>"
    s = "P4 <b>x</b>"
    return s
}

@Language("HTML")
fun p18c(x: String): String {
    return "P5 <b>${"P6 x"}</b>"
}

@Language("HTML")
fun p18d(): String {
    val s = "P7 <b>x</b>"
    return run { s }
}

@Language("HTML")
fun p18e(c: Boolean): String {
    val s = "P8 <b>x</b>"
    return if (c) s else "P9 <b>x</b>"
}

@Language("HTML")
fun p18f(): String {
    val s = "Q1 <b>x</b>"
    fun inner(): String {
        return s
    }
    return inner()
}

class P18G {
    @Language("HTML")
    var body = ""

    fun f() {
        body = p18n ?: "Q2 <b>x</b>"
        body = "Q3 <b>x</b>" // missed: #347
    }
}
