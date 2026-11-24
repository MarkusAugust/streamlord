import org.intellij.lang.annotations.Language

@Language("HTML")
var p32v = ""

fun p32a(c: Boolean, s: String) {
    p32v = when {
        c -> "o01 <a>" // missed: #347
        else -> "o02 <b>" + "o03 <c>"
    }
    p32v = when {
        c -> "o04 <d>" // missed: #347
        else -> s + "o05 <e>"
    }
    p32v = when {
        c -> "o06 <f>" + s
        else -> "o07 <g>" // missed: #347
    }
    p32v = when (s) {
        "o08 k" -> "o09 <h>" // missed: #347
        else -> s.trim() + "o10 <i>"
    }
    p32v = if (c) {
        s + "o11 <j>"
    } else {
        "o12 <k>"
    }
    p32v = s ?: when { c -> "o13 <l>" + s; else -> "o14 <m>" }
}

@Language("HTML")
fun p32b(c: Boolean, s: String): String {
    return when {
        c -> "o15 <n>"
        else -> s + "o16 <o>"
    }
}

@Language("HTML")
fun p32c(c: Boolean, s: String): String {
    val t = when {
        c -> "o17 <p>"
        else -> s + "o18 <q>"
    }
    return t
}

@Language("HTML")
fun p32d(c: Boolean, s: String): String = when {
    c -> "o19 <r>"
    else -> "o20 <s>" + s
}

fun p32e(c: Boolean): String = when {
    // language=HTML
    c -> "o21 <t>"
    else -> "o22 <u>"
}

fun p32f(c: Boolean): String {
    // language=HTML
    return if (c) "o23 <v>" else "o24 <w>"
}

fun p32g(c: Boolean): String {
    return if (c) "o25 x" else
        // language=HTML
        "o26 <x>"
}

fun p32h(c: Boolean): String {
    val a = /* language=HTML */ "o27 <y>"
    val b = "o28 <z>"
    return a + b
}

fun p32i(c: Boolean): String {
    val a = listOf(/* language=HTML */ "o29 <a>", "o30 <b>")
    return a.first()
}

fun p32j(c: Boolean): String {
    return p32id(/* language=HTML */ "o31 <c>") + "o32 <d>"
}

fun p32id(s: String) = s
