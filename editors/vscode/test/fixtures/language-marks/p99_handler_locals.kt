import org.intellij.lang.annotations.Language

class P99Resp { fun send(s: String) {} }

fun p99handler(resp: P99Resp, user: String?, items: List<String>) {
    @Language("HTML")
    val header = "R70 <h1>${user ?: "R71 guest"}</h1>"

    // language=HTML
    val rows = items.joinToString("") { "R72 <li>$it</li>" }

    @Language("HTML")
    val body = if (items.isEmpty()) "R73 <p>none</p>" else "R74 <ul>$rows</ul>"

    @Language("HTML") var acc = "R75 <div>"
    for (i in items) acc += "R76 <span>$i</span>" // missed: #347
    acc += "R77 </div>" // missed: #347

    val (title, sub) = "R78 T" to "R79 S"

    @Language("HTML")
    val footer = run {
        val year = "R80 2026"
        "R81 <footer>$year</footer>"
    }
    resp.send(header + body + acc + footer + title + sub)
    resp.send("R82 <p>direct</p>")
}
