package interpolationtests

import io.github.markusaugust.streamlord.core.domain.Html
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate

/*
 * Way 5: the string goes to Streamlord's `interpolate`, which escapes each
 * `%s` hole and refuses the positions escaping cannot make safe.
 *
 * Each mistake below compiles. The comment above it says what the editor must
 * say; the correct uses at the end must stay silent.
 */
object InterpolateCalls {
    // 1. A Kotlin template in the format.
    // interpolate-template, warning, on $query: Kotlin fills it in before
    // interpolate sees the markup. Fix: make it a %s hole and pass query.
    fun templateInText(query: String): Html = interpolate("<p class=\"lt-paragraph\">You searched for $query</p>")

    // 2. A template beside a hole.
    // interpolate-template, warning, on ${applicant.name}. Fix: a %s hole,
    // applicant.name passed after query, since it comes after query's hole.
    fun templateBesideHole(query: String): Html = interpolate("<p class=\"lt-paragraph\" title=\"%s\">Hello, ${applicant.name}</p>", query)

    // 3. A template in a Datastar expression.
    // interpolate-template, warning, on ${query}, no fix: as a %s hole
    // interpolate would refuse a String there.
    fun templateInExpression(query: String): Html = interpolate("<button class=\"lt-button\" data-on:click=\"@get('/search?q=${query}')\">Search</button>")

    // 4. A signal Kotlin interpolates by mistake.
    // kotlin-interpolation, warning, on $count: the whole Datastar expression.
    // Fixes: a $$ literal, or ${'$'}count.
    fun signalByMistake(): Html = interpolate("<p class=\"lt-paragraph\" data-text=\"$count\"></p>")

    // 5. More holes than values.
    // interpolate-holes, error, on interpolate: 2 holes and 1 value.
    fun tooFewValues(query: String): Html = interpolate("<p class=\"lt-paragraph\">%s searched for %s</p>", query)

    // 6. More values than holes.
    // interpolate-holes, error, on interpolate: 1 hole and 2 values.
    fun tooManyValues(query: String): Html = interpolate("<p class=\"lt-paragraph\">%s</p>", query, count)

    // 7. %%s is a per cent sign and an s, not a hole.
    // interpolate-holes, error, on interpolate: 0 holes and 1 value.
    fun percentIsNotAHole(query: String): Html = interpolate("<p class=\"lt-paragraph\">100%%s</p>", query)

    // 8. A String in a Datastar expression.
    // unsafe-interpolation, error, on %s: interpolate always throws.
    fun stringInExpression(query: String): Html = interpolate("<p class=\"lt-paragraph\" data-text=\"%s\"></p>", query)

    // 9. A value the text cannot tell, in a Datastar expression.
    // unsafe-interpolation, warning, on %s: throws unless a number or a boolean.
    fun unknownInExpression(): Html = interpolate("<p class=\"lt-paragraph\" data-text=\"%s\"></p>", applicant.name)

    // 10. A Boolean in style.
    // unsafe-interpolation, error, on %s: only a number is let in.
    fun booleanInStyle(query: String): Html {
        val hidden: Boolean = query.isBlank()
        return interpolate("<p class=\"lt-paragraph\" style=\"%s\">Hidden</p>", hidden)
    }

    // 11. A String as the tag name.
    // unsafe-interpolation, error, on both %s: only Trusted(...) is let in.
    fun stringAsTagName(query: String): Html = interpolate("<%s>Echo</%s>", query, query)

    // 12. A String in a script.
    // unsafe-interpolation, error, on %s: the browser runs it.
    fun stringInScript(query: String): Html = interpolate("<script>const searched = \"%s\"</script>", query)

    // 13. Html in a Datastar expression.
    // unsafe-interpolation, error, on the first %s: markup is not a number.
    fun htmlInExpression(query: String): Html = interpolate("<p class=\"lt-paragraph\" data-text=\"%s\"></p>", interpolate("<b>%s</b>", query))

    // 14. The format is read as HTML.
    // unknown-action, warning, on @pots, in both: <%s is a tag, not a template tag.
    fun formatIsHtml(query: String): Html = interpolate("<button class=\"lt-button\" data-on:click=\"@pots('/x')\">%s</button>", query)

    fun tagFromAHole(): Html = interpolate("<%s class=\"lt-button\" data-on:click=\"@pots('/y')\">Go</%s>", Trusted("button"), Trusted("button"))

    // 15. A template in a raw format, before a hole.
    // interpolate-template, warning, on ${applicant.errors.joinToString(", ")}.
    // Fix: a %s hole, the expression passed before query.
    fun templateInRawFormat(query: String): Html =
        interpolate(
            """
            <p class="lt-paragraph">${applicant.errors.joinToString(", ")}</p>
            <p class="lt-paragraph">%s</p>
            """.trimIndent(),
            query,
        )

    // 16. Markup a Kotlin template writes into the format.
    // interpolate-template, warning, on $inner: interpolate reads the Html as
    // format too, so a %s or %% in it is a hole or a per cent sign. Fix: make
    // it a %s hole and pass inner, which interpolate writes as it is.
    fun markupInText(query: String): Html {
        val inner: Html = interpolate("<b>%s</b>", query)
        return interpolate("<div class=\"lt-card\">$inner</div>")
    }

    // Correct uses: silent.

    // Values escaped into text and an attribute.
    fun escaped(query: String): Html = interpolate("<p class=\"lt-paragraph\" title=\"%s\">%s</p>", query, applicant.name)

    // A number in a Datastar expression and in style.
    fun numbers(): Html {
        val n: Int = count
        return interpolate("<p class=\"lt-paragraph\" data-signals=\"{errors: %s}\" style=\"width: %spx\">Errors</p>", n, n)
    }

    // A tag name from Trusted, markup built with interpolate.
    fun trusted(query: String): Html = interpolate("<%s class=\"lt-card\">%s</%s>", Trusted(tag), query, Trusted(tag))

    fun list(): Html = interpolate("<ul class=\"lt-list\">%s</ul>", Trusted(applicant.errors.joinToString("") { interpolate("<li>%s</li>", it).text }))

    // Constants in the format are the author's own.
    fun constants(query: String): Html = interpolate("<div id=\"$fieldId\" class=\"lt-card\" data-variant=\"$variant\">%s</div>", query)

    // A per cent sign, and signals in a $$ literal.
    fun percent(query: String): Html = interpolate("<p class=\"lt-paragraph\">50%% done: %s</p>", query)

    fun signals(query: String): Html = interpolate($$"<button class=\"lt-button\" data-on:click=\"$open = !$open\">%s</button>", query)
}
