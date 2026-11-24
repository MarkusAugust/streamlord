import org.intellij.lang.annotations.Language

data class P34Item(val name: String, val done: Boolean)

@Language("HTML")
fun p34row(item: P34Item): String {
    val cls = if (item.done) "a01 done" else "a02 open"
    return "a03 <li class=\"$cls\">${item.name}</li>"
}

@Language("HTML")
fun p34list(items: List<P34Item>): String {
    if (items.isEmpty()) return "a04 <p>empty</p>"
    val rows = items.joinToString("a05 sep") { p34row(it) }
    return "a06 <ul>$rows</ul>"
}

@Language("HTML")
fun p34page(title: String?, body: String): String {
    val t = title ?: return "a07 <p>no title</p>"
    return """
        a08 <h1>$t</h1>
        $body
    """.trimIndent()
}

@Language("HTML")
fun p34margin(name: String): String =
    """
    |a09 <p>$name</p>
    """.trimMargin()

@Language("HTML")
fun p34margin2(name: String): String {
    return """
        |a10 <p>$name</p>
    """.trimMargin("a11 |")
}

@Language("HTML")
fun p34build(items: List<String>): String {
    val sb = StringBuilder()
    sb.append("a12 <ul>")
    for (i in items) sb.append("a13 <li>$i</li>")
    sb.append("a14 </ul>")
    return sb.toString()
}

@Language("HTML")
fun p34buildString(items: List<String>): String = buildString {
    append("a15 <ul>")
    items.forEach { append("a16 <li>$it</li>") }
    append("a17 </ul>")
}

@Language("HTML")
fun p34status(code: Int): String {
    when (code) {
        200 -> return "a18 <b>ok</b>"
        404 -> return "a19 <b>missing</b>"
    }
    return "a20 <b>other</b>"
}

@Language("HTML")
fun p34nested(a: Boolean, b: Boolean): String {
    if (a) {
        if (b) {
            return "a21 <i>ab</i>"
        } else {
            return "a22 <i>a</i>"
        }
    } else if (b) {
        return "a23 <i>b</i>"
    }
    return "a24 <i>none</i>"
}

@Language("HTML")
fun p34nestedExpr(a: Boolean, b: Boolean): String =
    if (a) {
        if (b) "a25 <i>ab</i>" else "a26 <i>a</i>"
    } else {
        "a27 <i>none</i>"
    }

@Language("HTML")
fun p34elvisChain(a: String?, b: String?): String = a ?: b ?: "a28 <em>none</em>"

@Language("HTML")
fun p34elvisReturn(a: String?): String {
    return a?.let { "a29 <em>$it</em>" } ?: "a30 <em>none</em>"
}

@Language("HTML")
fun p34try(s: String): String {
    return try {
        "a31 <b>${s.toInt()}</b>"
    } catch (e: Exception) {
        "a32 <b>bad</b>"
    }
}

@Language("HTML")
fun p34takeIf(s: String): String = s.takeIf { it.isNotEmpty() } ?: "a33 <p>blank</p>"

@Language("HTML")
fun p34format(s: String): String = "a34 <p>%s</p>".format(s)

@Language("HTML")
fun p34unit(s: String): String {
    println("a35 log")
    require(s.isNotEmpty()) { "a36 msg" }
    return "a37 <p>$s</p>"
}

fun p34plain(s: String): String {
    val x = "a38 <p>$s</p>"
    return x
}

@Language("HTML")
fun p34lambdaReturn(items: List<String>): String {
    items.forEach { if (it.isEmpty()) return "a39 <p>empty item</p>" }
    return items.map { "a40 x$it" }.joinToString()
}

@Language("HTML")
fun p34labelled(items: List<String>): String {
    items.forEach inner@{ if (it.isEmpty()) return@inner }
    return "a41 <p>done</p>"
}
