import org.intellij.lang.annotations.Language

@Language("HTML")
fun p70a(c: Boolean, s: String): Any {
    return s == if (c) "P01 a" else "P02 b"
}

@Language("HTML")
fun p70b(c: Boolean, s: String): Any {
    return s != when { c -> "P03 a"; else -> "P04 b" }
}

@Language("HTML")
fun p70c(c: Boolean): String {
    val ok = 1 <= if (c) 2 else 3
    if (ok) return "P05 <p>a</p>"
    val ok2 = 1 >= if (c) 2 else 3
    return if (ok2) "P06 <p>b</p>" else "P07 <p>c</p>"
}

@Language("HTML")
fun p70d(c: Boolean): String {
    val r = 1..if (c) 2 else 3
    return if (c) "P08 <p>a</p>" else "P09 <p>b</p>" + r
}

@Language("HTML")
fun p70e(c: Boolean): String {
    val b = !if (c) true else false
    return if (b) "P10 <p>a</p>" else "P11 <p>b</p>"
}

@Language("HTML")
fun p70f(c: Boolean): String {
    return "P12 a" + if (c) "P13 b" else "P14 c"
}

@Language("HTML")
fun p70g(c: Boolean): String {
    return if (c) "P15 <p>a</p>" else "P16 <p>b</p>" + if (c) "P17 c" else "P18 d"
}

@Language("HTML")
fun p70h(c: Boolean, d: Boolean): String {
    if (c == if (d) true else false) return "P19 <p>a</p>"
    return "P20 <p>b</p>"
}

@Language("HTML")
fun p70i(c: Boolean, s: String): String {
    return if (s == "P21 x") "P22 <p>a</p>" else "P23 <p>b</p>"
}

@Language("HTML")
fun p70j(c: Boolean): String {
    val x = c == if (c) true else false; return if (x) "P24 <p>a</p>" else "P25 <p>b</p>"
}

@Language("HTML")
fun p70k(c: Boolean): String {
    val x = c ==
        if (c) true else false
    return if (x) "P26 <p>a</p>" else "P27 <p>b</p>"
}

@Language("HTML")
fun p70l(c: Boolean): String {
    val n = listOf(1, if (c) 2 else 3)
    return if (c) "P28 <p>a</p>" else "P29 <p>b</p>"
}

@Language("HTML")
fun p70m(c: Boolean): String {
    val n = foo2(c == if (c) true else false)
    return if (c) "P30 <p>a</p>" else "P31 <p>b</p>"
}

fun foo2(b: Boolean) = b

@Language("HTML")
fun p70n(c: Boolean): String = "P32 a" + if (c) "P33 b" else "P34 c"

@Language("HTML")
fun p70o(c: Boolean): String = if (c) "P35 <p>a</p>" else "P36 <p>b</p>"

@Language("HTML")
fun p70p(c: Boolean, s: String): String {
    return (s == if (c) "P37 a" else "P38 b").toString()
}

@Language("HTML")
fun p70q(c: Boolean, s: String): String {
    return s.ifEmpty { if (c) "P39 a" else "P40 b" }
}

@Language("HTML")
fun p70r(c: Boolean, n: Int): String {
    return if (n > if (c) 1 else 2) "P41 <p>a</p>" else "P42 <p>b</p>"
}

@Language("HTML")
fun p70s(c: Boolean, n: Int): String {
    return if (n < 2) "P43 <p>a</p>" else "P44 <p>b</p>"
}

@Language("HTML")
fun p70t(c: Boolean, n: Int): String {
    val x = n * if (c) 1 else 2
    val y = n - if (c) 1 else 2
    val z = c && if (c) true else false
    return if (c) "P45 <p>a</p>" else "P46 <p>b</p>"
}
