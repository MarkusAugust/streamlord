package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.input
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
 * it lands. That is the whole argument for patching elements rather than sending JSON. The
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
 * Datastar documents no way to read the SSE frames from the client. The actions reference
 * says so outright, and the one tool that does is a Pro feature. So the server shows them,
 * which is the better answer anyway: it has the encoder, and these strings come out of it.
 */
public fun TagConsumer<*>.frames(encoded: List<String>) {
    encoded.forEach { frame ->
        pre("wire__frame") { +frame }
    }
}

/**
 * The counter's own corner of the page, rewritten on every tick.
 *
 * Thirty numbers means thirty of these, each one an `inner` patch of the same element. The
 * number is in the markup rather than bound to the signal on purpose: a reader watching this
 * is watching elements arrive, and the signals panel beside it shows the same count arriving
 * the other way. Two mechanisms, one connection, visibly in step.
 */
public fun TagConsumer<*>.counter(
    count: Int,
    running: Boolean,
    stopped: Boolean,
) {
    p("counter__number") { +"$count" }
    p("fs-paragraph counter__state") {
        attributes["data-size"] = "small"
        +when {
            stopped -> "Stopped at $count. The connection closed the moment you asked."
            running -> "Counting. One connection, open since you pressed Start."
            else -> "Finished at $count. The server closed the stream; nothing was polled."
        }
    }
}

/**
 * Fristil's error summary, written by a server that has never seen Fristil.
 *
 * The classes and the shape are the design system's: `fs-error-summary` with `role="alert"`
 * and `tabindex="-1"` so it can be focused, a title, and a list of links into the fields that
 * failed. The page already carries the stylesheet, so this arrives styled and announced.
 *
 * When nothing is wrong it says so instead of disappearing, because a summary that vanishes
 * has told the reader nothing about whether the form was checked.
 */
public fun TagConsumer<*>.summary(
    faults: List<Fault>,
    signals: MusterSignals,
) {
    if (faults.isEmpty()) {
        p("fs-paragraph muster__ok") {
            attributes["data-size"] = "small"
            +"“${signals.banner.trim()}” musters ${signals.swords.trim()} swords. Ready to ride."
        }
        return
    }

    div("fs-error-summary") {
        attributes["role"] = "alert"
        attributes["tabindex"] = "-1"
        p("fs-error-summary__title") {
            +if (faults.size == 1) "One thing is wrong:" else "${faults.size} things are wrong:"
        }
        ul("fs-list") {
            attributes["data-variant"] = "plain"
            faults.forEach { fault ->
                li {
                    // No fs-link: Fristil styles `a` inside the summary itself, in the danger
                    // pair that belongs to that background. Ours would fight it and lose.
                    a(href = "#${fault.field}") { +fault.says }
                }
            }
        }
    }
}

/** The stone, whole: the element the modes are aimed at. */
public fun TagConsumer<*>.stone(says: String) {
    p("gallery__stone") {
        attributes["id"] = "stone"
        stoneInside(says)
    }
}

/**
 * What is inside the stone.
 *
 * Written apart from [stone] because `inner` sends this and `outer` sends the element around
 * it, and a reader comparing the two should be comparing the same words.
 *
 * The input is the point. Morphing keeps it and what you typed into it; replacing does not.
 */
public fun TagConsumer<*>.stoneInside(says: String) {
    span("gallery__says") { +says }
    input(classes = "fs-input gallery__chisel") {
        attributes["id"] = "chisel"
        placeholder = "Type here, then press Outer and Replace"
    }
}

/** A course of stone laid beside or inside the wall, which is what the four insert modes deliver. */
public fun TagConsumer<*>.course(says: String) {
    p("gallery__course") { +says }
}

/** What the server sent, in the protocol's own words. */
public fun TagConsumer<*>.said(
    mode: ElementPatchMode,
    selector: String?,
    elements: String,
) {
    p("gallery__said") {
        +"mode "
        span("gallery__wire") { +mode.wire }
        +" · selector "
        span("gallery__wire") { +(selector ?: "none") }
    }
    pre("wire__frame") {
        +if (elements.isBlank()) "No elements. A remove is a selector and a mode." else elements
    }
}
