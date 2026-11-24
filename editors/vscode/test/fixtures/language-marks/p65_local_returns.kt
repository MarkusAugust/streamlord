import org.intellij.lang.annotations.Language

fun p65a(c: Boolean): String {
    @Language("HTML")
    val x = if (c) { if (c) return "J01 a"; "J02 b" } else "J03 c"
    return x
}

fun p65b(c: Boolean): String {
    @Language("HTML")
    val x = if (c) { return "J04 a" } else "J05 c"
    return x
}

fun p65c(c: Boolean): String {
    @Language("HTML")
    val x = if (c) return "J06 a" else "J07 c"
    return x
}

fun p65d(c: Boolean): String {
    @Language("HTML")
    val x = run { if (c) return "J08 a"; "J09 b" }
    return x
}

fun p65e(c: Boolean): String {
    @Language("HTML")
    val x = if (c) {
        if (c) return "J10 a"
        "J11 b"
    } else "J12 c"
    return x
}

fun p65f(c: Boolean): String {
    // language=HTML
    val x = if (c) { if (c) return "J13 a"; "J14 b" } else "J15 c"
    return x
}

fun p65g(c: Boolean): String {
    @Language("HTML")
    val x = if (c) { return "J16 a"; } else { "J17 c" }
    return x
}

fun p65h(c: Boolean, s: String?): String {
    @Language("HTML")
    val x = s ?: return "J18 a"
    @Language("HTML")
    val y = when { c -> return "J19 a"; else -> "J20 b" }
    return x + y
}

fun p65i(c: Boolean): String {
    @Language("HTML")
    val x = if (c) { if (c) { return "J21 a" }; "J22 b" } else "J23 c"
    return x
}

fun p65j(c: Boolean): String {
    @Language("HTML")
    val x = try { if (c) return "J24 a"; "J25 b" } catch (e: Exception) { "J26 c" }
    return x
}

fun p65k(c: Boolean): String {
    @Language("HTML")
    val x = if (c) { println("J27 p"); return "J28 a" } else "J29 c"
    return x
}

fun p65l(c: Boolean): String {
    @Language("HTML")
    val x = if (c) { val y = if (c) return "J30 a" else "J31 b"; y } else "J32 c"
    return x
}
