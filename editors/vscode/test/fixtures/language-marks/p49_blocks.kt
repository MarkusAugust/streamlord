import org.intellij.lang.annotations.Language

class P49Call {
    fun respondText(text: String, type: String = "g00 text/html") = text + type
    fun respondHtml(@Language("HTML") text: String) = text
}

fun p49route(path: String, body: P49Call.() -> Unit) = P49Call().body()

fun p49app() {
    p49route("g01 /") {
        // language=HTML
        respondText("g02 <h1>home</h1>")
        respondText("g03 plain")
        respondHtml("g04 <p>marked param</p>") // missed: #347
        respondHtml(text = "g05 <p>named</p>") // missed: #347
        respondHtml(if (path() == "") "g06 <p>a</p>" else "g07 <p>b</p>")
    }
    p49route("g08 /a") {
        // language=HTML
        val page = """
            g09 <main>a</main>
        """.trimIndent()
        respondText(page, "g10 text/html")
    }
    p49route("g11 /b") {
        if (true) {
            // language=HTML
            val inner = "g12 <p>inner</p>"
            respondText(inner)
        }
        val after = "g13 after"
        respondText(after)
    }
}

fun path() = "g14 p"

fun p49nested() {
    fun local(): String {
        // language=HTML
        return "g15 <p>local</p>"
    }
    @Language("HTML")
    fun localMarked(): String = "g16 <p>lm</p>"
    @Language("HTML")
    fun localBlock(): String {
        return "g17 <p>lb</p>"
    }
    val after = "g18 after"
    println(local() + localMarked() + localBlock() + after)
}

fun p49lambdaMark() {
    val f = {
        // language=HTML
        "g19 <p>lambda</p>"
    }
    val g: () -> String = {
        // language=HTML
        val s = "g20 <p>s</p>"
        s
    }
    listOf(1).forEach { n ->
        // language=HTML
        println("g21 <p>$n</p>")
        println("g22 plain")
    }
    println(f() + g())
}

fun p49whenMark(n: Int) {
    when (n) {
        // language=HTML
        1 -> println("g23 <p>1</p>")
        2 -> println("g24 two")
    }
    val v = when (n) {
        1 -> /* language=HTML */ "g25 <p>1</p>"
        else -> "g26 other"
    }
    println(v)
}

fun p49ifMark(c: Boolean) {
    val v = if (c) {
        // language=HTML
        "g27 <p>a</p>"
    } else {
        "g28 b"
    }
    // language=HTML
    val w = if (c) "g29 <p>x</p>" else "g30 <p>y</p>"
    println(v + w)
}

fun p49tryMark() {
    // language=HTML
    val v = try {
        "g31 <p>ok</p>"
    } catch (e: Exception) {
        "g32 <p>fail</p>"
    }
    println(v)
}

fun p49markThenStatements() {
    // language=HTML
    val a = "g33 <p>a</p>"; val b = "g34 b"
    // language=HTML
    val c = "g35 <p>c</p>"
    val d = "g36 d"
    println(a + b + c + d)
}

class P49Holder {
    // language=HTML
    val a = "g37 <p>a</p>"
    val b = "g38 b"

    // language=HTML
    val c: String
        get() = "g39 <p>c</p>"

    // language=HTML
    fun d() = "g40 <p>d</p>"

    // language=HTML
    val e by lazy { "g41 <p>e</p>" }
}

object P49Obj {
    init {
        // language=HTML
        val x = "g42 <p>init</p>"
        println(x)
    }
}
