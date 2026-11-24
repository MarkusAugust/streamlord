import org.intellij.lang.annotations.Language

@Language("HTML")
fun p96a(items: List<String>): String {
    items.forEach { if (it.isEmpty()) return@forEach }
    val first = items.firstOrNull() ?: return "R28 <p>empty</p>"
    return "R29 <p>$first</p>"
}

@Language("HTML")
fun p96b(items: List<String>): String {
    val mapped = items.map { item ->
        if (item.isBlank()) return@map "R30 blank"
        "R31 $item"
    }
    return "R32 <ul>${mapped.joinToString("R33 ")}</ul>"
}

@Language("HTML")
fun p96c(n: Int): String {
    if (n < 0) throw IllegalArgumentException("R34 negative <b>")
    require(n < 100) { "R35 too big" }
    check(n != 5, { "R36 five" })
    return "R37 <p>$n</p>"
}

@Language("HTML")
fun p96d(n: Int): String {
    val label: String = if (n > 1) "R38 many" else "R39 one"
    val other = when {
        n == 0 -> "R40 zero"
        else -> "R41 some"
    }
    return "R42 <p>$label $other</p>"
}

@Language("HTML")
fun p96e(s: String?): String {
    s ?: return "R43 <i>null</i>"
    return s.let { "R44 <b>$it</b>" }
}
