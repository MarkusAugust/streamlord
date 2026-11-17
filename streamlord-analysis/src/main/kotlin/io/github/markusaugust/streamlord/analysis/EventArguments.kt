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

/** [s] where it holds no template and is closed. */
private fun plain(s: KotlinString?): KotlinString? = s?.takeIf { !it.unterminated && it.interpolations.isEmpty() }

/** The string literal [arg] is, where it holds no template and is closed. */
private fun literal(arg: Arg?): KotlinString? = plain(arg?.string)

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
    if (!calls.named(site)) return emptyList()
    val out = ArrayList<Issue>()

    fun refused(
        start: Int,
        end: Int,
        problem: String?,
        code: IssueCode = IssueCode.EVENT_REFUSED,
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
            refused(s.start, s.end, line, if (s.text.isBlank()) IssueCode.BLANK_SELECTOR else IssueCode.SELECTOR_NEWLINE)
        } else if (call.response) {
            refused(s.start, s.end, EventRules.headerSelector(s.text))
        }
    }
    val takes = call.takes ?: return out
    val first = site.named(takes) ?: site.positional.firstOrNull().takeIf { !site.trailingLambda || takes != "elements" }
    // A removal's selector is judged on a receiver of any name, as it always was; the rest only where the receiver says a stream.
    if (takes == "selector") selectorIssue(literal(first), "selector")
    if (!calls.ours(site)) return out
    when (takes) {
        "elements" -> {
            selectorIssue(literal(selector), "selector")
        }

        "signals" -> {
            literal(first)?.let { refused(it.start, it.end, EventRules.signals(it.text)) }
        }

        "url" -> {
            literal(first)?.let { refused(it.start, it.end, EventRules.redirect(it.text)) }
        }

        // A hyphen has an issue of its own, with the name to write instead.
        "signalNames" -> {
            for ((s, why) in removalRefusals(site)) if ('-' !in s.text) refused(s.start, s.end, why)
        }

        "script" -> {
            literal(first)?.let { refused(it.start, it.end, EventRules.script(it.text)) }
            // An event takes autoRemove second and the attributes third, an answer its attributes second.
            // An autoRemove the code computes may be false, so the effect attribute is judged only beside a literal.
            val removes = if (call.response) null else site.named("autoRemove") ?: site.positional.getOrNull(1)
            val autoRemove = !call.response && (removes == null || removes.text.trim() == "true")
            val attributes = site.named("attributes") ?: site.positional.getOrNull(if (call.response) 1 else 2)
            val at = attributes?.start ?: 0
            val effect = prefix + "effect"
            for (pair in attributes?.let { literalPairs(it.text) }.orEmpty()) {
                plain(pair.key)?.let { refused(at + it.start, at + it.end, EventRules.scriptAttributeName(it.text, autoRemove, effect)) }
                if (call.response) plain(pair.value)?.let { refused(at + it.start, at + it.end, EventRules.scriptAttributeValue(it.text)) }
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
    calls: EventCalls,
    elements: KotlinString,
    mode: ElementPatchMode,
    selector: Arg?,
    namespace: ElementNamespace?,
): Issue? {
    val name = calls.nameOf(site) ?: return null
    val call = EVENT_CALLS.getValue(name)
    if (elements.unterminated || elements.interpolations.isNotEmpty()) return null
    // A selector the code computes is taken to be one, which judges the fewest elements as none.
    val given = selector?.text?.takeIf { it != "null" }?.let { literal(selector)?.text ?: "#" }
    val problem = EventRules.elements(elements.text, mode, given, namespace, call.response) ?: return null
    return Issue(elements.start, elements.end, "$name throws: $problem.", Severity.ERROR, IssueCode.EVENT_REFUSED, Docs.SSE)
}

/** The message of a patch in [mode] that [site] makes without a selector, in core's words. */
@OptIn(InternalStreamlordApi::class)
internal fun modeWithoutSelector(
    site: CallSite,
    calls: EventCalls,
    mode: ElementPatchMode,
): String? = EventRules.modeWithoutSelector(mode)?.let { "${calls.nameOf(site) ?: site.name} throws: $it." }

/** A receiver named as a stream is: `sse`, `stream`, `datastarStream`. */
private val STREAM_RECEIVER = regex("""(?i)sse|stream|datastar""")
private val RECEIVER_NAME = regex("""[A-Za-z_][A-Za-z0-9_]*$""")
private const val STREAMLORD = "io.github.markusaugust.streamlord."
private const val STREAM_TYPE = "DatastarStream"

/** Ktor's call and its routing call, which `respondElements` and the rest extend. */
private val KTOR_CALLS = setOf("ApplicationCall", "RoutingCall")

/** A class of the file that implements core's stream: `class Recording : DatastarStream`, after its constructor if any. */
private val STREAM_CLASS =
    regex("""\b(?:class|object)\s+([A-Z][A-Za-z0-9_]*)(?:\s*\([^()]*(?:\([^()]*\)[^()]*)*\))?\s*:[^{\n]*\b$STREAM_TYPE\b""")

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

/** The names core's removals of signals are written with: `removeSignals`, and the companion's `remove`. */
internal val removalCallNames: Set<String> =
    EVENT_CALLS
        .filter {
            it.value.takes == "signalNames"
        }.keys
        .map { it.substringAfterLast('.') }
        .toSet()

/**
 * The literal names [site], a removal of signals, hands core, each with why core refuses it as it
 * takes them together: a name under another one removed is left out.
 */
@OptIn(InternalStreamlordApi::class)
internal fun removalRefusals(site: CallSite): List<Pair<KotlinString, String>> {
    val names = site.positional.mapNotNull { literal(it) }
    return names.zip(EventRules.removedSignals(names.map { it.text })).mapNotNull { (s, why) -> why?.let { s to it } }
}

/** Whether [site] in [src] is, by its name and receiver, core's removal of signals. */
internal fun removesSignals(
    src: String,
    site: CallSite,
): Boolean = eventCallName(src, site)?.let { EVENT_CALLS[it]?.takes } == "signalNames"

/**
 * Which calls of [src] are Streamlord's SSE calls and answers, read once for the file. A call may be
 * ([named]) when the file imports something of Streamlord's, does not declare the name or import it
 * from elsewhere ([shadowsCore]), the companion's name for a companion's function, and does not
 * declare the receiver with a type other than a stream's. It is ([ours]) when the receiver is
 * declared a stream, or the file declares no type of it and it is none, `this`, a receiver named as
 * a stream (`sse`, `stream`, `datastar` in any case), or `call` for Ktor's `respond*`.
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

    /**
     * Whether [site] may be core's call: the file imports Streamlord and takes the name from nowhere
     * else, and the receiver, where the file declares its type, is a stream.
     */
    fun named(site: CallSite): Boolean {
        if (!streamlord) return false
        val declared = nameOf(site)?.substringBefore('.') ?: return false
        val elsewhere =
            shadowed.getOrPut(declared) {
                shadowsCore(src, declared) && imports.none { it.startsWith(STREAMLORD) && it.endsWith(".$declared") }
            }
        return !elsewhere && streamReceiver(site) != false
    }

    private val declarations by lazy(LazyThreadSafetyMode.NONE) { Declarations(src) }

    /** Core's stream type, and the classes of the file that implement it, a test's fake among them. */
    private val streamTypes by lazy(LazyThreadSafetyMode.NONE) { setOf(STREAM_TYPE) + STREAM_CLASS.findAll(src).map { it.groupValues[1] } }

    /**
     * Whether the receiver of [site] is one core's calls are made on, by the type the file declares it
     * with: a stream, or Ktor's call for its `respond*`; null where the file declares none.
     */
    private fun streamReceiver(site: CallSite): Boolean? {
        val receiver = receiver(src, site)?.takeIf { it.isNotEmpty() && it != "this" } ?: return null
        val type = declarations.typeOf(receiver, site.nameStart) ?: return null
        return type in streamTypes || (site.name.startsWith("respond") && type in KTOR_CALLS)
    }

    /** Whether [site] is core's call as far as the text shows: [named], and on a receiver that is a stream's or a companion's. */
    fun ours(site: CallSite): Boolean {
        if (!named(site)) return false
        if ('.' in nameOf(site)!! || streamReceiver(site) == true) return true
        val receiver = receiver(src, site) ?: return false
        return receiver.isEmpty() || receiver == "this" || STREAM_RECEIVER.containsMatchIn(receiver) ||
            (receiver == "call" && site.name.startsWith("respond"))
    }
}
