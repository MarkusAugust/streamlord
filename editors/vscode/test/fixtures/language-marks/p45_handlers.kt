import org.intellij.lang.annotations.Language

annotation class P45Get(val path: String)

class P45Controller(private val repo: List<String>) {
    @Language("HTML")
    fun index(name: String): String {
        val greeting = "c01 Hello $name"
        return "c02 <p>$greeting</p>"
    }

    @Language("HTML")
    fun list(): String = buildString {
        append("c03 <ul>")
        for (i in repo) append("c04 <li>$i</li>")
        append("c05 </ul>")
    }

    @P45Get("c06 /x")
    @Language("HTML")
    fun mapped(): String = "c07 <p>x</p>"

    @Language("HTML")
    @P45Get("c08 /y")
    fun mapped2(): String = "c09 <p>y</p>"

    // language=HTML
    fun commented(): String = "c10 <p>x</p>"

    fun plain(): String = "c11 plain"

    @Language("HTML")
    fun early(id: String?): String {
        if (id == null) {
            return "c12 <p>none</p>"
        }
        val item = repo.find { it == id } ?: return "c13 <p>missing</p>"
        return "c14 <p>$item</p>"
    }

    @Language("HTML")
    fun withLocalFun(): String {
        fun cell(s: String): String {
            return "c15 <td>$s</td>"
        }
        return "c16 <tr>" + cell("c17 a") + "c18 </tr>"
    }

    @Language("HTML")
    fun lambdas(): String {
        val rows = repo.map { "c19 <li>$it</li>" }
        val other = repo.map { return@map "c20 <li>$it</li>" }
        return rows.joinToString("c21 ") + other
    }

    @Language("HTML")
    fun anon(): String {
        val f = fun(s: String): String { return "c22 <i>$s</i>" }
        return f("c23 x")
    }

    @Language("HTML")
    fun tried(): String {
        try {
            return "c24 <p>ok</p>"
        } catch (e: Exception) {
            return "c25 <p>fail</p>"
        } finally {
            println("c26 done")
        }
    }

    @Language("HTML")
    fun assigned(c: String?): String {
        var a: String
        a = c ?: return "c27 <p>none</p>"
        val b = c ?: return "c28 <p>none</p>"
        val d = run { if (c == null) return "c29 <p>r</p>"; "c30 x" }
        return a + b + d
    }

    companion object {
        @JvmStatic
        @Language("HTML")
        fun stat(): String = "c31 <p>s</p>"

        @Language("HTML")
        val CONST = "c32 <p>k</p>"
    }
}

object P45Views {
    @Language("HTML")
    val header: String
        get() = "c33 <header>h</header>"

    @get:Language("HTML")
    val footer: String
        get() = "c34 <footer>f</footer>"

    @Language("HTML")
    val nav = "c35 <nav>n</nav>"

    @Language("HTML")
    const val BTN = "c36 <button>b</button>"

    fun page(
        @Language("HTML") body: String = "c37 <main>default</main>",
        title: String = "c38 t",
    ): String = "c39 $title$body"

    @Language("HTML")
    val block: String
        get() {
            val x = "c40 inner"
            return "c41 <p>$x</p>"
        }

    @Language("HTML")
    val lazyOne by lazy { "c42 <p>lazy</p>" }
}

fun p45routing(route: Any, respond: (String) -> Unit) {
    route.apply {
        // language=HTML
        val html = "c43 <p>x</p>"
        respond(html)
        // language=HTML
        respond("c44 <p>y</p>")
        respond("c45 plain")
    }
}
