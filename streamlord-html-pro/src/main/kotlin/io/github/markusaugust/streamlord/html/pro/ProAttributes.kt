@file:Suppress("TooManyFunctions")

package io.github.markusaugust.streamlord.html.pro

import io.github.markusaugust.streamlord.core.domain.ExpressionGuard
import io.github.markusaugust.streamlord.html.Case
import io.github.markusaugust.streamlord.html.Casing
import io.github.markusaugust.streamlord.html.DatastarAttributes
import io.github.markusaugust.streamlord.html.SignalFilter
import io.github.markusaugust.streamlord.html.js
import kotlinx.html.HTMLTag
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/*
 * The forbidden wing of the library: kotlinx.html helpers for the attributes of Datastar Pro.
 *
 * Read this before you use them. Datastar Pro is licensed software. This module contains none
 * of it: no plugin source, no bundle, nothing but the attribute names and modifier spellings
 * documented publicly at data-star.dev. Every function here writes a string into an HTML
 * attribute and nothing more. Whether that attribute does anything in the browser depends on
 * you holding a Pro license and loading the Pro bundle yourself. Without it these attributes
 * are inert, and Streamlord makes no attempt to change that.
 *
 * The module is a separate artifact for exactly that reason: adding it is your explicit opt-in.
 */

// The free attributes' rule: whole milliseconds the browser's timers take, from 0 to Int.MAX_VALUE.
private fun Duration.ms(): String {
    require(!isNegative() && this <= Int.MAX_VALUE.milliseconds) {
        "$this is outside what the browser's timers take: 0 to ${Int.MAX_VALUE}ms"
    }
    require(!isPositive() || inWholeMilliseconds > 0) { "$this is shorter than a millisecond, the smallest duration Datastar reads" }
    return "${inWholeMilliseconds}ms"
}

private fun requireDuration(
    duration: Duration?,
    vararg flags: Pair<String, Boolean>,
    name: String,
) {
    if (duration != null) return
    flags.firstOrNull { it.second }?.let { throw IllegalArgumentException("${it.first} needs $name, which is not set") }
}

private fun ds(suffix: String): String = DatastarAttributes.name(suffix)

/** [expression] once [ExpressionGuard] has passed it, as kotlinx.html must be handed it ([DatastarAttributes.value]). */
private fun expr(expression: String): String = DatastarAttributes.value(ExpressionGuard.check(expression))

// The timing wrapper the Pro listeners share with the free ones, in the free builders' order.
private fun StringBuilder.appendTiming(
    delay: Duration?,
    debounce: Duration?,
    debounceLeading: Boolean,
    debounceNoTrailing: Boolean,
    throttle: Duration?,
    throttleNoLeading: Boolean,
    throttleTrailing: Boolean,
) {
    requireDuration(debounce, "debounceLeading" to debounceLeading, "debounceNoTrailing" to debounceNoTrailing, name = "debounce")
    requireDuration(throttle, "throttleNoLeading" to throttleNoLeading, "throttleTrailing" to throttleTrailing, name = "throttle")
    delay?.let { append("__delay.").append(it.ms()) }
    debounce?.let {
        append("__debounce.").append(it.ms())
        if (debounceLeading) append(".leading")
        if (debounceNoTrailing) append(".notrailing")
    }
    throttle?.let {
        append("__throttle.").append(it.ms())
        if (throttleNoLeading) append(".noleading")
        if (throttleTrailing) append(".trailing")
    }
}

/** Modifiers for `data-on-resize`: the timing of `data-on` and `__viewtransition`. */
public class ResizeModifiers {
    public var delay: Duration? = null
    public var debounce: Duration? = null
    public var debounceLeading: Boolean = false
    public var debounceNoTrailing: Boolean = false
    public var throttle: Duration? = null
    public var throttleNoLeading: Boolean = false
    public var throttleTrailing: Boolean = false
    public var viewTransition: Boolean = false

    internal fun build(): String =
        buildString {
            appendTiming(delay, debounce, debounceLeading, debounceNoTrailing, throttle, throttleNoLeading, throttleTrailing)
            if (viewTransition) append("__viewtransition")
        }
}

/** Modifiers for `data-on-raf`: the timing of `data-on` and `__viewtransition`. */
public class RafModifiers {
    public var delay: Duration? = null
    public var debounce: Duration? = null
    public var debounceLeading: Boolean = false
    public var debounceNoTrailing: Boolean = false
    public var throttle: Duration? = null
    public var throttleNoLeading: Boolean = false
    public var throttleTrailing: Boolean = false
    public var viewTransition: Boolean = false

