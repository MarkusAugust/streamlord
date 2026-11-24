import org.intellij.lang.annotations.Language

@Language("HTML")
val p74top = "T01 a"

val p74plain = run { "T02 b" }

class P74A {
    @Language("HTML")
    val p = "T03 a"
    val q = object {
        @Language("HTML")
        fun f(c: Boolean): String { if (c) return "T04 a"; return "T05 b" }
    }
    var r = "T06 c" // missed: #347
    @Language("HTML")
    fun g(c: Boolean): String {
        if (c) return "T07 a"
        return r
    }
}

@Language("HTML")
fun p74b(c: Boolean): String {
    var a = "T08 a"
    var b = a
    if (c) return "T09 <p>b</p>"
    return b
}

fun p74c(c: Boolean): String {
    @Language("HTML")
    var a = "T10 a"
    a = "T11 b" // missed: #347
    if (c) return "T12 c"
    return a
}

@Language("HTML")
fun p74d(c: Boolean): String {
    val a = run {
        @Language("HTML")
        val b = if (c) return "T13 a" else "T14 b"
        b
    }
    return a
}

fun p74e(c: Boolean): String {
    @Language("HTML")
    val a = run {
        val b = if (c) return "T15 a" else "T16 b"
        b
    }
    return a
}

@Language("HTML")
fun p74f(c: Boolean): String {
    val a = "T17 a"; val b = if (c) return "T18 <p>a</p>" else "T19 b"; return a + b
}

@Language("HTML")
fun p74g(c: Boolean): String {
    val a = if (c) "T20 a" else "T21 b"
    val b = if (c) return a else "T22 c"
    return b
}

fun p74h(c: Boolean): String {
    // language=HTML
    val a = if (c) "T23 a" else return "T24 b"
    val b = if (c) "T25 c" else return "T26 d"
    return a + b
}

fun p74i(c: Boolean): String {
    @Language("HTML")
    val a = if (c) "T27 a" else return "T28 b"
    @Language("HTML")
    val b: String = if (c) "T29 c" else return "T30 d"
    return a + b
}

@Language("HTML")
fun p74j(c: Boolean): String {
    val a = if (c) "T31 a" else return when { c -> "T32 <p>b</p>"; else -> "T33 <p>c</p>" }
    return a
}

@Language("HTML")
fun p74k(c: Boolean): String {
    val a: () -> String = { if (c) "T34 a" else "T35 b" }
    val b = fun(): String { return "T36 b" }
    return if (c) "T37 <p>a</p>" else a() + b()
}

@Language("HTML")
fun p74l(c: Boolean): String {
    val a = c
    val b = "T38 a".takeIf { a } ?: return "T39 <p>z</p>"
    return b
}

@Language("HTML")
fun p74m(c: Boolean, n: Int): String {
    val a = n
    when (a) {
        1 -> return "T40 <p>a</p>"
    }
    val b = 1; if (c) return "T41 <p>b</p>" else return "T42 <p>c</p>"
}
