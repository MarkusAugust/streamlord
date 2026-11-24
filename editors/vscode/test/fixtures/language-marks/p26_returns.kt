import org.intellij.lang.annotations.Language

class P26Card(val title: String) {
    @Language("HTML")
    fun render(): String {
        if (title.isEmpty()) return "V1 <i>empty</i>"
        val items = listOf("V2 a", "V3 b")
        val rows = items.map { "V4 <li>$it</li>" }
        return "V5 <ul>" + rows.joinToString("V6 ") + "V7 </ul>"
    }

    @Language("HTML")
    fun render2(): String {
        val sb = StringBuilder("V8 <ul>")
        sb.append("V9 <li>")
        return sb.append("W1 </ul>").toString()
    }

    @Language("HTML")
    fun render3(): String {
        val head = "W2 <h1>$title</h1>"
        val body = "W3 <p>body</p>"
        return head + body
    }

    @Language("HTML")
    fun render4(): String {
        val head = "W4 <h1>"
        return "$head W5 </h1>"
    }

    @Language("HTML")
    fun render5(): String {
        return """
            W6 <div>$title</div>
        """.trimIndent()
    }

    @Language("HTML")
    fun render6(): String = """
        W7 <div>$title</div>
    """.trimMargin()

    @Language("HTML")
    fun render7(): String =
        if (title.isEmpty()) {
            "W8 <i>empty</i>"
        } else {
            "W9 <b>$title</b>"
        }

    @Language("HTML")
    fun render8(): String {
        try {
            return "X1 <b>x</b>"
        } catch (e: Exception) {
            return "X2 <i>err</i>"
        }
    }

    @Language("HTML")
    fun render9(): String {
        for (i in 0..1) {
            if (i == 1) return "X3 <b>x</b>"
        }
        return "X4 <b>y</b>"
    }

    @Language("HTML")
    fun renderA(): String {
        val f = fun(): String { return "X5 x" }
        val g = { "X6 x" }
        return f() + g()
    }

    @Language("HTML")
    fun renderB(): String {
        val s = "X7 x"
        val t = s
        return t
    }

    @Language("HTML")
    fun renderC(title: String): String {
        return title
    }

    @Language("HTML")
    fun renderD(): String {
        val title = "X8 shadow <b>"
        return title
    }

    @Language("HTML")
    fun renderE(): String {
        return this.title
    }

    @Language("HTML")
    fun renderF(): String {
        val s = "X9 <b>"
        return s ?: "Y1 x"
    }

    @Language("HTML")
    fun renderG(): String {
        val s = "Y2 <b>"
        return s + "Y3 </b>" + s
    }

    @Language("HTML")
    fun renderH(): String {
        val s = "Y4 <b>"
        return s.trim()
    }

    @Language("HTML")
    fun renderI(): String {
        val s = "Y5 <b>".trim()
        return s
    }

    @Language("HTML")
    fun renderJ(): String {
        val s = "Y6 <b>" + "Y7 </b>"
        return s
    }

    @Language("HTML")
    fun renderK(): String {
        val s =
            "Y8 <b>"
        return s
    }

    @Language("HTML")
    fun renderL(): String {
        val s = "Y9 <b>" // trailing comment
        return s // and here
    }

    @Language("HTML")
    fun renderM(): String {
        val s = "Z1 a"; val t = "Z2 b"
        return t
    }

    @Language("HTML")
    fun renderN(): String {
        val s = "Z3 <b>"
        return s
            .trim()
    }

    @Language("HTML")
    fun renderO(): String {
        val s = "Z4 <b>"
        return s +
            "Z5 </b>"
    }

    @Language("HTML")
    fun renderP(c: Boolean): String {
        val s = "Z6 <b>"
        return when (c) {
            true -> s
            else -> "Z7 x"
        }
    }

    @Language("HTML")
    fun renderQ(): String {
        val s = "Z8 <b>"
        val r: String
        r = s
        return r
    }

    @Language("HTML")
    fun renderR(): String {
        val s = P26Card("Z9 t").title
        return s
    }
}

object P26Obj {
    private const val PREFIX = "a1 prefix" // missed: #347

    @Language("HTML")
    fun r(): String {
        return PREFIX
    }

    @Language("HTML")
    fun r2(): String {
        val PREFIX = "a2 <b>"
        return PREFIX
    }
}

val p26top = "a3 x" // missed: #347

@Language("HTML")
fun p26r(): String {
    return p26top
}

@Language("HTML")
fun p26s(): String {
    val p26top = "a4 <b>"
    return p26top
}

@Language("HTML")
fun p26t(n: Int): String {
    val s = "a5 <b>"
    return if (n > 0) {
        s
    } else {
        "a6 <i>"
    }
}

@Language("HTML")
fun p26u(n: Int): String {
    val list = listOf(1)
    list.forEach {
        val s = "a7 x"
        return s
    }
    return ""
}