    internal fun build(): String =
        buildString {
            appendTiming(delay, debounce, debounceLeading, debounceNoTrailing, throttle, throttleNoLeading, throttleTrailing)
            if (viewTransition) append("__viewtransition")
        }
}

/**
 * The easing curves `data-animate__ease.<name>` can name. The names are Datastar Pro's; the Pro
 * bundle you license and load is what gives them meaning, and nothing but the names is here.
 */
public enum class Easing(
    public val wire: String,
) {
    LINEAR("linear"),
    QUADRATIC("quadratic"),
    CUBIC("cubic"),
    ELASTIC("elastic"),
    IN_QUAD("inquad"),
    OUT_QUAD("outquad"),
    IN_OUT_QUAD("inoutquad"),
    IN_CUBIC("incubic"),
    OUT_CUBIC("outcubic"),
    IN_OUT_CUBIC("inoutcubic"),
    IN_QUART("inquart"),
    OUT_QUART("outquart"),
    IN_OUT_QUART("inoutquart"),
    IN_QUINT("inquint"),
    OUT_QUINT("outquint"),
    IN_OUT_QUINT("inoutquint"),
    IN_SINE("insine"),
    OUT_SINE("outsine"),
    IN_OUT_SINE("inoutsine"),
    IN_EXPO("inexpo"),
    OUT_EXPO("outexpo"),
    IN_OUT_EXPO("inoutexpo"),
    IN_CIRC("incirc"),
    OUT_CIRC("outcirc"),
    IN_OUT_CIRC("inoutcirc"),
    IN_ELASTIC("inelastic"),
    OUT_ELASTIC("outelastic"),
    IN_OUT_ELASTIC("inoutelastic"),
    IN_BACK("inback"),
    OUT_BACK("outback"),
    IN_OUT_BACK("inoutback"),
    IN_BOUNCE("inbounce"),
    OUT_BOUNCE("outbounce"),
    IN_OUT_BOUNCE("inoutbounce"),
    IN_GOLDEN("ingolden"),
    OUT_GOLDEN("outgolden"),
    IN_OUT_GOLDEN("inoutgolden"),
}

/** Modifiers for `data-animate`. */
public class AnimateModifiers {
    /** How long the animation runs; Datastar Pro's default is one second. */
    public var duration: Duration? = null

    /** How long to wait before it starts. */
    public var delay: Duration? = null

    /** The curve it follows; Datastar Pro's default is [Easing.LINEAR]. */
    public var ease: Easing? = null

    /** Start over from the start value when the animation ends. */
    public var loop: Boolean = false

    /** Run back to the start value when the animation ends, and so on. */
    public var pingpong: Boolean = false

    internal fun build(): String =
        buildString {
            duration?.let { append("__duration.").append(it.ms()) }
            delay?.let { append("__delay.").append(it.ms()) }
            ease?.let { append("__ease.").append(it.wire) }
            if (loop) append("__loop")
            if (pingpong) append("__pingpong")
        }
}

/** Scroll behaviour for `data-scroll-into-view`. */
public enum class ScrollBehavior(
    internal val modifier: String,
) {
    SMOOTH("__smooth"),
    INSTANT("__instant"),
    AUTO("__auto"),
}

/** Horizontal alignment for `data-scroll-into-view`. */
public enum class HorizontalAlign(
    internal val modifier: String,
) {
    START("__hstart"),
    CENTER("__hcenter"),
    END("__hend"),
    NEAREST("__hnearest"),
}

/** Vertical alignment for `data-scroll-into-view`. */
public enum class VerticalAlign(
    internal val modifier: String,
) {
    START("__vstart"),
    CENTER("__vcenter"),
    END("__vend"),
    NEAREST("__vnearest"),
}

/**
 * `data-animate="{attribute: expression}"` (Pro): the object form, each attribute in it animated
 * whenever its expression's signals change. The [modifiers] apply to all of them. Each value must
 * be a string, `'12px'` or `String($r)`: the plugin reads the number and unit out of a string, and
 * throws on a number, where the key form writes its value as a string first.
 */
public fun HTMLTag.dataAnimate(
    expression: String,
    modifiers: AnimateModifiers.() -> Unit = {},
) {
    attributes[ds("animate${AnimateModifiers().apply(modifiers).build()}")] = expr(expression)
}

/**
 * `data-animate:attribute="expression"` (Pro): animate an attribute whenever its signals change,
 * with [attribute] as HTML has it: `dataAnimate("stroke-width", ...)`. The browser lowercases an
 * attribute name, so a capital is refused: one that really has capitals, such as SVG's `viewBox`,
 * takes the object form, `dataAnimate("{viewBox: ...}")`.
 */
