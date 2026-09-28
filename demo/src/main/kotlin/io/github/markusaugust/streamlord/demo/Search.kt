package io.github.markusaugust.streamlord.demo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One section of one documentation page, as `:demo:buildSearchIndex` wrote it.
 *
 * A section rather than a page, so a hit can name the heading it was found under and quote
 * the line around it. That is the difference between a search and a list of links.
 */
@Serializable
public data class Section(
    val slug: String,
    val title: String,
    val heading: String,
    val anchor: String,
    val text: String,
)

/** What the reader sees: where it is, and enough of it to know whether to go there. */
public data class Hit(
    val title: String,
    val heading: String,
    val url: String,
    val excerpt: String,
)

/**
 * The whole search engine, and it is allowed to be this small.
 *
 * Nineteen pages is a hundred and fourteen sections. A token scan over that is microseconds,
 * and Lucene would be a library, an index format and a migration for a corpus that fits in
 * the memory of a phone. The point of this endpoint is that Streamlord answers it, not that
 * the ranking is clever.
 */
public class SearchIndex(private val sections: List<Section>) {
    public fun search(query: String, limit: Int = 8): List<Hit> {
        val tokens = tokenise(query)
        if (tokens.isEmpty()) return emptyList()

        return sections
            .mapNotNull { section -> score(section, tokens)?.let { it to section } }
            .sortedByDescending { (score, _) -> score }
            .take(limit)
            .map { (_, section) -> hit(section, tokens) }
    }

    /**
     * Null when the section does not hold every token.
     *
     * Requiring all of them rather than any keeps a two-word query from returning half the
     * site, which on a corpus this small is what "any" amounts to.
     */
    private fun score(section: Section, tokens: List<String>): Int? {
        val title = section.title.lowercase()
        val heading = section.heading.lowercase()
        val text = section.text.lowercase()

        var total = 0
        for (token in tokens) {
            val inTitle = title.contains(token)
            val inHeading = heading.contains(token)
            val occurrences = text.occurrencesOf(token)

            if (!inTitle && !inHeading && occurrences == 0) return null

            if (inTitle) total += 8
            if (inHeading) total += 4
            total += minOf(occurrences, 5)
        }
        return total
    }

    private fun hit(section: Section, tokens: List<String>): Hit =
        Hit(
            title = section.title,
            heading = section.heading,
            url = "/${section.slug}/" + if (section.anchor.isEmpty()) "" else "#${section.anchor}",
            excerpt = excerpt(section.text, tokens),
        )

    /** A window around the first token, cut at word boundaries so it reads as a sentence. */
    private fun excerpt(text: String, tokens: List<String>, width: Int = 150): String {
        val at = tokens.minOfOrNull { text.lowercase().indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val from = (at - width / 3).coerceAtLeast(0).let { start ->
            if (start == 0) 0 else text.indexOf(' ', start).takeIf { it in 0..(start + 20) } ?: start
        }
        val to = (from + width).coerceAtMost(text.length).let { end ->
            if (end == text.length) end else text.lastIndexOf(' ', end).takeIf { it > from } ?: end
        }

        return buildString {
            if (from > 0) append("… ")
            append(text.substring(from, to).trim())
            if (to < text.length) append(" …")
        }
    }

    private fun tokenise(query: String): List<String> =
        query.lowercase()
            .split(Regex("""[^\p{L}\p{N}$@._-]+"""))
            .filter { it.length >= 2 }
            .distinct()
            .take(6)

    private fun String.occurrencesOf(token: String): Int {
        var count = 0
        var at = indexOf(token)
        while (at >= 0) {
            count++
            at = indexOf(token, at + token.length)
        }
        return count
    }

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads the index out of the jar. It is built into the artifact rather than fetched,
         * so the service cannot start half-configured and needs no network to answer.
         */
        public fun load(): SearchIndex {
            val resource =
                SearchIndex::class.java.getResourceAsStream("/index.json")
                    ?: error("index.json is missing: run :demo:buildSearchIndex")
            return SearchIndex(json.decodeFromString<List<Section>>(resource.bufferedReader().readText()))
        }
    }
}
