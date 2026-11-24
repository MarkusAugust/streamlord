import org.intellij.lang.annotations.Language

fun p41a(c: Boolean, d: Boolean): String = when {
    c -> /* language=HTML */ "h01 <p>a</p>"
    d -> "h02 <p>b</p>"
    else -> "h03 <p>c</p>"
}

fun p41b(c: Boolean): String = when {
    c -> /* language=HTML */ if (c) "h04 <p>a</p>" else "h05 <p>b</p>" // missed: #347
    else -> "h06 <p>c</p>"
}

fun p41c(c: Boolean): String = when {
    c -> /* language=HTML */ "h07 <p>a</p>"
    else -> /* language=HTML */ "h08 <p>b</p>"
}

fun p41d(c: Boolean): String = when {
    c -> /* language=HTML */ "h09 <p>a</p>" else -> "h10 <p>b</p>"
}

fun p41e(c: Boolean): String = when {
    c -> /* language=HTML */ "h11 <p>a</p>"; else -> "h12 <p>b</p>"
}

fun p41f(c: Boolean): String = when (c) {
    true -> /* language=HTML */ "h13 <p>a</p>"
    false -> "h14 <p>b</p>"
}

fun p41g(c: Boolean): String = when {
    c -> /* language=HTML */ "h15 <p>a</p>" +
        "h16 <p>b</p>" // missed: #347
    else -> "h17 <p>c</p>"
}

fun p41h(c: Boolean): String = if (c) /* language=HTML */ "h18 <p>a</p>"
else "h19 <p>b</p>"

fun p41i(c: Boolean): String = if (c) "h20 x" else
    /* language=HTML */ "h21 <p>b</p>"

fun p41j(c: String?): String = c
    ?: /* language=HTML */ "h22 <p>b</p>"

fun p41k(c: String?, d: String?): String = c ?: /* language=HTML */ d ?: "h23 <p>b</p>" // missed: #347

fun p41l(c: Boolean): String {
    return when {
        c -> /* language=HTML */ "h24 <p>a</p>"
        else -> "h25 <p>b</p>"
    }
}

fun p41m(c: Boolean): String {
    val s = when {
        c -> /* language=HTML */ "h26 <p>a</p>"
        else -> "h27 <p>b</p>"
    }
    return s
}

fun p41n(c: Boolean): String = when {
    c -> {
        /* language=HTML */ "h28 <p>a</p>"
    }
    else -> "h29 <p>b</p>"
}

fun p41o(c: Boolean): String = when {
    c -> run { /* language=HTML */ "h30 <p>a</p>" }
    else -> "h31 <p>b</p>"
}

fun p41p(c: Boolean): String = when {
    c -> listOf(/* language=HTML */ "h32 <p>a</p>", "h33 <p>b</p>").first()
    else -> "h34 <p>c</p>"
}

fun p41q(c: Boolean): String = when {
    c -> /* language=HTML */ "h35 <p>a</p>".trim()
    else -> "h36 <p>b</p>"
}

fun p41r(c: Boolean): String = when {
    c -> /* language=HTML */ """
        h37 <p>a</p>
    """.trimIndent()
    else -> "h38 <p>b</p>"
}

fun p41s(c: Boolean): String = when {
    c -> "h39 x"
    // language=HTML
    else -> if (c) "h40 <p>a</p>" else "h41 <p>b</p>"
}

fun p41t(c: Boolean, d: Boolean): String = when {
    // language=HTML
    c -> when {
        d -> "h42 <p>a</p>"
        else -> "h43 <p>b</p>"
    }
    else -> "h44 <p>c</p>"
}

fun p41u(c: Boolean): String = when {
    c -> "h45 x"
    else -> {
        // language=HTML
        val s = "h46 <p>a</p>"
        s
    }
}

fun p41v(c: Boolean): String {
    when {
        c -> {
            // language=HTML
            return "h47 <p>a</p>"
        }
        else -> return "h48 <p>b</p>"
    }
}