public fun HTMLTag.dataAnimate(
    attribute: String,
    expression: String,
    modifiers: AnimateModifiers.() -> Unit = {},
) {
    val mods = AnimateModifiers().apply(modifiers).build()
    attributes[ds("animate:" + Casing.withModifiers(attribute, Casing.attributeKey(attribute, "dataAnimate"), mods))] = expr(expression)
}

/** `data-custom-validity="expression"` (Pro): an expression yielding `''` when valid, a message otherwise. */
public fun HTMLTag.dataCustomValidity(expression: String) {
    attributes[ds("custom-validity")] = expr(expression)
}

/**
 * `data-match-media:signal="'(query)'"` (Pro): a boolean signal that follows a media query, with
 * [signal] written as `dataSignals` writes a name.
 */
public fun HTMLTag.dataMatchMedia(
    signal: String,
    mediaQuery: String,
) {
    val (key, mod) = Casing.key(Casing.signalName(signal), null, Case.CAMEL)
    val mods = mod?.let { "__case.${it.wire}" } ?: ""
    attributes[ds("match-media:" + Casing.heldKey(signal, Casing.withModifiers(signal, key, mods)))] = js(mediaQuery)
}

/** `data-on-raf="expression"` (Pro): run on every animation frame. */
public fun HTMLTag.dataOnRaf(
    expression: String,
    modifiers: RafModifiers.() -> Unit = {},
) {
    attributes[ds("on-raf${RafModifiers().apply(modifiers).build()}")] = expr(expression)
}

/** `data-on-resize="expression"` (Pro): run when the element's size changes. */
public fun HTMLTag.dataOnResize(
    expression: String,
    modifiers: ResizeModifiers.() -> Unit = {},
) {
    attributes[ds("on-resize${ResizeModifiers().apply(modifiers).build()}")] = expr(expression)
}

/**
 * `data-persist` (Pro): keep signals in local storage (or session storage) across page loads.
 *
 * @param key The storage key. It is written kebab-case like the other keys Datastar uses as the
 * browser hands them (`attr`, `style`, `animate`): the browser lowercases an attribute name, so
 * `myKey` could never reach the client, and `my-key` is what the signals are stored under.
 * Datastar's default is `datastar`.
 * @param filter Which signals to persist.
 * @param session Use `sessionStorage` instead of `localStorage`.
 */
public fun HTMLTag.dataPersist(
    key: String? = null,
    filter: SignalFilter? = null,
    session: Boolean = false,
) {
    val name =
        buildString {
            append(ds("persist"))
            val modifiers = if (session) "__session" else ""
            key?.let { append(':').append(Casing.withModifiers(it, Casing.plainKey(it), modifiers)) } ?: append(modifiers)
        }
    attributes[name] = filter?.toJs() ?: ""
}

/**
 * `data-query-string` (Pro): two-way sync between signals and the URL query string.
 *
 * @param filter Which signals to sync.
 * @param omitEmpty The `__filter` modifier: leave empty values out of the query string.
 * @param history The `__history` modifier: push a history entry on every change.
 */
public fun HTMLTag.dataQueryString(
    filter: SignalFilter? = null,
    omitEmpty: Boolean = false,
    history: Boolean = false,
) {
    val name =
        buildString {
            append(ds("query-string"))
            if (omitEmpty) append("__filter")
            if (history) append("__history")
        }
    attributes[name] = filter?.toJs() ?: ""
}

/** `data-replace-url="expression"` (Pro): replace the browser URL without a reload. */
public fun HTMLTag.dataReplaceUrl(expression: String) {
    attributes[ds("replace-url")] = expr(expression)
}

/** `data-scroll-into-view` (Pro): scroll the element into the viewport when it appears. */
public fun HTMLTag.dataScrollIntoView(
    behavior: ScrollBehavior? = null,
    horizontal: HorizontalAlign? = null,
    vertical: VerticalAlign? = null,
    focus: Boolean = false,
) {
    val name =
        buildString {
            append(ds("scroll-into-view"))
            behavior?.let { append(it.modifier) }
            horizontal?.let { append(it.modifier) }
            vertical?.let { append(it.modifier) }
            if (focus) append("__focus")
        }
    attributes[name] = ""
}

/** `data-view-transition="expression"` (Pro): set the element's `view-transition-name`. */
public fun HTMLTag.dataViewTransition(expression: String) {
    attributes[ds("view-transition")] = expr(expression)
}
