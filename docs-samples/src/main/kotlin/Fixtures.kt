/*
 * The imaginary world the documentation's examples live in.
 *
 * An example that reads well needs a domain: a repository to search, a hit to render, some
 * markup rendered elsewhere. Declaring them once here does two things. The samples compile,
 * and every page tells the same story instead of inventing a new domain per page.
 *
 * Add to this only what a page genuinely needs. A fixture nobody uses is a lie about what
 * the documentation shows.
 *
 * SearchSignals is deliberately not here. The signals page declares it, in the example that
 * teaches it, and every other page's examples compile against that one. A fixture copy would mean
 * the class a reader sees and the class the examples use were two different things, which is how
 * the page came to declare a `Search` and then read a `SearchSignals`.
 */

data class Hit(
    val title: String,
    val url: String,
)

object repository {
    fun search(query: String): List<Hit> = listOf(Hit("A head on the wall", "/heads/1")).filter { query.isNotEmpty() }
}

/** A stream of numbers, for the pages that show a long-lived response. */
val ticks: kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flowOf(1, 2, 3)

/** Markup built somewhere else, for the pages that are not about building markup. */
fun renderResults(hits: List<Hit>): String = hits.joinToString("") { "<li>${it.title}</li>" }

/**
 * A stand-in for whichever template engine the reader uses. The templates page is about handing
 * Streamlord a string, not about any one engine, so the fixture is the smallest thing that has
 * the shape every engine has: a name, some values, a string out.
 */
object pebble {
    fun render(
        template: String,
        values: Map<String, Any>,
    ): String = "<li>$template ${values.size}</li>"
}

/** A response body, for the pages that show one being read back into events. */
val wire: String =
    "event: datastar-patch-elements\n" +
        "data: selector #feed\n" +
        "data: mode append\n" +
        "data: elements <li>A new head</li>\n\n" +
        "event: datastar-patch-signals\n" +
        "data: signals {\"heads\":13}\n\n"

/** A markup string and a Kotlin file, for the page that shows the analysis being called directly. */
val html: String = """<div id="a" data-text="${'$'}count"></div>"""
val source: String = """fun row() = div { dataText(signal("count")) }"""
