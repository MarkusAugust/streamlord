package interpolationtests

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.ktor.respondDatastar
import io.ktor.server.application.ApplicationCall

/*
 * Way 3: the string goes straight to Streamlord's `patchElements`, whose
 * parameter is `@Language("HTML")`. Nothing is marked in this file: the
 * injection comes from Streamlord's own annotation.
 *
 * The same fourteen forms as in A_LanguageComment.kt, as patches, each with
 * the same mistake: `query`, text from the request, written somewhere it does
 * harm.
 */
suspend fun ApplicationCall.streamlordPatches() {
    val query: String = request.queryParameters["q"].orEmpty()
    respondDatastar {
        // 1. No interpolation.
        // The mistake: the query as a whole href, so javascript: runs.
        patchElements("<div id=\"plain\"><button class=\"lt-button\" type=\"button\">Send application</button><a class=\"lt-link\" href=\"$query\">Back</a></div>")

        // 2. `$name` in the text.
        // The mistake: the query unescaped in the text.
        val name = applicant.name
        patchElements("<p id=\"simple\" class=\"lt-paragraph\">Hello, $name. You searched for $query</p>")

        // 3. `${expression}` with a property in the text.
        // The mistake: the query unescaped in a title attribute.
        patchElements("<p id=\"property\" class=\"lt-paragraph\" title=\"${query.trim()}\">Signed in as ${applicant.email}</p>")

        // 4. `${call()}` and a conditional in the text.
        // The mistake: a conditional whose one branch is the query.
        patchElements("<h2 id=\"count\" class=\"lt-heading\">The form has $count ${if (count == 1) "error" else "errors"} for ${if (query.isBlank()) "nothing" else query}</h2>")

        // 5. A whole attribute value.
        // The mistake: the query as the field's value.
        patchElements(
            """
            <div id="field">
              <label class="lt-label" for="$fieldId">Email address</label>
              <input class="lt-input" id="$fieldId" type="email" value="$query">
            </div>
            """.trimIndent(),
        )

        // 6. Part of a class list.
        // The mistake: the query as a class.
        patchElements("""<button id="busy" class="lt-button ${if (count > 0) "is-busy" else ""} $query" data-variant="$variant">Save</button>""")

        // 7. A whole attribute from a function.
        // The mistake: the query as an unquoted value, where a space starts an attribute.
        patchElements("<div id=\"cancel\"><button ${buttonAttributes()}>Cancel</button><button class=\"lt-button\" data-variant=$query>Retry</button></div>")

        // 8. The tag name itself.
        // The mistake: the query as a tag name.
        patchElements("<$tag id=\"inside\" class=\"lt-card\"><p class=\"lt-paragraph\">Inside</p><$query>Echo</$query></$tag>")

        // 9. A list built with joinToString, appended with a selector.
        // The mistake: each error inside a JavaScript string Datastar runs.
        patchElements(
            applicant.errors.joinToString("") { error -> """<li><a href="#$fieldId" data-on:click="alert('$error')">$error</a></li>""" },
            selector = "#errors",
            mode = ElementPatchMode.APPEND,
        )

        // 10. A literal `$` for Datastar signals.
        // The mistake: the query inside the expression, beside the signal.
        patchElements("""<button id="menu" class="lt-button" data-on:click="${'$'}open = !${'$'}open; ${'$'}last = '$query'" data-attr:aria-expanded="${'$'}open">Menu</button>""")

        // 11. A raw string with trimMargin.
        // The mistake: the query as an image source.
        patchElements(
            """
            |<lt-field id="name-field">
            |  <label class="lt-label" for="name">Full name</label>
            |  <input class="lt-input" id="name" value="${applicant.name}">
            |  <img src="$query" alt="">
            |</lt-field>
            """.trimMargin(),
        )

        // 12. Two literals joined with `+`.
        // The mistake: the query joined into a style attribute.
        patchElements("<p id=\"joined\" class=\"lt-paragraph\" style=\"color: " + query + "\">" + applicant.name + "</p>")

        // 13. Deliberate findings next to interpolation.
        // The mistake: the query inside a script.
        patchElements(
            """
            <div id="findings">
              <input class="lt-input" id="$fieldId-confirm" type="email">
              <a class="lt-link" href="#nowhere">Go to ${applicant.name}</a>
              <p class="lt-unknown-class">$count</p>
              <script>const searched = "$query"</script>
            </div>
            """.trimIndent(),
        )

        // 14. A Datastar signal Kotlin interpolates by mistake.
        // The mistake, besides: the query as a whole Datastar expression.
        val open = "true"
        patchElements("<div id=\"shown\" data-show=\"$open\" class=\"lt-card\">Shown<p data-text=\"$query\"></p></div>")
    }
}
