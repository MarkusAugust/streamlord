/*
 * The imaginary world the documentation's examples live in.
 *
 * An example that reads well needs a domain: a repository to search, a hit to render, a
 * signal class to deserialise into. Declaring them once here does two things. The samples
 * compile, and every page tells the same story instead of inventing a new domain per page.
 *
 * Add to this only what a page genuinely needs. A fixture nobody uses is a lie about what
 * the documentation shows.
 */

import kotlinx.serialization.Serializable

@Serializable
data class SearchSignals(val query: String = "")

data class Hit(val title: String, val url: String)

object repository {
    fun search(query: String): List<Hit> =
        listOf(Hit("A head on the wall", "/heads/1")).filter { query.isNotEmpty() }
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
    fun render(template: String, values: Map<String, Any>): String =
        "<li>$template ${values.size}</li>"
}

/** A markup string and a Kotlin file, for the page that shows the analysis being called directly. */
val html: String = """<div id="a" data-text="${'$'}count"></div>"""
val source: String = """fun row() = div { dataText(signal("count")) }"""
