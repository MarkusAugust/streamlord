import org.intellij.lang.annotations.Language

@Language("HTML")
fun p67a(c: Boolean): String {
    val x = if (c) return "L01 <p>a</p>" else "L02 b"
    return x
}

@Language("HTML")
fun p67b(c: Boolean): String {
    @Language("HTML")
    val x = if (c) return "L03 <p>a</p>" else "L04 b"
    return x
}

fun p67c(c: Boolean): String {
    @Language("HTML")
    val x = if (c) return "L05 a" else "L06 b"
    return x
}

fun p67d(c: Boolean): String {
    // language=HTML
    val x = if (c) return "L07 a" else "L08 b"
    return x
}

@Language("HTML")
fun p67e(c: Boolean): String {
    // language=HTML
    val x = if (c) return "L09 <p>a</p>" else "L10 b"
    return x
}

@Language("HTML")
fun p67f(c: Boolean): String {
    var x = if (c) return "L11 <p>a</p>" else "L12 b"
    x = if (c) return "L13 <p>a</p>" else "L14 b"
    return x
}

@Language("HTML")
fun p67g(c: Boolean, s: String?): String {
    val x = s ?: return "L15 <p>a</p>"
    val y = s?.let { return "L16 <p>a</p>" } ?: "L17 b"
    return x + y
}

@Language("HTML")
fun p67h(c: Boolean): String {
    val x = when {
        c -> return "L18 <p>a</p>"
        else -> "L19 b"
    }
    val y: String
    y = if (c) return "L20 <p>a</p>" else "L21 b"
    return x + y
}

@Language("HTML")
fun p67i(c: Boolean): String {
    val x = listOf(1).map { if (c) return "L22 <p>a</p>" else "L23 b" }
    val y = listOf(1).map(fun(i: Int): String { if (c) return "L24 a"; return "L25 b" })
    return "L26 <p>c</p>" + x + y
}

@Language("HTML")
fun p67j(c: Boolean): String {
    val x = "L27 a"; if (c) return "L28 <p>b</p>"
    val y = "L29 a"
    return if (c) "L30 <p>c</p>" else y
}

@Language("HTML")
fun p67k(c: Boolean): String {
    val x = run {
        if (c) return@run "L31 a"
        "L32 b"
    }
    val y = run inner@{
        if (c) return@inner "L33 a"
        return "L34 <p>b</p>"
    }
    return x + y
}

@Language("HTML")
fun p67l(c: Boolean): String {
    val x =
        if (c) {
            return "L35 <p>a</p>"
        } else {
            "L36 b"
        }
    return x
}

@Language("HTML")
fun p67m(c: Boolean): String {
    val x = foo(if (c) return "L37 <p>a</p>" else "L38 b")
    return x
}

fun foo(s: String) = s

@Language("HTML")
val p67n: String get() {
    val x = if (true) return "L39 <p>a</p>" else "L40 b"
    return x
}
