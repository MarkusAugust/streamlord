package interpolationtests

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.ktor.respondDatastar
import io.ktor.server.application.ApplicationCall

/*
 * Way 3: the string goes straight to Streamlord's `patchElements`, whose
 * parameter is `@Language("HTML")`. Nothing is marked in this file: the
 * injection comes from Streamlord's own annotation.
 *
 * The same fourteen forms as in A_LanguageComment.kt, as patches.
 */
suspend fun ApplicationCall.streamlordPatches() = respondDatastar {
    // 1. No interpolation.
    patchElements("<button id=\"plain\" class=\"lt-button\" type=\"button\">Send application</button>")

    // 2. `$name` in the text.
    val name = applicant.name
    patchElements("<p id=\"simple\" class=\"lt-paragraph\">Hello, $name</p>")

    // 3. `${expression}` with a property in the text.
    patchElements("<p id=\"property\" class=\"lt-paragraph\">Signed in as ${applicant.email}</p>")

    // 4. `${call()}` and a conditional in the text.
    patchElements("<h2 id=\"count\" class=\"lt-heading\">The form has $count ${if (count == 1) "error" else "errors"}</h2>")

    // 5. A whole attribute value.
    patchElements(
        """
        <div id="field">
          <label class="lt-label" for="$fieldId">Email address</label>
          <input class="lt-input" id="$fieldId" type="email">
        </div>
        """.trimIndent(),
    )

    // 6. Part of a class list.
    patchElements("""<button id="busy" class="lt-button ${if (count > 0) "is-busy" else ""}" data-variant="$variant">Save</button>""")

    // 7. A whole attribute from a function.
    patchElements("<button id=\"cancel\" ${buttonAttributes()}>Cancel</button>")

    // 8. The tag name itself.
    patchElements("<$tag id=\"inside\" class=\"lt-card\"><p class=\"lt-paragraph\">Inside</p></$tag>")

    // 9. A list built with joinToString, appended with a selector.
    patchElements(
        applicant.errors.joinToString("") { error -> """<li><a href="#$fieldId">$error</a></li>""" },
        selector = "#errors",
        mode = ElementPatchMode.APPEND,
    )

    // 10. A literal `$` for Datastar signals.
    patchElements("""<button id="menu" class="lt-button" data-on:click="${'$'}open = !${'$'}open" data-attr:aria-expanded="${'$'}open">Menu</button>""")

    // 11. A raw string with trimMargin.
    patchElements(
        """
        |<lt-field id="name-field">
        |  <label class="lt-label" for="name">Full name</label>
        |  <input class="lt-input" id="name" value="${applicant.name}">
        |</lt-field>
        """.trimMargin(),
    )

    // 12. Two literals joined with `+`.
    patchElements("<p id=\"joined\" class=\"lt-paragraph\">" + applicant.name + "</p>")

    // 13. Deliberate findings next to interpolation.
    patchElements(
        """
        <div id="findings">
          <input class="lt-input" id="$fieldId-confirm" type="email">
          <a class="lt-link" href="#nowhere">Go to ${applicant.name}</a>
          <p class="lt-unknown-class">$count</p>
        </div>
        """.trimIndent(),
    )

    // 14. A Datastar signal Kotlin interpolates by mistake.
    val open = "true"
    patchElements("<div id=\"shown\" data-show=\"$open\" class=\"lt-card\">Shown</div>")
}
