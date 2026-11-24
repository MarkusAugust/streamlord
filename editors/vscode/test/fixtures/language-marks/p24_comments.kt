fun p24a(name: String): String {
    // language=HTML
    val s = "J1 <p>$name</p>"
    val t = "J2 not"
    return s + t
}

fun p24b(name: String): String = buildString {
    // language=HTML
    append("J3 <p>$name</p>")
    append("J4 not")
}

fun p24c(name: String): String {
    val sb = StringBuilder()
    // language=HTML
    sb.append("J5 <p>")
    sb.append("J6 not")
    return sb.toString()
}

fun p24d(name: String): String {
    // language=HTML
    return "J7 <p>$name</p>"
}

fun p24e(name: String): String {
    return listOf(
        // language=HTML
        "J8 <p>$name</p>",
        "J9 not",
    ).joinToString()
}

fun p24f(name: String): String {
    // language=HTML
    val s = """
        K1 <p>$name</p>
    """.trimIndent()
    return s
}

fun p24g(name: String): String {
    val s = /* language=HTML */ "K2 <p>$name</p>"
    return s
}

fun p24h(name: String): String {
    // language=HTML
    val s = if (name.isEmpty()) "K3 <i>x</i>" else "K4 <b>x</b>"
    return s
}

fun p24i(name: String): String {
    // language=HTML
    val s = "K5 <p>" + name + "K6 </p>"
    return s
}

fun p24j(name: String): String {
    // language=HTML
    val s = name.ifEmpty { "K7 <i>x</i>" }
    return s
}

fun p24k(name: String): String {
    // language=HTML
    val s = String.format("K8 <p>%s</p>", name)
    return s
}

class P24L {
    // language=HTML
    val a = "K9 <p>"
    val b = "L1 not"

    // language=HTML
    fun f() = "L2 <p>"

    fun g(): String {
        // language=HTML
        var x = "L3 <p>"
        x = "L4 maybe"
        return x
    }
}

fun p24m(): String {
    // language=HTML
    val a = "L5 <p>"; val b = "L6 not"
    return a + b
}

fun p24n(): String {
    // language=JSON
    val a = "L7 {}"
    val b = "L8 not"
    return a + b
}

fun p24o(): String {
    // language=HTML
    p24take("L9 <p>", "M1 x")
    return ""
}

fun p24take(a: String, b: String) = a + b

fun p24p(c: Boolean): String {
    var s = "" // missed: #347
    // language=HTML
    s = "M2 <p>"
    s = "M3 not"
    return s
}
