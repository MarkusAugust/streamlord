import org.intellij.lang.annotations.Language

@Language("HTML")
fun p48a(c: Boolean): String {
    val s = "f01 <p>a</p>"
    return s
}

@Language("HTML")
fun p48b(c: Boolean): String {
    val s = if (c) "f02 <p>a</p>" else "f03 <p>b</p>"
    return s
}

@Language("HTML")
fun p48c(c: Boolean): String {
    val s = "f04 <p>a</p>" + "f05 <p>b</p>"
    return s
}

@Language("HTML")
fun p48d(c: Boolean): String {
    var s = "f06 <p>a</p>"
    s += "f07 <p>b</p>"
    return s
}

@Language("HTML")
fun p48e(c: Boolean): String {
    val s = buildString { append("f08 <p>a</p>") }
    return s
}

@Language("HTML")
fun p48f(c: Boolean): String {
    val s: String = "f09 <p>a</p>"
    return s.trim()
}

@Language("HTML")
fun p48g(c: Boolean): String {
    val s = """
        f10 <p>a</p>
    """.trimIndent()
    return s
}

@Language("HTML")
fun p48h(c: Boolean): String {
    val a = if (c) "f11 x" else return "f12 <p>early</p>"
    val b = when {
        c -> "f13 y"
        else -> return "f14 <p>early2</p>"
    }
    return a + b
}

@Language("HTML")
fun p48i(items: List<String>): String {
    for (i in items) {
        if (i.isEmpty()) return "f15 <p>empty</p>"
    }
    items.forEach {
        if (it == "x") return "f16 <p>x</p>"
    }
    while (true) {
        return "f17 <p>loop</p>"
    }
}

@Language("HTML")
fun p48j(c: Boolean): String {
    return when {
        c -> {
            val inner = "f18 <p>inner</p>"
            inner
        }
        else -> "f19 <p>else</p>"
    }
}

@Language("HTML")
fun p48k(c: Boolean): String {
    if (c) {
        return """
            f20 <p>multi</p>
        """.trimIndent()
    }
    return "f21 <p>${if (c) "f22 a" else "f23 b"}</p>"
}

@Language("HTML")
fun p48l(c: String?): String {
    return c ?: "f24 <p>default</p>"
}

@Language("HTML")
fun p48m(c: String?): String {
    return (c ?: "f25 <p>default</p>")
}

@Language("HTML")
fun p48n(c: Boolean): String {
    return "f26 <p>" + (if (c) "f27 a" else "f28 b") + "f29 </p>"
}

@Language("HTML")
fun p48o(c: Boolean): String {
    return "f30 <p>a</p>".also { println("f31 log") }
}

@Language("HTML")
fun p48p(c: Boolean): String {
    return String.format("f32 <p>%s</p>", "f33 x")
}

@Language("HTML")
fun p48q(c: Boolean): String {
    val s = "f34 <p>a</p>"
    val t = s
    return t
}

@Language("HTML")
fun p48r(c: Boolean): String {
    val s = "f35 <p>a</p>"
    run {
        val s = "f36 inner"
        println(s)
    }
    return s
}

fun p48s(c: Boolean): String {
    val s = "f37 plain"
    return s
}

@Language("HTML")
fun p48t(c: Boolean): String = if (c) {
    "f38 <p>a</p>"
} else {
    val x = "f39 inner"
    "f40 <p>$x</p>"
}

@Language("HTML")
fun p48u(c: Boolean): String {
    return try {
        "f41 <p>ok</p>"
    } catch (e: Exception) {
        "f42 <p>fail</p>"
    }
}

@Language("HTML")
fun p48v(c: Boolean): String {
    label@ for (i in 0..2) {
        if (c) return "f43 <p>lbl</p>"
    }
    return "f44 <p>end</p>"
}
