package interpolationtests

/*
 * Way 1: a `// language=HTML` comment right above the string.
 *
 * Every function returns HTML, and each shows one form of interpolation.
 * What to try in each: completion of `lt-` classes inside `class="…"`, the
 * findings of the check (underlines), and the quick fixes on them, which must
 * leave every `$name` and `${…}` as it is.
 *
 * Every form also writes `query`, text from the request, somewhere it does
 * harm, and the editor must say so. The comment above each says where.
 */
object LanguageComment {
    // 1. No interpolation: the baseline everything else is compared with.
    // The mistake: the query as a whole href, so javascript: runs.
    fun plain(query: String): String {
        // language=HTML
        return "<button class=\"lt-button\" type=\"button\">Send application</button><a class=\"lt-link\" href=\"$query\">Back</a>"
    }

    // 2. `$name` in the text.
    // The mistake: the query unescaped in the text.
    fun simpleInText(query: String): String {
        val name = applicant.name
        // language=HTML
        return "<p class=\"lt-paragraph\">Hello, $name. You searched for $query</p>"
    }

    // 3. `${expression}` with a property in the text.
    // The mistake: the query unescaped in a title attribute.
    fun propertyInText(query: String): String {
        // language=HTML
        return "<p class=\"lt-paragraph\" title=\"${query.trim()}\">Signed in as ${applicant.email}</p>"
    }

    // 4. `${call()}` and a conditional in the text.
    // The mistake: a conditional whose one branch is the query.
    fun callAndConditional(query: String): String {
        // language=HTML
        return "<h2 class=\"lt-heading\">The form has $count ${if (count == 1) "error" else "errors"} for ${if (query.isBlank()) "nothing" else query}</h2>"
    }

    // 5. A whole attribute value: `id="$fieldId"`, wired to its label.
    // The mistake: the query as the field's value.
    fun attributeValue(query: String): String {
        // language=HTML
        return """
            <label class="lt-label" for="$fieldId">Email address</label>
            <input class="lt-input" id="$fieldId" type="email" value="$query">
        """.trimIndent()
    }

    // 6. Part of a class list, and part of a class name.
    // The mistake: the query as a class.
    fun partOfClass(query: String): String {
        // language=HTML
        return """<button class="lt-button ${if (count > 0) "is-busy" else ""} $query" data-variant="$variant">Save</button>"""
    }

    // 7. A whole attribute, or several, from a function.
    // The mistake: the query as an unquoted value, where a space starts an attribute.
    fun wholeAttribute(query: String): String {
        // language=HTML
        return "<button ${buttonAttributes()}>Cancel</button><button class=\"lt-button\" data-variant=$query>Retry</button>"
    }

    // 8. The tag name itself.
    // The mistake: the query as a tag name.
    fun tagName(query: String): String {
        // language=HTML
        return "<$tag class=\"lt-card\"><p class=\"lt-paragraph\">Inside</p></$tag><$query>Echo</$query>"
    }

    // 9. A list built with joinToString: a string inside the string.
    // The mistake: each error inside a JavaScript string Datastar runs.
    fun loop(): String {
        // language=HTML
        return """
            <ul class="lt-list">
              ${applicant.errors.joinToString("") { error -> """<li><a href="#$fieldId" data-on:click="alert('$error')">$error</a></li>""" }}
            </ul>
        """.trimIndent()
    }

    // 10. A literal `$`, as Datastar signals need: `${'$'}open`.
    // The mistake: the query inside the expression, beside the signal.
    fun datastarSignal(query: String): String {
        // language=HTML
        return """<button class="lt-button" data-on:click="${'$'}open = !${'$'}open; ${'$'}last = '$query'" data-attr:aria-expanded="${'$'}open">Menu</button>"""
    }

    // 11. A raw string with trimMargin.
    // The mistake: the query as an image source.
    fun rawWithMargin(query: String): String {
        // language=HTML
        return """
            |<lt-field>
            |  <label class="lt-label" for="name">Full name</label>
            |  <input class="lt-input" id="name" value="${applicant.name}">
            |  <img src="$query" alt="">
            |</lt-field>
        """.trimMargin()
    }

    // 12. Two literals joined with `+`, each with interpolation.
    // The mistake: the query joined into a style attribute.
    fun concatenated(query: String): String {
        // language=HTML
        return "<p class=\"lt-paragraph\">" + applicant.name + "</p>" + "<p class=\"lt-help-text\" style=\"color: " + query + "\">$count to fix</p>"
    }

    // 13. Deliberate findings: a field with no label, a link to an id that
    // does not exist, and an unknown class, next to interpolation.
    // The mistake: the query inside a script.
    fun deliberateFindings(query: String): String {
        // language=HTML
        return """
            <input class="lt-input" id="$fieldId-confirm" type="email">
            <a class="lt-link" href="#nowhere">Go to ${applicant.name}</a>
            <p class="lt-unknown-class">$count</p>
            <script>const searched = "$query"</script>
        """.trimIndent()
    }

    // 14. A Datastar signal with a `$` Kotlin interpolates by mistake:
    // `$open` here is the Kotlin value, not the signal (Streamlord's own
    // plugin flags this; LO-TEK's must just not make it worse).
    // The mistake, besides: the query as a whole Datastar expression.
    fun interpolatedSignalByMistake(query: String): String {
        val open = "true"
        // language=HTML
        return "<div data-show=\"$open\" class=\"lt-card\">Shown</div><p data-text=\"$query\"></p>"
    }
}
