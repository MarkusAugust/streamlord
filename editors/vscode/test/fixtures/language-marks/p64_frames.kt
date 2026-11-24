import org.intellij.lang.annotations.Language

fun p64run(f: () -> Unit) = f()

@Language("HTML")
fun p64a(c: Boolean): String {
    p64run {
        if (c) return "I01 <p>a</p>" else println("I02 x")
    }
    if (c) return "I03 <p>b</p>"
    else p64run { println("I04 y") }
    return "I05 <p>c</p>"
}

@Language("HTML")
fun p64b(c: Boolean): String {
    for (i in 0..2) {
        if (i == 1) return "I06 <p>a</p>" else continue
    }
    while (c) {
        if (c) break else return "I07 <p>b</p>"
    }
    return "I08 <p>c</p>"
}

@Language("HTML")
fun p64c(c: Boolean): String {
    do {
        if (c) return "I09 <p>a</p>"
    } while (false)
    try {
        if (c) return "I10 <p>b</p>" else throw Exception("I11 x")
    } finally {
        println("I12 y")
    }
}

@Language("HTML")
fun p64d(c: Boolean): String {
    val f = { d: Boolean -> if (d) "I13 a" else "I14 b" }
    if (c) return f(c) else return "I15 <p>c</p>"
}

@Language("HTML")
fun p64e(c: Boolean): String {
    label@ for (i in 0..1) {
        if (c) return "I16 <p>a</p>" else break@label
    }
    return "I17 <p>b</p>"
}

class P64F {
    @Language("HTML")
    fun f(c: Boolean): String {
        val g = object : Runnable {
            override fun run() { if (c) return else println("I18 x") }
        }
        g.run()
        if (c) return "I19 <p>a</p>" else return "I20 <p>b</p>"
    }
}

@Language("HTML")
fun p64g(c: Boolean): String = if (c) "I21 <p>a</p>" else "I22 <p>b</p>"

@Language("HTML")
fun p64h(c: Boolean): String =
    if (c) "I23 <p>a</p>"
    else if (!c) "I24 <p>b</p>"
    else "I25 <p>c</p>"
