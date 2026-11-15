package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.InternalStreamlordApi
import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.EventRules
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/*
 * The literal arguments of an SSE call, or of an answer that is no stream, that core refuses as it
 * runs, judged by core's own EventRules and said in its words. What the code computes is not judged.
 */

/** Core's SSE calls and answers, as the catalog lists them. */
private val EVENT_CALLS: Map<String, EventCallSpec> = Catalog.default.eventCalls

/** The names the calls core judges are written with: a companion's function by its own, `remove`. */
internal val eventCallNames: Set<String> = EVENT_CALLS.keys.map { it.substringAfterLast('.') }.toSet()

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
 * runs, where [calls] says it is Streamlord's. The selector of a patch is read where [selector]
 * says, as the markup's check reads it; the markup itself [elementsIssue] judges. [prefix] is the
 * attribute prefix, which names the effect attribute that removes a script once it has run.
 */
@OptIn(InternalStreamlordApi::class)
internal fun eventArgumentIssues(
    site: CallSite,
    selector: Arg?,
    calls: EventCalls,
    prefix: String,
): List<Issue> {
    val name = calls.nameOf(site) ?: return emptyList()
    val call = EVENT_CALLS.getValue(name)
    if (!calls.ours(site)) return emptyList()
    val out = ArrayList<Issue>()

    fun refused(
        start: Int,
        end: Int,
        problem: String?,
        code: String = "event-refused",
    ) {
        if (problem != null) out += Issue(start, end, "$name throws: $problem.", Severity.ERROR, code, Docs.SSE)
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
    val takes = call.takes ?: return out
    val first = site.named(takes) ?: site.positional.firstOrNull().takeIf { !site.trailingLambda || takes != "elements" }
    when (takes) {
        "selector" -> {
            selectorIssue(literal(first), "selector")
        }

        "elements" -> {
            selectorIssue(literal(selector), "selector")
        }

        "signals" -> {
            literal(first)?.let { refused(it.start, it.end, EventRules.signals(it.text)) }
        }

        "url" -> {
            literal(first)?.let { refused(it.start, it.end, EventRules.redirect(it.text)) }
        }

        "script" -> {
            literal(first)?.let { refused(it.start, it.end, EventRules.script(it.text)) }
            // An event takes autoRemove second and the attributes third, an answer its attributes second.
            // An autoRemove the code computes may be false, so the effect attribute is judged only beside a literal.
            val removes = if (call.response) null else site.named("autoRemove") ?: site.positional.getOrNull(1)
            val autoRemove = !call.response && (removes == null || removes.text.trim() == "true")
            val attributes = site.named("attributes") ?: site.positional.getOrNull(if (call.response) 1 else 2)
            for ((key, value) in attributes?.let(::mapPairs).orEmpty()) {
                key?.let { refused(it.start, it.end, EventRules.scriptAttributeName(it.text, autoRemove, prefix + "effect")) }
                if (call.response) value?.let { refused(it.start, it.end, EventRules.scriptAttributeValue(it.text)) }
            }
        }
    }
    if (takes == "elements" || takes == "selector") {
        selectorIssue(literal(site.named("viewTransitionSelector")), "viewTransitionSelector")
    }
    if (call.stream) {
        literal(site.named("eventId"))?.let { refused(it.start, it.end, EventRules.eventId(it.text)) }
        site.named("retry")?.let { arg -> durationLiteral(arg.text)?.let { refused(arg.start, arg.end, EventRules.retry(it)) } }
    }
    return out
}

/**
 * The issue of [elements], the literal markup of [site], where core refuses it: as nothing to patch
 * in [mode], or, in [namespace] where it is known, as SVG or MathML outside its root or HTML inside one.
 */
@OptIn(InternalStreamlordApi::class)
internal fun elementsIssue(
    site: CallSite,
    elements: KotlinString,
    mode: ElementPatchMode,
    selector: Arg?,
    namespace: ElementNamespace?,
): Issue? {
    val call = EVENT_CALLS[site.name] ?: return null
    if (elements.unterminated || elements.interpolations.isNotEmpty()) return null
    // A selector the code computes is taken to be one, which judges the fewest elements as none.
    val given = selector?.text?.takeIf { it != "null" }?.let { literal(selector)?.text ?: "#" }
    val problem =
        EventRules.elements(elements.text, mode, given, call.response)
            ?: namespace?.let { EventRules.markup(elements.text, mode, it) }
            ?: return null
    return Issue(elements.start, elements.end, "${site.name} throws: $problem.", Severity.ERROR, "event-refused", Docs.SSE)
}

/** A literal with no template, closed, at its offsets in the file. */
private class Literal(
    val start: Int,
    val end: Int,
    val text: String,
)

/**
 * The pairs of [arg] where it is a map built in place, `mapOf("type" to "module")`: each key and
 * value that is a [Literal], or null where it is something else.
 */
private fun mapPairs(arg: Arg): List<Pair<Literal?, Literal?>> {
    val text = arg.text
    val lex = lexKotlin(text)
    val map = findCallSites(text, MAP_CALLS, lex).firstOrNull()?.takeIf { text.substring(0, it.nameStart).isBlank() } ?: return emptyList()

    fun plain(s: KotlinString?) =
        s?.takeIf { !it.unterminated && it.interpolations.isEmpty() }?.let {
            Literal(
                arg.start + it.start,
                arg.start + it.end,
                it.text,
            )
        }
    return map.positional.map { pair ->
        val strings = lex.stringsIn(pair.start, pair.end)
        val key = strings.firstOrNull()?.takeIf { text.substring(pair.start, it.start).isBlank() }
        val value =
            key?.let { k ->
                strings.getOrNull(1)?.takeIf {
                    PAIR_TO.matchEntire(text.substring(k.end, it.start)) != null &&
                        text.substring(it.end, pair.end).isBlank()
                }
            }
        plain(key) to plain(value)
    }
}

private val PAIR_TO = regex("""\s+to\s+""")

/** The message of a patch in [mode] that [site] makes without a selector, in core's words. */
@OptIn(InternalStreamlordApi::class)
internal fun modeWithoutSelector(
    site: CallSite,
    mode: ElementPatchMode,
): String? = EventRules.modeWithoutSelector(mode)?.let { "${site.name} throws: $it." }

/** The receiver, besides a stream and a companion, whose calls are Streamlord's on any file that imports it. */
private const val THIS = "this"

/** A receiver named as a stream is: `sse`, `stream`, `datastarStream`. */
private val STREAM_RECEIVER = regex("""(?i)sse|stream|datastar""")
private val RECEIVER_NAME = regex("""[A-Za-z_][A-Za-z0-9_]*$""")
private const val STREAMLORD = "io.github.markusaugust.streamlord."

/** The name written before the `.` of [site] in [src], `PatchElements` of `PatchElements.remove(...)`; "" with no `.`, null with no name. */
private fun receiver(
    src: String,
    site: CallSite,
): String? {
    var i = site.nameStart - 1
    while (i >= 0 && src[i].isWhitespace()) i--
    if (i < 0 || src[i] != '.') return ""
    i--
    if (i >= 0 && src[i] == '?') i--
    return RECEIVER_NAME.find(src.substring(maxOf(0, i - 80), i + 1))?.value
}

/** The name [site] has among core's calls in [src], a companion's function as `PatchElements.remove`, or null with none. */
internal fun eventCallName(
    src: String,
    site: CallSite,
): String? {
    val qualified = receiver(src, site)?.takeIf { it.isNotEmpty() }?.let { "$it.${site.name}" }
    return qualified?.takeIf { it in EVENT_CALLS } ?: site.name.takeIf { it in EVENT_CALLS }
}

/** What core's call [name] takes first, as the catalog says. */
internal fun eventCallTakes(name: String): String? = EVENT_CALLS[name]?.takes

/**
 * Which calls of [src] are Streamlord's SSE calls and answers, read once for the file. A call is
 * when the file imports something of Streamlord's, does not declare the name or import it from
 * elsewhere ([shadowsCore]), the companion's name for a companion's function, and the call has no
 * receiver, or `this`, a receiver named as a stream (`sse`, `stream`, `datastar` in any case), or
 * `call` for Ktor's `respond*`.
 */
internal class EventCalls(
    private val src: String,
) {
    private val imports =
        src
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .map {
                it
                    .removePrefix("import ")
                    .substringBefore("//")
                    .trim()
                    .removeSuffix(";")
                    .trim()
            }.toList()
    private val streamlord = imports.any { it.startsWith(STREAMLORD) }
    private val shadowed = HashMap<String, Boolean>()

    fun nameOf(site: CallSite): String? = eventCallName(src, site)

    fun ours(site: CallSite): Boolean {
        if (!streamlord) return false
        val name = nameOf(site) ?: return false
        val declared = name.substringBefore('.')
        val elsewhere =
            shadowed.getOrPut(declared) {
                shadowsCore(src, declared) && imports.none { it.startsWith(STREAMLORD) && it.endsWith(".$declared") }
            }
        if (elsewhere) return false
        if ('.' in name) return true
        val receiver = receiver(src, site) ?: return false
        return receiver.isEmpty() || receiver == THIS || STREAM_RECEIVER.containsMatchIn(receiver) ||
            (receiver == "call" && site.name.startsWith("respond"))
    }
}
