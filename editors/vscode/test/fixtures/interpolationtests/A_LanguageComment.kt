package interpolationtests

/*
 * Way 1: a `// language=HTML` comment right above the string.
 *
 * Every function returns HTML, and each shows one form of interpolation.
 * What to try in each: completion of `lt-` classes inside `class="…"`, the
 * findings of the check (underlines), and the quick fixes on them, which must
 * leave every `$name` and `${…}` as it is.
 */
object LanguageComment {
    // 1. No interpolation: the baseline everything else is compared with.
    fun plain(): String {
        // language=HTML
        return "<button class=\"lt-button\" type=\"button\">Send application</button>"
    }

    // 2. `$name` in the text.
    fun simpleInText(): String {
        val name = applicant.name
        // language=HTML
        return "<p class=\"lt-paragraph\">Hello, $name</p>"
    }

    // 3. `${expression}` with a property in the text.
    fun propertyInText(): String {
        // language=HTML
        return "<p class=\"lt-paragraph\">Signed in as ${applicant.email}</p>"
    }

    // 4. `${call()}` and a conditional in the text.
    fun callAndConditional(): String {
        // language=HTML
        return "<h2 class=\"lt-heading\">The form has $count ${if (count == 1) "error" else "errors"}</h2>"
    }

    // 5. A whole attribute value: `id="$fieldId"`, wired to its label.
    fun attributeValue(): String {
        // language=HTML
        return """
            <label class="lt-label" for="$fieldId">Email address</label>
            <input class="lt-input" id="$fieldId" type="email">
        """.trimIndent()
    }

    // 6. Part of a class list, and part of a class name.
    fun partOfClass(): String {
        // language=HTML
        return """<button class="lt-button ${if (count > 0) "is-busy" else ""}" data-variant="$variant">Save</button>"""
    }

    // 7. A whole attribute, or several, from a function.
    fun wholeAttribute(): String {
        // language=HTML
        return "<button ${buttonAttributes()}>Cancel</button>"
    }

    // 8. The tag name itself.
    fun tagName(): String {
        // language=HTML
        return "<$tag class=\"lt-card\"><p class=\"lt-paragraph\">Inside</p></$tag>"
    }

    // 9. A list built with joinToString: a string inside the string.
    fun loop(): String {
        // language=HTML
        return """
            <ul class="lt-list">
              ${applicant.errors.joinToString("") { error -> """<li><a href="#$fieldId">$error</a></li>""" }}
            </ul>
        """.trimIndent()
    }

    // 10. A literal `$`, as Datastar signals need: `${'$'}open`.
    fun datastarSignal(): String {
        // language=HTML
        return """<button class="lt-button" data-on:click="${'$'}open = !${'$'}open" data-attr:aria-expanded="${'$'}open">Menu</button>"""
    }

    // 11. A raw string with trimMargin.
    fun rawWithMargin(): String {
        // language=HTML
        return """
            |<lt-field>
            |  <label class="lt-label" for="name">Full name</label>
            |  <input class="lt-input" id="name" value="${applicant.name}">
            |</lt-field>
        """.trimMargin()
    }

    // 12. Two literals joined with `+`, each with interpolation.
    fun concatenated(): String {
        // language=HTML
        return "<p class=\"lt-paragraph\">" + applicant.name + "</p>" + "<p class=\"lt-help-text\">$count to fix</p>"
    }

    // 13. Deliberate findings: a field with no label, a link to an id that
    // does not exist, and an unknown class, next to interpolation.
    fun deliberateFindings(): String {
        // language=HTML
        return """
            <input class="lt-input" id="$fieldId-confirm" type="email">
            <a class="lt-link" href="#nowhere">Go to ${applicant.name}</a>
            <p class="lt-unknown-class">$count</p>
        """.trimIndent()
    }

    // 14. A Datastar signal with a `$` Kotlin interpolates by mistake:
    // `$open` here is the Kotlin value, not the signal (Streamlord's own
    // plugin flags this; LO-TEK's must just not make it worse).
    fun interpolatedSignalByMistake(): String {
        val open = "true"
        // language=HTML
        return "<div data-show=\"$open\" class=\"lt-card\">Shown</div>"
    }
}
