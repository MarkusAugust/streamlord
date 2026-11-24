import org.intellij.lang.annotations.Language

@Language("HTML")
var p17p = ""

val p17n: String? = null

@Language("HTML")
val p17v = p17n?.trim() ?: "L1 <b>x</b>"

fun p17r(a: String?, b: String?) = a ?: b ?: ""

fun p17a(n: String?) {
    p17p = n ?: "L2 <b>x</b>"
    p17p = n?.trim() ?: "L3 <b>x</b>"
    p17p = (n ?: "L4 <b>x</b>")
    p17p = p17r("L5 <b>x</b>", "L6 <b>x</b>") // missed: #347
    p17p = "L7 <b>x</b>".trim() // missed: #347
    p17p = listOf("L8 <b>x</b>").first() // missed: #347
    p17p = n.orEmpty() + p17r("L9 <b>x</b>", null)
    p17p = n ?: n ?: "M1 <b>x</b>"
    p17p = if (n == null) "M2 <b>x</b>" else n ?: "M3 <b>x</b>" // missed: #347
}

fun p17b(n: String?): String {
    @Language("HTML")
    val s = n ?: "M4 <b>x</b>"
    return s
}
