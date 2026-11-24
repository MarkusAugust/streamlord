import org.intellij.lang.annotations.Language

@Language("HTML")
fun p77a(c: Boolean): String {
    val y = 1 // a comment
    return "X01 <p>a</p>"
}

@Language("HTML")
fun p77b(c: Boolean, s: String?): String {
    val y = s!!
    return "X02 <p>a</p>"
}

@Language("HTML")
fun p77c(c: Boolean): String {
    val y = listOf<String>()
    return "X03 <p>a</p>"
}

@Language("HTML")
fun p77d(c: Boolean): String {
    val y = 'x'
    return "X04 <p>a</p>"
}

@Language("HTML")
fun p77e(c: Boolean, n: Int): String {
    val y = """
        X05 raw
    """.trimIndent()
    return "X06 <p>a</p>"
}

@Language("HTML")
fun p77f(c: Boolean, n: Int): String {
    val y = "X07 $n ${n + 1}"
    return "X08 <p>a</p>"
}

@Language("HTML")
fun p77g(c: Boolean): String {
    val y = if (c) {
        1
    } else {
        2
    }
    return "X09 <p>a</p>"
}

@Language("HTML")
fun p77h(c: Boolean): String {
    val y = try {
        1
    } catch (e: Exception) {
        2
    }
    return "X10 <p>a</p>"
}

@Language("HTML")
fun p77i(c: Boolean): String {
    val y = listOf(1)
        .map { it }
        .first()
    return "X11 <p>a</p>"
}

@Language("HTML")
fun p77j(c: Boolean): String {
    val y: Map<String, List<Int>> = emptyMap()
    return "X12 <p>a</p>"
}

@Language("HTML")
fun p77k(c: Boolean): String {
    val y = object {
        val z = 1
    }
    return "X13 <p>a</p>"
}

@Language("HTML")
fun p77l(c: Boolean): String {
    val y = 1 /* block */
    return "X14 <p>a</p>"
}

@Language("HTML")
fun p77m(c: Boolean): String {
    val (a, b) = 1 to 2 // pair
    return "X15 <p>a</p>"
}

@Language("HTML")
fun p77n(c: Boolean): String {
    val (a, b) = listOf(1, 2)
        .let { it[0] to it[1] }
    return "X16 <p>a</p>"
}

@Language("HTML")
fun p77o(c: Boolean): String {
    var y = 1
    y++
    return "X17 <p>a</p>"
}

@Language("HTML")
fun p77p(c: Boolean): String {
    val y = c
    if (y) {
        return "X18 <p>a</p>"
    }
    return "X19 <p>b</p>"
}

@Language("HTML")
fun p77q(c: Boolean): String {
    val y = when {
        c -> 1
        else -> 2
    }
    return "X20 <p>a</p>"
}

@Language("HTML")
fun p77r(c: Boolean): String {
    val y = 1; val z = 2
    return "X21 <p>a</p>"
}

@Language("HTML")
fun p77s(c: Boolean): String {
    val y = (1
        + 2)
    return "X22 <p>a</p>"
}

@Language("HTML")
fun p77t(c: Boolean): String {
    val y by lazy { 1 }
    return "X23 <p>a</p>"
}

@Language("HTML")
fun p77u(c: Boolean): String {
    val y = c
    else2()
    return "X24 <p>a</p>"
}

fun else2() {}

@Language("HTML")
fun p77v(c: Boolean): String {
    val y = c ==
        true
    return "X25 <p>a</p>"
}

@Language("HTML")
fun p77w(c: Boolean): String {
    val y = c as Any
    return "X26 <p>a</p>"
}

@Language("HTML")
fun p77x(c: Boolean): String {
    val y = c
    @Suppress("UNUSED")
    val z = 2
    return "X27 <p>a</p>"
}

@Language("HTML")
fun p77y(c: Boolean, l: List<Int>): String {
    val y = l[0]
    return "X28 <p>a</p>"
}

@Language("HTML")
fun p77z(c: Boolean): String {
    val y = Unit
    return "X29 <p>a</p>"
}
