package interpolationtests

import org.intellij.lang.annotations.Language

/*
 * Way 2: `@Language("HTML")` from org.jetbrains:annotations.
 *
 * Three places it can stand, at the top; then the same fourteen forms as in
 * A_LanguageComment.kt, each on an annotated value, each with the same
 * mistake: `query`, text from the request, written somewhere it does harm.
 */

/** A function of our own whose parameter is HTML: a string passed to it is injected. */
fun page(@Language("HTML") body: String): String = "<main>$body</main>"

object LanguageAnnotation {
    // A. On a value.
    // The mistake: the applicant's own email as a whole href.
    @Language("HTML")
    val onValue = "<button class=\"lt-button\" type=\"button\">Send application</button><a class=\"lt-link\" href=\"${applicant.email}\">Mail</a>"

    // B. On a parameter: the argument is HTML.
    // The mistake: the query in the text of the argument.
    fun onParameter(query: String): String = page("<p class=\"lt-paragraph\">Hello, ${applicant.name}. You searched for $query</p>")

    // C. On the function. Whether IntelliJ injects the returned literal in
    // Kotlin is part of what this file tests.
    // The mistake: the query in an event handler, which the browser runs.
    @Language("HTML")
    fun onFunction(query: String): String = "<p class=\"lt-paragraph\" onclick=\"search('$query')\">Returned from an annotated function</p>"

    // 1. No interpolation.
    // The mistake: the query as a whole href, so javascript: runs.
    fun plain(query: String): String {
        @Language("HTML")
        val html = "<button class=\"lt-button\" type=\"button\">Send application</button><a class=\"lt-link\" href=\"$query\">Back</a>"
        return html
    }

    // 2. `$name` in the text.
    // The mistake: the query unescaped in the text.
    fun simpleInText(query: String): String {
        val name = applicant.name
        @Language("HTML")
        val html = "<p class=\"lt-paragraph\">Hello, $name. You searched for $query</p>"
        return html
    }

    // 3. `${expression}` with a property in the text.
    // The mistake: the query unescaped in a title attribute.
    fun propertyInText(query: String): String {
        @Language("HTML")
        val html = "<p class=\"lt-paragraph\" title=\"${query.trim()}\">Signed in as ${applicant.email}</p>"
        return html
    }

    // 4. `${call()}` and a conditional in the text.
    // The mistake: a conditional whose one branch is the query.
    fun callAndConditional(query: String): String {
        @Language("HTML")
        val html = "<h2 class=\"lt-heading\">The form has $count ${if (count == 1) "error" else "errors"} for ${if (query.isBlank()) "nothing" else query}</h2>"
        return html
    }

    // 5. A whole attribute value.
    // The mistake: the query as the field's value.
    fun attributeValue(query: String): String {
        @Language("HTML")
        val html = """
            <label class="lt-label" for="$fieldId">Email address</label>
            <input class="lt-input" id="$fieldId" type="email" value="$query">
        """.trimIndent()
        return html
    }

    // 6. Part of a class list.
    // The mistake: the query as a class.
    fun partOfClass(query: String): String {
        @Language("HTML")
        val html = """<button class="lt-button ${if (count > 0) "is-busy" else ""} $query" data-variant="$variant">Save</button>"""
        return html
    }

    // 7. A whole attribute from a function.
    // The mistake: the query as an unquoted value, where a space starts an attribute.
    fun wholeAttribute(query: String): String {
        @Language("HTML")
        val html = "<button ${buttonAttributes()}>Cancel</button><button class=\"lt-button\" data-variant=$query>Retry</button>"
        return html
    }

    // 8. The tag name itself.
    // The mistake: the query as a tag name.
    fun tagName(query: String): String {
        @Language("HTML")
        val html = "<$tag class=\"lt-card\"><p class=\"lt-paragraph\">Inside</p></$tag><$query>Echo</$query>"
        return html
    }

    // 9. A list built with joinToString.
    // The mistake: each error inside a JavaScript string Datastar runs.
    fun loop(): String {
        @Language("HTML")
        val html = """
            <ul class="lt-list">
              ${applicant.errors.joinToString("") { error -> """<li><a href="#$fieldId" data-on:click="alert('$error')">$error</a></li>""" }}
            </ul>
        """.trimIndent()
        return html
    }

    // 10. A literal `$` for Datastar signals.
    // The mistake: the query inside the expression, beside the signal.
    fun datastarSignal(query: String): String {
        @Language("HTML")
        val html = """<button class="lt-button" data-on:click="${'$'}open = !${'$'}open; ${'$'}last = '$query'" data-attr:aria-expanded="${'$'}open">Menu</button>"""
        return html
    }

    // 11. A raw string with trimMargin.
    // The mistake: the query as an image source.
    fun rawWithMargin(query: String): String {
        @Language("HTML")
        val html = """
            |<lt-field>
            |  <label class="lt-label" for="name">Full name</label>
            |  <input class="lt-input" id="name" value="${applicant.name}">
            |  <img src="$query" alt="">
            |</lt-field>
        """.trimMargin()
        return html
    }

    // 12. Two literals joined with `+`.
    // The mistake: the query joined into a style attribute.
    fun concatenated(query: String): String {
        @Language("HTML")
        val html = "<p class=\"lt-paragraph\">" + applicant.name + "</p>" + "<p class=\"lt-help-text\" style=\"color: " + query + "\">$count to fix</p>"
        return html
    }

    // 13. Deliberate findings next to interpolation.
    // The mistake: the query inside a script.
    fun deliberateFindings(query: String): String {
        @Language("HTML")
        val html = """
            <input class="lt-input" id="$fieldId-confirm" type="email">
            <a class="lt-link" href="#nowhere">Go to ${applicant.name}</a>
            <p class="lt-unknown-class">$count</p>
            <script>const searched = "$query"</script>
        """.trimIndent()
        return html
    }

    // 14. A Datastar signal Kotlin interpolates by mistake.
    // The mistake, besides: the query as a whole Datastar expression.
    fun interpolatedSignalByMistake(query: String): String {
        val open = "true"
        @Language("HTML")
        val html = "<div data-show=\"$open\" class=\"lt-card\">Shown</div><p data-text=\"$query\"></p>"
        return html
    }
}
