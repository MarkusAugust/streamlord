package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.InternalStreamlordApi
import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.EventRules
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/*
 * The literal arguments of an SSE call, or of an answer that is no stream, that core refuses as it
 * runs, judged by core's own EventRules and said in its words. What the code computes is not judged.
 */

/** What the first argument of a call is, and whether it takes an event id and a retry. */
private class EventCall(
    val first: String,
    val stream: Boolean,
    val response: Boolean = false,
)

private val EVENT_CALLS: Map<String, EventCall> =
    buildMap {
        for (n in listOf("patchElements", "PatchElements")) put(n, EventCall("elements", stream = true))
        for (n in listOf(
            "respondElements",
            "ElementsResponse",
            "datastarElements",
        )) {
            put(n, EventCall("elements", stream = false, response = true))
        }
        put("removeElements", EventCall("selector", stream = true))
        for (n in listOf("patchSignals", "PatchSignals")) put(n, EventCall("signals", stream = true))
        for (n in listOf("respondSignals", "SignalsResponse", "datastarSignals")) put(n, EventCall("signals", stream = false))
        for (n in listOf("executeScript", "ExecuteScript")) put(n, EventCall("script", stream = true))
        for (n in listOf("respondScript", "ScriptResponse", "datastarScript")) put(n, EventCall("script", stream = false))
        put("redirect", EventCall("url", stream = false))
    }

/** The calls whose literal arguments core judges as it runs. */
internal val eventCallNames: Set<String> = EVENT_CALLS.keys

/** `500.milliseconds`, `(-1).seconds`, `1.5.seconds`: a number and a unit, as Kotlin's Duration extensions write it. */
private val DURATION =
    regex("""(-?)\s*\(?\s*(-?[\d_]+(\.[\d_]+)?)\s*\)?\s*\.\s*(nanoseconds|microseconds|milliseconds|seconds|minutes|hours|days)""")

private val UNITS =
    mapOf(
        "nanoseconds" to DurationUnit.NANOSECONDS,
        "microseconds" to DurationUnit.MICROSECONDS,
        "milliseconds" to DurationUnit.MILLISECONDS,
        "seconds" to DurationUnit.SECONDS,
        "minutes" to DurationUnit.MINUTES,
        "hours" to DurationUnit.HOURS,
        "days" to DurationUnit.DAYS,
    )

/** The duration [text] writes as a literal, or null where it is anything else. */
private fun durationLiteral(text: String): Duration? {
    val t = text.trim()
    if (t == "Duration.ZERO" || t == "ZERO") return Duration.ZERO
    if (t == "Duration.INFINITE" || t == "INFINITE") return Duration.INFINITE
    val m = DURATION.matchEntire(t) ?: return null
    val unit = UNITS.getValue(m.groupValues[4])
    val digits = m.groupValues[2].replace("_", "")
    val sign = if (m.groupValues[1] == "-") -1 else 1
    return if (m.groupValues[3].isEmpty()) {
        digits.toLongOrNull()?.let { (sign * it).toDuration(unit) }
    } else {
        digits.toDoubleOrNull()?.let { (sign * it).toDuration(unit) }
    }
}

/** The string literal [arg] is, where it holds no template and is closed. */
private fun literal(arg: Arg?): KotlinString? = arg?.string?.takeIf { !it.unterminated && it.interpolations.isEmpty() }

/**
 * The issues of the literal arguments of [site], an SSE call or an answer, that core refuses as it
 * runs. The selector of a patch is read where [selector] says, as the markup's check reads it, and
 * [elements] is the markup handed to it, which that check judges otherwise.
 */
@OptIn(InternalStreamlordApi::class)
internal fun eventArgumentIssues(
    site: CallSite,
    selector: Arg?,
): List<Issue> {
    val call = EVENT_CALLS[site.name] ?: return emptyList()
    val out = ArrayList<Issue>()

    fun refused(
        start: Int,
        end: Int,
        problem: String?,
        code: String = "event-refused",
    ) {
        if (problem != null) out += Issue(start, end, "${site.name} throws: $problem.", Severity.ERROR, code, Docs.SSE)
    }

    fun selectorIssue(
        s: KotlinString?,
        field: String,
    ) {
        s ?: return
        val line = EventRules.selector(s.text, field)
        if (line != null) {
            refused(s.start, s.end, line, if (s.text.isBlank()) "blank-selector" else "selector-newline")
        } else if (call.response) {
            refused(s.start, s.end, EventRules.headerSelector(s.text))
        }
    }
    val first = site.named(call.first) ?: site.positional.firstOrNull().takeIf { !site.trailingLambda || call.first != "elements" }
    when (call.first) {
        "selector" -> selectorIssue(literal(first), "selector")
        "signals" -> literal(first)?.let { refused(it.start, it.end, EventRules.signals(it.text)) }
        "script" -> literal(first)?.let { refused(it.start, it.end, EventRules.script(it.text)) }
        "url" -> literal(first)?.let { refused(it.start, it.end, EventRules.redirect(it.text)) }
    }
    if (call.first == "elements") {
        selectorIssue(literal(selector), "selector")
        selectorIssue(literal(site.named("viewTransitionSelector")), "viewTransitionSelector")
    }
    if (call.stream) {
        literal(site.named("eventId"))?.let { refused(it.start, it.end, EventRules.eventId(it.text)) }
        site.named("retry")?.let { arg -> durationLiteral(arg.text)?.let { refused(arg.start, arg.end, EventRules.retry(it)) } }
    }
    return out
}

/** The issue of [elements], the literal markup of [site], where core refuses it as nothing to patch in [mode]. */
@OptIn(InternalStreamlordApi::class)
internal fun blankElementsIssue(
    site: CallSite,
    elements: KotlinString,
    mode: ElementPatchMode,
): Issue? {
    val call = EVENT_CALLS[site.name] ?: return null
    if (elements.unterminated || elements.interpolations.isNotEmpty()) return null
    val problem = EventRules.elements(elements.text, mode, call.response) ?: return null
    return Issue(elements.start, elements.end, "${site.name} throws: $problem.", Severity.ERROR, "event-refused", Docs.SSE)
}

/** The message of a patch in [mode] that [site] makes without a selector, in core's words. */
@OptIn(InternalStreamlordApi::class)
internal fun modeWithoutSelector(
    site: CallSite,
    mode: ElementPatchMode,
): String? = EventRules.modeWithoutSelector(mode)?.let { "${site.name} throws: $it." }
