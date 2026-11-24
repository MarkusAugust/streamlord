import org.intellij.lang.annotations.Language

fun p57sink(s: String, t: String = "") = s + t

@Language("HTML")
fun p57a(c: Boolean): String {
    val x = p57sink(if (c) return "B01 <p>a</p>" else "B02 b", "B03 c")
    val y = p57sink("B04 d", if (c) "B05 e" else return "B06 <p>f</p>")
    return x + y
}

@Language("HTML")
fun p57b(c: Boolean, d: Boolean): String {
    var z = "B07 a"
    z = if (c) if (d) return "B08 <p>a</p>" else "B09 b" else "B10 c"
    z += if (c) return "B11 <p>a</p>" else "B12 d"
    return z
}

@Language("HTML")
fun p57c(c: Boolean): String {
    val z = (if (c) return "B13 <p>a</p>" else "B14 b") + "B15 c"
    return z
}

@Language("HTML")
fun p57d(c: Boolean): String {
    val z = listOf(if (c) return "B16 <p>a</p>" else "B17 b", "B18 c")
    val m = mapOf("B19 k" to if (c) return "B20 <p>m</p>" else "B21 v")
    return z.toString() + m
}

@Language("HTML")
fun p57e(c: Boolean, s: String?): String {
    val z = s ?: if (c) return "B22 <p>a</p>" else "B23 b"
    val w = s?.let { if (c) return "B24 <p>l</p>" else "B25 v" } ?: "B26 n"
    return z + w
}

@Language("HTML")
fun p57f(c: Boolean): String {
    val z = if (c) {
        return "B27 <p>a</p>"
    } else {
        "B28 b"
    }
    p57sink(
        if (c) return "B29 <p>c</p>"
        else "B30 d",
    )
    return z
}

@Language("HTML")
fun p57g(c: Boolean): String {
    val arr = arrayOf("B31 x")
    arr[0] = if (c) return "B32 <p>a</p>" else "B33 b"
    p57sink(t = if (c) return "B34 <p>n</p>" else "B35 n", s = "B36 s")
    return arr[0]
}
