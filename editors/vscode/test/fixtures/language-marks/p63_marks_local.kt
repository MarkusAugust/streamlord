import org.intellij.lang.annotations.Language

fun p63a(c: Boolean): String {
    // language=HTML
    val x = if (c) "H01 a" else "H02 b"
    // language=HTML
    val y = if (c) return "H03 a" else "H04 b"
    return x + y
}

fun p63b(c: Boolean): String {
    @Language("HTML")
    val x = if (c) { if (c) return "H05 a"; "H06 b" } else "H07 c"
    @Language("HTML")
    var y = "H08 a"
    y = if (c) return "H09 b" else "H10 c" // missed: #347
    return x + y
}

fun p63c(c: Boolean): String {
    // language=HTML
    return if (c) "H11 a" else if (!c) "H12 b" else "H13 c"
}

fun p63d(c: Boolean): String {
    // language=HTML
    if (c) return "H14 a" else return "H15 b"
}

fun p63e(c: Boolean, d: Boolean): String {
    @Language("HTML") val x = if (c) "H16 a" else if (d) return "H17 b" else "H18 c"
    return x
}
