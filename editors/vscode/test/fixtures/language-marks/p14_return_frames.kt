import org.intellij.lang.annotations.Language

@Language("HTML")
fun p14a(c: Boolean): String {
    return if (c) { "A1 <b>x</b>" } else { "A2 <b>x</b>" }
}

@Language("HTML")
fun p14b(c: Boolean): String {
    return try { "A3 <b>x</b>" } catch (e: Exception) { "A4 <b>x</b>" }
}

@Language("HTML")
fun p14c(c: Boolean): String {
    return when (c) { true -> "A5 <b>x</b>" else -> "A6 <b>x</b>" }
}

@Language("HTML")
fun p14d(): String {
    return ("A7 <b>x</b>")
}

@Language("HTML")
fun p14e(c: Boolean, d: Boolean): String {
    return if (c) "A8 <b>x</b>" else if (d) "A9 <b>x</b>" else "B1 <b>x</b>"
}

@Language("HTML")
fun p14f(n: String?): String {
    return n ?: run { "B2 <b>x</b>" }
}

@Language("HTML")
fun p14g(c: Boolean): String {
    return if (c)
        "B3 <b>x</b>"
    else
        "B4 <b>x</b>"
}

@Language("HTML")
fun p14h(c: Boolean): String {
    if (c) {
        return "B5 <b>x</b>"
    }
    listOf(1).forEach { if (it > 0) return "B6 <b>x</b>" }
    return "B7 <b>x</b>"
}

@Language("HTML")
fun p14i(c: Boolean) = if (c) { "B8 <b>x</b>" } else "B9 <b>x</b>"

@Language("HTML")
val p14j = if (true) { "C1 <b>x</b>" } else "C2 <b>x</b>"

@Language("HTML")
val p14k = when (1) { 1 -> "C3 <b>x</b>" else -> "C4 <b>x</b>" }

@Language("HTML")
fun p14l(n: String): String {
    val t = "C5 <b>x</b>"
    return t
}

@Language("HTML")
fun p14m(n: String): String {
    return n + "C6 <b>x</b>" + n
}

@Language("HTML")
fun p14n(c: Boolean): String {
    return if (c) "C7 <b>x</b>"
    else "C8 <b>x</b>"
}

@Language("HTML")
fun p14o(c: Boolean): String {
    return (if (c) "C9 <b>x</b>" else "D1 <b>x</b>")
}

@Language("HTML")
fun p14p(c: Boolean): String {
    return "D2 <b>x</b>".trim()
}

@Language("HTML")
fun p14q(n: String): String {
    val t = if (n.isEmpty()) "D3 <b>x</b>" else n
    return t
}

@Language("HTML")
fun p14r(n: String): String {
    return "D4 <b>" + n + "D5 </b>"
}
