package io.github.markusaugust.streamlord.demo

import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.ul

/**
 * The results, in Fristil's classes.
 *
 * The service writes markup for a design system it does not ship and cannot see: the page
 * already carries `fristil.css`, so a `fs-list` arriving over the wire is styled the instant
 * it lands. That is the whole argument for patching elements rather than sending JSON — the
 * server owns the markup, and there is no second model of the page to keep in step.
 */
public fun TagConsumer<*>.results(
    hits: List<Hit>,
    query: String,
) {
    if (query.isBlank()) {
        p("fs-paragraph") {
            attributes["data-size"] = "small"
            +"Type to search the documentation."
        }
        return
    }

    if (hits.isEmpty()) {
        p("fs-paragraph") {
            attributes["data-size"] = "small"
            +"Nothing matches “$query”."
        }
        return
    }

    ul("fs-list hits") {
        attributes["data-variant"] = "plain"
        hits.forEach { hit ->
            li {
                a(classes = "fs-link hits__where", href = hit.url) {
                    +hit.title
                    if (hit.heading.isNotEmpty()) {
                        span("hits__heading") { +" → ${hit.heading}" }
                    }
                }
                p("fs-paragraph hits__excerpt") {
                    attributes["data-size"] = "small"
                    +hit.excerpt
                }
            }
        }
    }
}

/**
 * The frames, as they went over the wire.
 *
 * Datastar documents no way to read the SSE frames from the client — the actions reference
 * says so outright, and the one tool that does is a Pro feature. So the server shows them,
 * which is the better answer anyway: it has the encoder, and these strings come out of it.
 */
public fun TagConsumer<*>.frames(encoded: List<String>) {
    encoded.forEach { frame ->
        pre("wire__frame") { +frame }
    }
}
