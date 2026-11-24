import org.intellij.lang.annotations.Language

sealed interface P38Msg
data class P38Ok(val v: String) : P38Msg
data class P38Err(val e: String) : P38Msg

@Language("HTML")
fun p38a(m: P38Msg): String = when (m) {
    is P38Ok -> "e01 <p>${m.v}</p>"
    is P38Err -> "e02 <p class=\"err\">${m.e}</p>"
}

@Language("HTML")
fun p38b(m: P38Msg): String {
    return when (m) {
        is P38Ok -> {
            val v = m.v
            "e03 <p>$v</p>"
        }
        is P38Err -> {
            println("e04 log")
            "e05 <p>${m.e}</p>"
        }
    }
}

@Language("HTML")
fun p38c(m: P38Msg?): String {
    val ok = m as? P38Ok ?: return "e06 <p>not ok</p>"
    return "e07 <p>${ok.v}</p>"
}

@Language("HTML")
fun p38d(n: Int): String = when {
    n < 0 -> "e08 <p>neg</p>"
    n == 0 -> "e09 <p>zero</p>"
    n in 1..9 -> if (n % 2 == 0) "e10 <p>even</p>" else "e11 <p>odd</p>"
    else -> when (n) {
        10 -> "e12 <p>ten</p>"
        else -> "e13 <p>big</p>"
    }
}

@Language("HTML")
fun p38e(n: Int): String {
    val x = when (n) { 1 -> "e14 one"; else -> "e15 other" }
    return "e16 <p>$x</p>"
}

@Language("HTML")
fun p38f(a: String?, b: Boolean): String =
    a?.takeIf { b }
        ?.let { "e17 <p>$it</p>" }
        ?: "e18 <p>none</p>"

@Language("HTML")
fun p38g(a: String?, b: Boolean): String {
    return if (a == null) {
        "e19 <p>null</p>"
    } else if (b) {
        "e20 <p>b</p>"
    } else {
        when {
            a.isEmpty() -> "e21 <p>empty</p>"
            else -> "e22 <p>$a</p>"
        }
    }
}

@Language("HTML")
fun p38h(a: Boolean): String {
    return if (a)
        "e23 <p>a</p>"
    else
        "e24 <p>b</p>"
}

@Language("HTML")
fun p38i(a: Boolean): String {
    return when {
        a ->
            "e25 <p>a</p>"
        else ->
            "e26 <p>b</p>"
    }
}

@Language("HTML")
fun p38j(a: String?): String {
    return a
        ?: "e27 <p>none</p>"
}

@Language("HTML")
fun p38k(a: Boolean): String {
    return if (a) "e28 <p>a</p>"
    else "e29 <p>b</p>"
}

@Language("HTML")
fun p38l(a: Boolean, s: String): String {
    return if (a) "e30 <p>a</p>" + s else s + "e31 <p>b</p>"
}

@Language("HTML")
fun p38m(a: Boolean): String {
    return (if (a) "e32 <p>a</p>" else "e33 <p>b</p>")
}

@Language("HTML")
fun p38n(a: Boolean): String {
    return (if (a) "e34 <p>a</p>" else "e35 <p>b</p>").trim()
}

@Language("HTML")
fun p38o(a: Boolean, s: String): String {
    return when {
        a -> "e36 <p>a</p>"
        s.startsWith("e37 x") -> "e38 <p>x</p>"
        else -> "e39 <p>b</p>"
    }
}

@Language("HTML")
fun p38p(a: String): String = when (a) {
    "e40 k", "e41 l" -> "e42 <p>kl</p>"
    else -> "e43 <p>other</p>"
}

@Language("HTML")
fun p38q(a: Boolean): String {
    if (a) return "e44 <p>a</p>" else return "e45 <p>b</p>"
}

@Language("HTML")
fun p38r(a: Boolean): String {
    if (a) {
        return "e46 <p>a</p>"
    }
    else {
        return "e47 <p>b</p>"
    }
}

@Language("HTML")
fun p38s(a: List<String>): String {
    for (x in a) {
        if (x.isEmpty()) continue
        return "e48 <p>$x</p>"
    }
    return "e49 <p>none</p>"
}

@Language("HTML")
fun p38t(a: Boolean): String {
    while (a) {
        return "e50 <p>loop</p>"
    }
    return "e51 <p>after</p>"
}

@Language("HTML")
fun p38u(a: String?): String {
    a ?: return "e52 <p>none</p>"
    return "e53 <p>$a</p>"
}

@Language("HTML")
fun p38v(a: Boolean): String {
    val y = if (a) 1 else 2
    return "e54 <p>$y</p>"
}

@Language("HTML")
fun p38w(m: Map<String, String>, k: String): String = m[k] ?: "e55 <p>missing</p>"

@Language("HTML")
fun p38x(m: Map<String, String>, k: String): String = m.getOrDefault(k, "e56 default")

@Language("HTML")
fun p38y(a: Boolean): String = (if (a) "e57 <p>a</p>" else "e58 <p>b</p>") + "e59 <p>c</p>"

@Language("HTML")
fun p38z(a: Boolean): String = "e60 <p>" + (if (a) "e61 a" else "e62 b") + "e63 </p>"
