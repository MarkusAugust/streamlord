import org.intellij.lang.annotations.Language

fun p76a(c: Boolean): String {
    @Language("HTML")
    val (a, b) = if (c) return "V01 a" else "V02 x" to "V03 y"
    return a
}

fun p76b(c: Boolean): String {
    @Language("HTML")
    val (a, b) = when { c -> return "V04 a"; else -> Pair("V05 x", "V06 y") }
    return a
}

fun p76c(c: Boolean, s: Pair<String, String>?): String {
    @Language("HTML")
    val (a, b) = s ?: return "V07 a"
    return a
}

fun p76d(c: Boolean): String {
    // language=HTML
    val (a, b) = if (c) return "V08 a" else Pair("V09 x", "V10 y")
    return a
}

@Language("HTML")
fun p76e(c: Boolean): String {
    @Language("HTML")
    val (a, b) = if (c) return "V11 <p>a</p>" else Pair("V12 x", "V13 y")
    return a
}

fun p76f(c: Boolean): String {
    @Language("HTML")
    val (a, b) = run { if (c) return "V14 a"; "V15 x" to "V16 y" }
    return a
}

fun p76g(c: Boolean): String {
    @Language("HTML")
    val (a, b) =
        if (c) return "V17 a"
        else Pair("V18 x", "V19 y")
    return a
}

fun p76h(c: Boolean): String {
    @Language("HTML")
    val (a, b) = "V20 x" to "V21 y"
    return a
}

fun p76i(c: Boolean): String {
    @Language("HTML")
    val x = if (c) return "V22 a" else "V23 b"
    @Language("HTML")
    var y = if (c) return "V24 a" else "V25 b"
    return x + y
}

class P76J {
    @Language("HTML")
    val p: String = run { "V26 a" }
    fun f(c: Boolean): String {
        @Language("HTML")
        val (a, b) = if (c) return "V27 a" else Pair("V28 x", "V29 y")
        return a
    }
}
