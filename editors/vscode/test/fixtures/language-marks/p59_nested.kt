import org.intellij.lang.annotations.Language

fun p59run(f: () -> String) = f()

@Language("HTML")
fun p59a(c: Boolean): String {
    fun inner(d: Boolean): String {
        if (d) return "D01 a" else return "D02 b"
    }
    if (c) return "D03 <p>a</p>" else return inner(c)
}

@Language("HTML")
fun p59b(c: Boolean): String {
    val r = p59run {
        if (c) return@p59run "D04 a" else "D05 b"
    }
    if (c) return r
    return "D06 <p>e</p>"
}

@Language("HTML")
fun p59c(c: Boolean): String {
    p59run { if (c) return "D07 <p>a</p>" else "D08 b" }
    val g = fun(d: Boolean): String { if (d) return "D09 a"; return "D10 b" }
    return "D11 <p>c</p>" + g(c)
}

fun p59d(c: Boolean): String {
    @Language("HTML")
    fun marked(d: Boolean): String {
        if (d) return "D12 <p>a</p>" else return "D13 <p>b</p>"
    }
    if (c) return "D14 a" else return marked(c)
}

@Language("HTML")
fun p59e(c: Boolean): String {
    val o = object {
        fun f(d: Boolean): String {
            if (d) return "D15 a"
            return "D16 b"
        }
    }
    if (c) return o.f(c) else return "D17 <p>c</p>"
}

@Language("HTML")
fun p59f(c: Boolean): String {
    val local = if (c) {
        fun h(): String { if (c) return "D18 a" else return "D19 b" }
        h()
    } else return "D20 <p>x</p>"
    return local
}

@Language("HTML")
fun p59g(c: Boolean): String {
    val x = run {
        if (c) return "D21 <p>a</p>"
        "D22 b"
    }
    return x
}
