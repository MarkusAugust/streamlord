import org.intellij.lang.annotations.Language

class P29View(private val name: String) {
    @Language("HTML")
    var html = ""

    init {
        html = """
            t1 <b>$name</b>
        """.trimIndent() // missed: #347
    }

    fun update() {
        html = "t2 <b>".trimIndent() + "t3 x" // missed: #347
        html = buildString {
            append("t4 x")
        }
    }

    @Language("HTML")
    fun row(i: Int): String {
        return if (i > 0) {
            """
                t5 <li>$i</li>
            """.trimIndent()
        } else {
            "t6 <li>none</li>"
        }
    }

    @Language("HTML")
    fun row2(i: Int): String {
        return when (i) {
            0 -> "t7 <b>"
            else -> """
                t8 <i>
            """.trimIndent()
        }
    }

    @Language("HTML")
    fun row3(i: Int): String {
        val inner = if (i > 0) "t9 <b>" else "u1 <i>"
        return inner.trimIndent()
    }

    @Language("HTML")
    fun row4(i: Int): String {
        return ("u2 <b>").trimIndent()
    }

    @Language("HTML")
    fun row5(i: Int): String {
        return "u3 <b>" + "u4 </b>".trimIndent()
    }

    @Language("HTML")
    fun row6(i: Int): String {
        return "u5 <b>$i</b>".trimIndent().trim()
    }

    @Language("HTML")
    fun row7(i: Int): String {
        val s = "u6 <b>$i</b>"
            .trimIndent()
        return s
    }

    @Language("HTML")
    fun row8(i: Int): String {
        val s = "u7 <b>"
        val t = "u8 x" + s
        return t
    }

    companion object {
        @Language("HTML")
        fun page(body: String): String {
            return """
                u9 <html>$body</html>
            """.trimIndent()
        }
    }
}

@Language("HTML")
val P29View.ext: String
    get() = """
        v1 <b>x</b>
    """.trimIndent()

@Language("HTML")
fun P29View.ext2(): String {
    return """
        v2 <b>x</b>
    """.trimIndent()
}
