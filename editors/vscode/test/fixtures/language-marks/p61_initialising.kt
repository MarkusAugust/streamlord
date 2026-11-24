import org.intellij.lang.annotations.Language

@Language("HTML")
fun p61a(c: Boolean, d: Boolean): String {
    val a = if (c) {
        val b = if (d) return "F01 <p>a</p>" else "F02 b"
        b
    } else "F03 c"
    return a
}

@Language("HTML")
fun p61b(c: Boolean): String {
    val a = run {
        val b = "F04 b"
        if (c) return "F05 <p>a</p>"
        b
    }
    val q = "F06 q"
    if (c) return q else return "F07 <p>z</p>" + a
}

@Language("HTML")
fun p61c(c: Boolean): String {
    val a: String = try {
        if (c) return "F08 <p>a</p>" else "F09 b"
    } catch (e: Exception) {
        return "F10 <p>e</p>"
    }
    return a
}

@Language("HTML")
fun p61d(c: Boolean): String {
    val a by lazy { if (c) "F11 a" else "F12 b" }
    if (c) return a
    return "F13 <p>c</p>"
}

class P61E(c: Boolean) {
    @Language("HTML")
    fun f(c: Boolean): String {
        val a = if (c) return "F14 <p>a</p>" else "F15 b"
        val b = if (c) "F16 c" else return "F17 <p>d</p>"
        return a + b
    }
    val p: String = if (c) "F18 a" else "F19 b"
}

@Language("HTML")
fun p61f(c: Boolean): String {
    val (a, b) = if (c) return "F20 <p>a</p>" else Pair("F21 x", "F22 y")
    return a + b
}

@Language("HTML")
fun p61g(c: Boolean): String {
    var a = "F23 a"
    val b = a.also { if (c) return "F24 <p>a</p>" }
    a = b
    return a
}
