import org.intellij.lang.annotations.Language

annotation class P54Mapping(val value: String, val produces: String = "")

class P54Handler {
    @P54Mapping("n01 /a", produces = "n02 text/html")
    @Language("HTML")
    fun a(): String = "n03 <p>a</p>"

    @Language("HTML")
    @P54Mapping(value = "n04 /b")
    fun b(user: String?): String {
        val title: String
        title = if (user == null) return "n05 <p>login</p>" else "n06 Home"
        return "n07 <h1>$title</h1>" // trailing note
    }

    @Language("HTML")
    fun c(user: String?): String {
        if (user == null) return "n08 <p>login</p>" else println("n09 ok")
        return "n10 <p>$user</p>" /* note */
    }

    @Language("HTML")
    fun d(n: Int): String {
        return if (n > 0) "n11 <p>pos</p>"
        else if (n < 0) "n12 <p>neg</p>"
        else "n13 <p>zero</p>"
    }

    @Language("HTML")
    fun e(n: Int): String {
        val s = if (n > 0) {
            "n14 a"
        } else {
            return "n15 <p>e</p>"
        }
        return "n16 <p>$s</p>"
    }

    @Language("HTML")
    fun f(n: Int): String {
        return "n17 <p>" + n + "n18 </p>"
    }

    @Language("HTML")
    fun g(n: Int): String {
        return "n19 <p>a</p>" +
            "n20 b" +
            "n21 </p>"
    }

    @Language("HTML")
    fun h(n: Int): String {
        return "n22 <p>a</p>".trimIndent() + "n23 b"
    }

    fun i(n: Int): String {
        // language=HTML
        val a = "n24 <p>a</p>"
        /**
         * doc
         */
        // language=HTML
        val b = "n25 <p>b</p>"
        // language=HTML
        // language=CSS
        val c = "n26 a{}"
        // language=CSS
        // language=HTML
        val d = "n27 <p>d</p>"
        return a + b + c + d
    }

    fun j(): String {
        /* language=HTML */ val a = "n28 <p>a</p>"
        val b = "n29 b"; /* language=HTML */ val c = "n30 <p>c</p>"
        return a + b + c
    }

    fun k(): String {
        // language=HTML
        return "n31 <p>" +
            "n32 x" +
            "n33 </p>"
    }

    fun l(c: Boolean): String {
        // language=HTML
        return when {
            c -> "n34 <p>c</p>"
            else -> {
                val inner = "n35 i"
                "n36 <p>$inner</p>"
            }
        }
    }

    fun m(c: Boolean): String {
        var out = "" // missed: #347
        if (c) {
            // language=HTML
            out = "n37 <p>c</p>"
        } else {
            out = "n38 x"
        }
        return out
    }
}

object P54Templates {
    // language=HTML
    private const val ROW = "n39 <tr>%s</tr>"

    // language=HTML
    private val CELL = """
        n40 <td>%s</td>
    """.trimIndent()

    @Language("HTML")
    private val LIST: String = listOf("n41 a").joinToString("n42 ") { "n43 <li>$it</li>" }

    fun row(s: String) = ROW.format(s) + CELL + LIST
}
