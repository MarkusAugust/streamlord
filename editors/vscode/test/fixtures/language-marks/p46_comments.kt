import org.intellij.lang.annotations.Language

fun p46a(c: Boolean): String {
    // language=HTML
    val a = "d01 <p>a</p>"
    // language=HTML
    return "d02 <p>b</p>"
}

fun p46b(c: Boolean): String {
    // language=HTML
    return if (c) "d03 <p>a</p>" else "d04 <p>b</p>"
}

fun p46c(c: Boolean): String {
    // language=HTML
    return buildString {
        append("d05 <p>a</p>")
        append("d06 <p>b</p>")
    }
}

fun p46d(): String {
    var html = "d07 x" // missed: #347
    // language=HTML
    html = "d08 <p>a</p>"
    // language=HTML
    html += "d09 <p>b</p>"
    return html
}

fun p46e(sb: StringBuilder) {
    // language=HTML
    sb.append("d10 <p>a</p>")
    sb.append("d11 <p>b</p>")
    // language=HTML
    println("d12 <p>c</p>")
}

fun p46f() {
    // language=HTML
    val x = listOf("d13 <p>a</p>", "d14 <p>b</p>")
    // language=HTML
    val y = "d15 <p>a</p>" + "d16 <p>b</p>"
    // language=HTML
    val z = """
        d17 <p>a</p>
    """.trimIndent()
    println(x + y + z)
}

fun p46g() {
    //language=HTML
    val a = "d18 <p>a</p>"
    /* language=HTML */
    val b = "d19 <p>b</p>"
    /*language=HTML*/
    val c = "d20 <p>c</p>"
    // language=HTML prefix=<div> suffix=</div>
    val d = "d21 <p>d</p>"
    // language=HTML some note here
    val e = "d22 <p>e</p>"
    // language = HTML
    val f = "d23 <p>f</p>"
    // language=HTML.
    val g = "d24 <p>g</p>"
    // language=HTMLX
    val h = "d25 <p>h</p>"
    println(a + b + c + d + e + f + g + h)
}

fun p46h() {
    // language=HTML
    // the header row
    val a = "d26 <p>a</p>"
    // language=HTML
    /** doc */
    val b = "d27 <p>b</p>"
    // language=HTML
    @Suppress("d28 x") // missed: #347
    val c = "d29 <p>c</p>"
    // language=HTML

    val d = "d30 <p>d</p>"
    println(a + b + c + d)
}

fun p46i() {
    val a = 1 // language=HTML
    val b = "d31 <p>b</p>"
    val c = /* language=HTML */ "d32 <p>c</p>"
    val d = // language=HTML
        "d33 <p>d</p>"
    println(a.toString() + b + c + d)
}

fun p46j(f: (String, String) -> Unit, m: Map<String, String>) {
    f(/* language=HTML */ "d34 <p>a</p>", "d35 b")
    f(
        "d36 a",
        // language=HTML
        "d37 <p>b</p>",
    )
    val n = mapOf("d38 k" to /* language=HTML */ "d39 <p>v</p>")
    println(n + m)
}

// language=HTML
class P46K {
    val a = "d40 <p>a</p>"
    fun b() = "d41 <p>b</p>"
}

// language=HTML
val p46l = "d42 <p>top</p>"

// language=HTML
fun p46m(): String {
    return "d43 <p>m</p>"
}

fun p46n(c: Boolean) {
    // language=HTML
    if (c) {
        val a = "d44 <p>a</p>"
        println(a)
    }
    // language=HTML
    run {
        println("d45 <p>b</p>")
    }
    // language=HTML
    val v = run { "d46 <p>c</p>" }
    // language=HTML
    val w = listOf(1).map { "d47 <p>$it</p>" }
    println(v + w)
}

fun p46o(): String {
    val a = "d48 x" + /* language=HTML */ "d49 <p>b</p>"
    val b = "d50 x" +
        // language=HTML
        "d51 <p>b</p>"
    return a + b
}
