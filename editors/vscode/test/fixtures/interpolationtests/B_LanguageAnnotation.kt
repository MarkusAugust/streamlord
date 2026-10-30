package interpolationtests

import org.intellij.lang.annotations.Language

/*
 * Way 2: `@Language("HTML")` from org.jetbrains:annotations.
 *
 * Three places it can stand, at the top; then the same fourteen forms as in
 * A_LanguageComment.kt, each on an annotated value.
 */

/** A function of our own whose parameter is HTML: a string passed to it is injected. */
fun page(@Language("HTML") body: String): String = "<main>$body</main>"

object LanguageAnnotation {
    // A. On a value.
    @Language("HTML")
    val onValue = "<button class=\"lt-button\" type=\"button\">Send application</button>"

    // B. On a parameter: the argument is HTML.
    fun onParameter(): String = page("<p class=\"lt-paragraph\">Hello, ${applicant.name}</p>")

    // C. On the function. Whether IntelliJ injects the returned literal in
    // Kotlin is part of what this file tests.
    @Language("HTML")
    fun onFunction(): String = "<p class=\"lt-paragraph\">Returned from an annotated function</p>"

    // 1. No interpolation.
    fun plain(): String {
        @Language("HTML")
        val html = "<button class=\"lt-button\" type=\"button\">Send application</button>"
        return html
    }

    // 2. `$name` in the text.
    fun simpleInText(): String {
        val name = applicant.name
        @Language("HTML")
        val html = "<p class=\"lt-paragraph\">Hello, $name</p>"
        return html
    }

    // 3. `${expression}` with a property in the text.
    fun propertyInText(): String {
        @Language("HTML")
        val html = "<p class=\"lt-paragraph\">Signed in as ${applicant.email}</p>"
        return html
    }

    // 4. `${call()}` and a conditional in the text.
    fun callAndConditional(): String {
        @Language("HTML")
        val html = "<h2 class=\"lt-heading\">The form has $count ${if (count == 1) "error" else "errors"}</h2>"
        return html
    }

    // 5. A whole attribute value.
    fun attributeValue(): String {
        @Language("HTML")
        val html = """
            <label class="lt-label" for="$fieldId">Email address</label>
            <input class="lt-input" id="$fieldId" type="email">
        """.trimIndent()
        return html
    }

    // 6. Part of a class list.
    fun partOfClass(): String {
        @Language("HTML")
        val html = """<button class="lt-button ${if (count > 0) "is-busy" else ""}" data-variant="$variant">Save</button>"""
        return html
    }

    // 7. A whole attribute from a function.
    fun wholeAttribute(): String {
        @Language("HTML")
        val html = "<button ${buttonAttributes()}>Cancel</button>"
        return html
    }

    // 8. The tag name itself.
    fun tagName(): String {
        @Language("HTML")
        val html = "<$tag class=\"lt-card\"><p class=\"lt-paragraph\">Inside</p></$tag>"
        return html
    }

    // 9. A list built with joinToString.
    fun loop(): String {
        @Language("HTML")
        val html = """
            <ul class="lt-list">
              ${applicant.errors.joinToString("") { error -> """<li><a href="#$fieldId">$error</a></li>""" }}
            </ul>
        """.trimIndent()
        return html
    }

    // 10. A literal `$` for Datastar signals.
    fun datastarSignal(): String {
        @Language("HTML")
        val html = """<button class="lt-button" data-on:click="${'$'}open = !${'$'}open" data-attr:aria-expanded="${'$'}open">Menu</button>"""
        return html
    }

    // 11. A raw string with trimMargin.
    fun rawWithMargin(): String {
        @Language("HTML")
        val html = """
            |<lt-field>
            |  <label class="lt-label" for="name">Full name</label>
            |  <input class="lt-input" id="name" value="${applicant.name}">
            |</lt-field>
        """.trimMargin()
        return html
    }

    // 12. Two literals joined with `+`.
    fun concatenated(): String {
        @Language("HTML")
        val html = "<p class=\"lt-paragraph\">" + applicant.name + "</p>" + "<p class=\"lt-help-text\">$count to fix</p>"
        return html
    }

    // 13. Deliberate findings next to interpolation.
    fun deliberateFindings(): String {
        @Language("HTML")
        val html = """
            <input class="lt-input" id="$fieldId-confirm" type="email">
            <a class="lt-link" href="#nowhere">Go to ${applicant.name}</a>
            <p class="lt-unknown-class">$count</p>
        """.trimIndent()
        return html
    }

    // 14. A Datastar signal Kotlin interpolates by mistake.
    fun interpolatedSignalByMistake(): String {
        val open = "true"
        @Language("HTML")
        val html = "<div data-show=\"$open\" class=\"lt-card\">Shown</div>"
        return html
    }
}
