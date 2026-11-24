import org.intellij.lang.annotations.Language

class P53Renderer(val items: List<String>) {
    @Language("HTML")
    fun table(): String = buildString {
        appendLine("m01 <table>")
        items.forEach { appendLine("m02 <tr><td>$it</td></tr>") }
        appendLine("m03 </table>")
    }

    @Language("HTML")
    fun apply1(): String = StringBuilder().apply {
        append("m04 <p>")
        append("m05 </p>")
    }.toString()

    @Language("HTML")
    fun margin(): String {
        return """
            |m06 <ul>
            |${items.joinToString("m07 ") { "m08 <li>$it</li>" }}
            |</ul>
        """.trimMargin()
    }

    @Language("HTML")
    fun joined(): String = items.joinToString(separator = "m09 ", prefix = "m10 <ul>", postfix = "m11 </ul>") { "m12 <li>$it</li>" }

    @Language("HTML")
    fun nestedTemplate(c: Boolean): String = "m13 <p>${if (c) "m14 <b>y</b>" else "m15 n"}</p>"

    @Language("HTML")
    fun withWhenBlocks(n: Int): String {
        return when (n) {
            0 -> {
                "m16 <p>0</p>"
            }
            1 -> {
                val s = "m17 one"
                "m18 <p>$s</p>"
            }
            else -> "m19 <p>n</p>"
        }
    }
}

@Language("HTML")
val P53Renderer.count: String
    get() = "m20 <p>${items.size}</p>"

val P53Renderer.plain: String
    get() = "m21 plain"

fun p53locals(c: Boolean) {
    @Language("HTML")
    val a = "m22 <p>a</p>"
    @Language("HTML")
    val b = if (c) "m23 <p>a</p>" else "m24 <p>b</p>"
    @Language("HTML")
    var d = "m25 <p>d</p>"
    d = "m26 <p>d2</p>" // missed: #347
    @Language("HTML") val e = "m27 <p>e</p>"
    val f = "m28 plain"
    println(a + b + d + e + f)
}

@Language("HTML")
val p53top = if (System.currentTimeMillis() > 0) "m29 <p>a</p>" else "m30 <p>b</p>"

@Language("HTML")
val p53topSum = "m31 <p>" +
    "m32 x" +
    "m33 </p>"

@Language("HTML")
val p53topWhen = when (1) {
    1 -> "m34 <p>1</p>"
    else -> "m35 <p>e</p>"
}

val p53after = "m36 plain"

@Language("HTML")
fun p53exprBuild(): String =
    buildString {
        // section
        append("m37 <p>")
        append("m38 </p>")
    }

fun p53commentInBuild(): String = buildString {
    // language=HTML
    append("m39 <p>a</p>")
    append("m40 b")
}

fun p53commentVal(): String {
    // language=HTML
    val s = buildString {
        append("m41 <p>a</p>")
    }
    return s
}
