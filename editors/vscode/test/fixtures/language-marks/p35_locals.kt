import org.intellij.lang.annotations.Language

@Language("HTML")
fun p35a(c: Boolean): String {
    var out = "b01 <p>start</p>"
    if (c) out = "b02 <p>c</p>"
    return out
}

@Language("HTML")
fun p35b(c: Boolean): String {
    var out: String
    out = "b03 <p>x</p>"
    out += "b04 <p>y</p>"
    return out
}

@Language("HTML")
fun p35c(c: Boolean): String {
    val out = "b05 <p>outer</p>"
    run {
        val out = "b06 <p>inner</p>"
        println(out)
    }
    return out
}

@Language("HTML")
fun p35d(c: Boolean): String {
    val out = "b07 <p>outer</p>"
    if (c) {
        val out = "b08 <p>inner</p>"
        return out
    }
    return out
}

@Language("HTML")
fun p35e(c: Boolean): String {
    val a = "b09 <p>a</p>"
    val b = "b10 <p>b</p>"
    return if (c) a else b
}

@Language("HTML")
fun p35f(c: Boolean): String {
    val a = "b11 <p>a</p>"
    val b = "b12 <p>b</p>"
    return a + b
}

@Language("HTML")
fun p35g(c: Boolean): String {
    val a = "b13 <p>a</p>"
    return a.trim()
}

@Language("HTML")
fun p35h(c: Boolean): String {
    val a = """
        b14 <p>a</p>
    """.trimIndent()
    return a
}

@Language("HTML")
fun p35i(c: Boolean): String {
    val a: String = when {
        c -> "b15 <p>c</p>"
        else -> "b16 <p>d</p>"
    }
    return a
}

@Language("HTML")
fun p35j(c: String?): String {
    val a = c ?: "b17 <p>none</p>"
    return a
}

@Language("HTML")
fun p35k(c: Boolean): String {
    val a = "b18 <p>a</p>"
    val b = a
    return b
}

@Language("HTML")
fun p35l(c: Boolean): String {
    val a = "b19 not returned"
    val b = "b20 <p>b</p>"
    println(a)
    return b
}

@Language("HTML")
fun p35m(c: Boolean): String {
    var a = "b21 first"
    a = "b22 <p>second</p>"
    return a
}

@Language("HTML")
fun p35n(c: Boolean): String {
    val a by lazy { "b23 <p>lazy</p>" }
    return a
}

@Language("HTML")
fun p35o(c: Boolean): String {
    val (a, b) = "b24 x" to "b25 y"
    return a
}

@Language("HTML")
fun p35p(c: Boolean): String {
    val a = "b26 <p>a</p>"
    fun inner(): String {
        val a = "b27 inner"
        return a
    }
    return a
}

@Language("HTML")
fun p35q(items: List<String>): String {
    val a = "b28 <p>x</p>"
    return items.firstOrNull() ?: a
}

@Language("HTML")
fun p35r(c: Boolean): String {
    val a = "b29 <p>a</p>"
    return (a)
}

@Language("HTML")
fun p35s(c: Boolean): String {
    val a = "b30 <p>a</p>"
    return "b31 <div>$a</div>"
}

@Language("HTML")
fun p35t(c: Boolean): String {
    val a = "b32 <p>a</p>"; return a
}

@Language("HTML")
fun p35u(c: Boolean): String {
    val a = "b33 <p>a</p>"
    return a.let { it }
}

fun p35v(): String {
    @Language("HTML")
    val a = "b34 <p>a</p>"
    val b = "b35 <p>b</p>"
    return a + b
}

fun p35w(): String {
    // language=HTML
    val a = "b36 <p>a</p>"
    return a
}

fun p35x(): String {
    // language=HTML
    var a = "b37 <p>a</p>"
    a = "b38 <p>b</p>"
    return a
}

@Language("HTML")
fun p35y(c: Boolean): String {
    val a = if (c) "b39 <p>a</p>" else return "b40 <p>early</p>"
    return a
}

@Language("HTML")
fun p35z(c: Boolean): String {
    lateinit var a: String
    a = "b41 <p>late</p>"
    return a
}
