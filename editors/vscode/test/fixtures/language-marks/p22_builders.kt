import org.intellij.lang.annotations.Language

@Language("HTML")
fun p22a(items: List<String>): String = buildString {
    append("A1 <ul>")
    for (i in items) append("A2 <li>$i</li>")
    append("A3 </ul>")
}

@Language("HTML")
fun p22b(items: List<String>): String {
    val sb = StringBuilder()
    sb.append("A4 <ul>")
    items.forEach { sb.append("A5 <li>$it</li>") }
    return sb.toString()
}

@Language("HTML")
fun p22c(items: List<String>): String {
    return items.joinToString("A6 ") { "A7 <li>$it</li>" }
}

@Language("HTML")
fun p22d(name: String): String {
    val greeting = "A8 Hello"
    return "A9 <p>$greeting $name</p>"
}

@Language("HTML")
fun p22e(name: String): String {
    val s = "B1 <p>$name</p>".trimIndent()
    return s
}

@Language("HTML")
fun p22f(name: String): String {
    val s = """
        B2 <p>$name</p>
    """.trimIndent()
    return s
}

@Language("HTML")
fun p22g(name: String): String {
    val s = "B3 <p>" + name + "B4 </p>"
    return s
}

@Language("HTML")
fun p22h(name: String): String {
    var s = "B5 <p>"
    s += name
    return s
}

@Language("HTML")
fun p22i(name: String): String {
    val s: String = "B6 <p>$name</p>"
    return s
}

@Language("HTML")
fun p22j(name: String): String {
    val s = name.ifEmpty { "B7 <i>none</i>" }
    return s
}

@Language("HTML")
fun p22k(name: String?): String {
    val s = name ?: "B8 <i>none</i>"
    return s
}

@Language("HTML")
fun p22l(name: String): String {
    val s = "B9 x"
    val t = "C1 <p>$s</p>"
    return t
}

@Language("HTML")
fun p22m(name: String): String {
    val s = "C2 x"
    return s.uppercase()
}

@Language("HTML")
fun p22n(name: String): String {
    val s = "C3 x"
    return "$s"
}

@Language("HTML")
fun p22o(name: String): String {
    val s = "C4 <p>"
    run {
        return s
    }
}

@Language("HTML")
fun p22p(name: String): String {
    val s = "C5 <p>"
    fun inner(): String {
        return s
    }
    return inner()
}

@Language("HTML")
fun p22q(name: String) = run {
    val s = "C6 <p>"
    s
}

@Language("HTML")
fun p22r(name: String): String {
    val s = "C7 <p>"
    return (s)
}

@Language("HTML")
fun p22s(name: String): String {
    val s = "C8 <p>"
    return if (name.isEmpty()) s else s + name
}

@Language("HTML")
fun p22t(name: String): String {
    val s = "C9 <p>"
    return when { name.isEmpty() -> s else -> "D1 x" }
}

@Language("HTML")
fun p22u(name: String): String {
    val (a, b) = "D2 x" to "D3 y"
    return a
}

@Language("HTML")
fun p22v(name: String): String {
    val s by lazy { "D4 <p>" }
    return s
}

@Language("HTML")
fun p22w(name: String): String {
    lateinit var s: String
    s = "D5 <p>"
    return s
}

@Language("HTML")
fun p22x(name: String): String {
    val s = "D6 <p>" +
        "D7 </p>"
    return s
}

@Language("HTML")
fun p22y(name: String): String {
    val s = name + "D8 <p>"
    return s
}

@Language("HTML")
fun p22z(name: String): String {
    val s = ("D9 <p>")
    return s
}
