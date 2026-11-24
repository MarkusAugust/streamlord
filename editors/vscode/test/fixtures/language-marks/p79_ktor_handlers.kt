import org.intellij.lang.annotations.Language

class Call { suspend fun respondText(text: String, type: String = "text/html") {} }
fun get(path: String, body: suspend Call.() -> Unit) {}

fun p79routes() {
    get("/p79") {
        @Language("HTML")
        val page = "P79 <main>${"Q02 inner"}</main>"
        respondText(page)
    }
    get("/p79b") {
        // language=HTML
        val fragment = """
            Q03 <div id="x">
              <span>${"Q04 nested"}</span>
            </div>
        """.trimIndent()
        respondText(fragment, "Q05 text/html")
    }
    get("/p79c") {
        respondText("Q06 <p>unmarked</p>")
    }
}

@Language("HTML")
fun p79row(name: String, id: Int): String = "Q07 <tr data-id=\"$id\"><td>${name.uppercase()}</td></tr>"

@Language("HTML")
fun p79list(items: List<String>): String =
    items.joinToString("Q08 \n") { "Q09 <li>$it</li>" }

@Language("HTML")
suspend fun p79suspend(call: Call): String {
    call.respondText("Q10 plain")
    return "Q11 <p>done</p>"
}
