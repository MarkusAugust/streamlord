// review309/cases3/v_tag_when_oneline
object Case67 {
fun f(ok: Boolean, name: String): String {
    val tag = if (ok) "<b>" else "<i>"
    val tag2 = when (ok) { true -> "<b>" else -> "<i>" }
    return "<p>$tag $tag2</p>"
}
}

// review309/cases3/v_when_brace
object Case68 {
fun outer(n: String) {
    fun label(n: Int) = when
    {
        n > 0 -> "<b>$n</b>"
        else -> ""
    }
    println("<p>$n</p>")
}
fun outer2(n: String) {
    fun label(n: Int) = when (n)
    {
        1 -> "<b>$n</b>"
        else -> ""
    }
    println("<p>$n</p>")
}
fun outer3(n: String) {
    fun label(n: Int): String = run
    {
        "<b>$n</b>"
    }
    println("<p>$n</p>")
}
fun outer4(n: String) {
    fun label(n: Int) = object
        : Any() {
        override fun toString() = "<b>$n</b>"
    }
    println("<p>$n</p>")
}
}

