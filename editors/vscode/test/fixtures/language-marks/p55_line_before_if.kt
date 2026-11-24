import org.intellij.lang.annotations.Language

@Language("HTML")
fun p55a(c: Boolean, x: String?, n: String): String {
    val s = n + "M1 x"
    if (c) return x ?: "M2 <p>a</p>"
    return "M3 <p>b</p>" + s
}

@Language("HTML")
fun p55b(c: Boolean, d: Boolean, x: String?, n: Int): String {
    var m = n
    m++
    if (c) return if (d) "M4 <p>a</p>" else "M5 <p>z</p>"
    return x ?: "M6 <p>b</p>"
}

@Language("HTML")
fun p55c(c: Boolean, x: String?): String {
    val m: List<Int> = listOf()
    if (c) return x ?: "M7 <p>a</p>"
    return "M8 <p>b</p>" + m
}
