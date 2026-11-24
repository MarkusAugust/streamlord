import org.intellij.lang.annotations.Language

data class P86Page(
    @Language("HTML") val body: String = "Q68 <body></body>",
    val title: String = "Q69 Untitled",
    @param:Language("HTML") val head: String = "Q70 <head></head>",
)

class P86Component(@Language("HTML") var html: String) {
    @Language("HTML")
    var cached: String = "Q71 <span></span>"

    fun reset() {
        html = "Q72 <i>reset</i>" // missed: #347
        cached = "Q73 <i>cached</i>" // missed: #347
    }
}

fun p86use() {
    val p = P86Page(body = "Q74 <main></main>", title = "Q75 t") // missed: #347
    val q = P86Page("Q76 <div></div>", "Q77 t2", "Q78 <meta>") // missed: #347
    val c = P86Component("Q79 <p></p>") // missed: #347
    val d = p.copy(body = "Q80 <section></section>") // missed: #347
}
