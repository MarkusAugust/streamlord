import org.intellij.lang.annotations.Language

@Language("HTML")
fun p73a(n: Int): String {
    return when (val m = n + 1) {
        1 -> "S01 <p>a</p>"
        else -> "S02 <p>b</p>"
    }
}

@Language("HTML")
fun p73b(n: Int, c: Boolean): String {
    when (val m = if (c) return "S03 <p>a</p>" else n) {
        1 -> return "S04 <p>b</p>"
    }
    return "S05 <p>c</p>"
}

@Language("HTML")
fun p73c(s: String): String {
    return when (s) {
        "S06 k" -> "S07 <p>a</p>"
        in listOf("S08 l") -> "S09 <p>b</p>"
        else -> "S10 <p>c</p>"
    }
}

@Language("HTML")
fun p73d(s: Any): String {
    return when (s) {
        is String -> if (s == "S11 x") "S12 <p>a</p>" else "S13 <p>b</p>"
        else -> "S14 <p>c</p>"
    }
}

fun p73e(s: Any): String {
    @Language("HTML")
    val x = when (s) {
        is String -> "S15 a"
        else -> return "S16 b"
    }
    return x
}

fun p73f(n: Int): String {
    // language=HTML
    val x = when (val m = n) {
        1 -> "S17 a"
        else -> if (n > 0) "S18 b" else return "S19 c"
    }
    return x
}

@Language("HTML")
fun p73g(n: Int): String {
    val x = when (n) {
        1 -> return "S20 <p>a</p>"
        else -> n
    }
    return when (x) { 2 -> "S21 <p>b</p>"; else -> "S22 <p>c</p>" }
}

@Language("HTML")
fun p73h(n: Int, c: Boolean): String {
    when (n) {
        1 -> { val y = if (c) return "S23 <p>a</p>" else "S24 b"; println(y) }
        else -> if (c) return "S25 <p>b</p>"
    }
    return "S26 <p>c</p>"
}
