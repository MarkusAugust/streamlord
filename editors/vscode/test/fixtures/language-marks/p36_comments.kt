import org.intellij.lang.annotations.Language

fun p36id(s: String) = s

fun p36a(c: Boolean): String {
    // language=HTML
    return "c01 <p>a</p>"
}

fun p36b(c: Boolean): String {
    // language=HTML
    return if (c) "c02 <p>a</p>" else "c03 <p>b</p>"
}

fun p36c(c: Boolean): String {
    // language=HTML
    return when {
        c -> "c04 <p>a</p>"
        else -> "c05 <p>b</p>"
    }
}

fun p36d(c: String?): String {
    // language=HTML
    return c ?: "c06 <p>none</p>"
}

fun p36e(c: Boolean): String {
    // language=HTML
    println("c07 x")
    return "c08 <p>a</p>"
}

fun p36f(c: Boolean): String {
    // language=HTML
    p36id("c09 <p>a</p>")
    return "c10 <p>b</p>"
}

fun p36g(c: Boolean): String = when {
    c -> "c11 <p>a</p>"
    // language=HTML
    else -> "c12 <p>b</p>"
}

fun p36h(c: Int): String = when (c) {
    // language=HTML
    1 -> "c13 <p>a</p>"
    2 -> "c14 <p>b</p>"
    else -> "c15 <p>c</p>"
}

fun p36i(c: Int): String {
    when (c) {
        // language=HTML
        1 -> return "c16 <p>a</p>"
        else -> return "c17 <p>b</p>"
    }
}

fun p36j(c: Boolean): String {
    if (c) {
        // language=HTML
        return "c18 <p>a</p>"
    }
    return "c19 <p>b</p>"
}

fun p36k(c: Boolean): String {
    // language=HTML
    val s = if (c) "c20 <p>a</p>" else "c21 <p>b</p>"
    return s
}

fun p36l(c: Boolean): String {
    val s = /* language=HTML */ if (c) "c22 <p>a</p>" else "c23 <p>b</p>"
    return s
}

fun p36m(c: Boolean): String = p36id(/* language=HTML */ "c24 <p>a</p>")

fun p36n(c: Boolean): String = p36id(s = /* language=HTML */ "c25 <p>a</p>")

fun p36o(c: Boolean): Pair<String, String> = Pair("c26 x", /* language=HTML */ "c27 <p>a</p>")

fun p36p(c: Boolean): String = listOf(/* language=HTML */ "c28 <p>a</p>", "c29 <p>b</p>").first()

fun p36q(c: Boolean): String {
    return /* language=HTML */ "c30 <p>a</p>"
}

fun p36r(c: Boolean): String {
    return /* language=HTML */ "c31 <p>a</p>" + "c32 <p>b</p>" // missed: #347
}

fun p36s(c: String?): String = c ?: /* language=HTML */ "c33 <p>a</p>" + "c34 b" // missed: #347

fun p36t(c: Boolean): String {
    val s: String
    s = /* language=HTML */ "c35 <p>a</p>"
    return s
}

fun p36u(c: Boolean): String = when {
    c -> "c36 <p>a</p>"
    else -> /* language=HTML */
        "c37 <p>b</p>"
}

fun p36v(c: Boolean): String {
    // language=HTML
    // another comment
    return "c38 <p>a</p>"
}

fun p36w(c: Boolean): String {
    // language=HTML

    return "c39 <p>a</p>"
}

fun p36x(c: Boolean): String {
    /* language=HTML */
    return "c40 <p>a</p>"
}

fun p36y(c: Boolean): String {
    // language=HTML
    @Suppress("x") // missed: #347
    val s = "c41 <p>a</p>"
    return s
}

fun p36z(c: Boolean): String = when {
    c -> "c42 <p>a</p>" // language=HTML
    else -> "c43 <p>b</p>"
}

fun p36za(c: Boolean): String {
    val s = "c44 x" // language=HTML
    val t = "c45 <p>t</p>"
    return s + t
}

fun p36zb(c: Boolean): String = if (c) {
    // language=HTML
    "c46 <p>a</p>"
} else {
    "c47 <p>b</p>"
}

fun p36zc(c: Boolean): String = when {
    c -> {
        // language=HTML
        "c48 <p>a</p>"
    }
    else -> "c49 <p>b</p>"
}

fun p36zd(c: Boolean): String = when {
    c -> "c50 x"
    /* language=HTML */ else -> "c51 <p>b</p>"
}

fun p36ze(c: Boolean): String = when {
    /* language=HTML */ c -> "c52 <p>a</p>"
    else -> "c53 <p>b</p>"
}

fun p36zf(c: Boolean): String = when { c -> "c54 x" /* language=HTML */ else -> "c55 <p>b</p>" } // missed: #347

fun p36zg(c: Boolean): String = when {
    c -> "c56 x" /* language=HTML */
    else -> "c57 <p>b</p>"
}
