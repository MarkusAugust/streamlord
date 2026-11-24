import org.intellij.lang.annotations.Language

annotation class P60

@Language("HTML")
fun p60a(c: Boolean, n: Int): String {
    val s = n +
        1
    if (c) return "E01 <p>a</p>" else println("E02 x")
    return "E03 <p>b</p>"
}

@Language("HTML")
fun p60b(c: Boolean, n: Int): String {
    val s = "E04 x" +
        if (c) return "E05 <p>a</p>" else "E06 b"
    return s
}

@Language("HTML")
fun p60c(c: Boolean, n: Int): String {
    var m = n
    m--
    if (c) return "E07 <p>a</p>" else return "E08 <p>b</p>"
}

@Language("HTML")
fun p60d(c: Boolean): String {
    val t: Map<String, List<Int>>
        = mapOf()
    if (c) return "E09 <p>a</p>"
    val u = mutableListOf<Int>()
    if (c) return "E10 <p>b</p>" else return "E11 <p>c</p>" + t + u
}

@Language("HTML")
fun p60e(c: Boolean): String {
    @P60
    if (c) return "E12 <p>a</p>" else println("E13 x")
    val v = 'x'
    if (c) return "E14 <p>b</p>"
    val w = 1.0
    if (c) return "E15 <p>c</p>"
    val k = true
    if (k) return "E16 <p>d</p>"
    return "E17 <p>e</p>"
}

@Language("HTML")
fun p60f(c: Boolean, n: Int): String {
    val s = n *
    if (c) return "E18 <p>a</p>" else 2
    val t = c &&
        if (c) return "E19 <p>b</p>" else true
    val u = c ||
        if (c) return "E20 <p>c</p>" else false
    return "E21 <p>d</p>" + s + t + u
}

@Language("HTML")
fun p60g(c: Boolean, n: Int?): String {
    val s = n ?:
        if (c) return "E22 <p>a</p>" else 3
    val t = n
        ?: if (c) return "E23 <p>b</p>" else 4
    return "E24 <p>c</p>" + s + t
}

@Language("HTML")
fun p60h(c: Boolean): String {
    val s = "E25 a" // a comment ending in +
    if (c) return "E26 <p>a</p>" else return "E27 <p>b</p>" + s
}

@Language("HTML")
fun p60i(c: Boolean, n: Int): String {
    var m = n
    m++
    if (c) return "E28 <p>a</p>" else m += 1
    val x = n.toString()
    if (c) return "E29 <p>b</p>" else return x
}

@Language("HTML")
fun p60j(c: Boolean): String {
    val s = """E30 raw"""
    if (c) return "E31 <p>a</p>" else return "E32 <p>b</p>" + s
}

@Language("HTML")
fun p60k(c: Boolean, n: Int): String {
    val s = n as Int?
    if (c) return "E33 <p>a</p>"
    val r = n is Int
    if (r) return "E34 <p>b</p>" else return "E35 <p>c</p>" + s
}

@Language("HTML")
fun p60z(b: Boolean): Any {
    return "Z1 x" to if (b) "Z2 <p>a</p>" else "Z3 <p>b</p>"
}

@Language("HTML")
fun p60y(b: Boolean): Any {
    return 1 == if (b) "Z4 <p>a</p>" else "Z5 <p>b</p>"
}

@Language("HTML")
fun p60x(b: Boolean): Any {
    return "Z6 a" <= if (b) "Z7 <p>a</p>" else "Z8 <p>b</p>"
}
