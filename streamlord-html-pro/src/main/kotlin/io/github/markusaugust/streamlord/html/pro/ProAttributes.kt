@file:Suppress("TooManyFunctions")

package io.github.markusaugust.streamlord.html.pro

import io.github.markusaugust.streamlord.html.Case
import io.github.markusaugust.streamlord.html.SignalFilter
import kotlinx.html.HTMLTag
import kotlin.time.Duration

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

private fun Duration.ms(): String = "${inWholeMilliseconds}ms"

/** Modifiers for `data-on-resize`. */
public class ResizeModifiers {
    public var debounce: Duration? = null
    public var debounceLeading: Boolean = false
    public var debounceNoTrailing: Boolean = false
    public var throttle: Duration? = null
    public var throttleNoLeading: Boolean = false
    public var throttleTrailing: Boolean = false

    internal fun build(): String = buildString {
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
}

/** Modifiers for `data-on-raf`. */
public class RafModifiers {
    public var throttle: Duration? = null
    public var throttleNoLeading: Boolean = false
    public var throttleTrailing: Boolean = false

    internal fun build(): String = buildString {
        throttle?.let {
            append("__throttle.").append(it.ms())
            if (throttleNoLeading) append(".noleading")
            if (throttleTrailing) append(".trailing")
        }
    }
}

/** Scroll behaviour for `data-scroll-into-view`. */
public enum class ScrollBehavior(internal val modifier: String) { SMOOTH("__smooth"), INSTANT("__instant"), AUTO("__auto") }

/** Horizontal alignment for `data-scroll-into-view`. */
public enum class HorizontalAlign(internal val modifier: String) {
    START("__hstart"), CENTER("__hcenter"), END("__hend"), NEAREST("__hnearest")
}

/** Vertical alignment for `data-scroll-into-view`. */
public enum class VerticalAlign(internal val modifier: String) {
    START("__vstart"), CENTER("__vcenter"), END("__vend"), NEAREST("__vnearest")
}

/** `data-animate:attribute="expression"` (Pro): animate an attribute whenever its signals change. */
public fun HTMLTag.dataAnimate(attribute: String, expression: String) {
    attributes["data-animate:$attribute"] = expression
}

/** `data-custom-validity="expression"` (Pro): an expression yielding `''` when valid, a message otherwise. */
public fun HTMLTag.dataCustomValidity(expression: String) {
    attributes["data-custom-validity"] = expression
}

/** `data-match-media:signal="'(query)'"` (Pro): a boolean signal that follows a media query. */
public fun HTMLTag.dataMatchMedia(signal: String, mediaQuery: String, case: Case? = null) {
    val mods = case?.let { "__case.${it.wire}" } ?: ""
    attributes["data-match-media:$signal$mods"] = "'$mediaQuery'"
}

/** `data-on-raf="expression"` (Pro): run on every animation frame. */
public fun HTMLTag.dataOnRaf(expression: String, modifiers: RafModifiers.() -> Unit = {}) {
    attributes["data-on-raf${RafModifiers().apply(modifiers).build()}"] = expression
}

/** `data-on-resize="expression"` (Pro): run when the element's size changes. */
public fun HTMLTag.dataOnResize(expression: String, modifiers: ResizeModifiers.() -> Unit = {}) {
    attributes["data-on-resize${ResizeModifiers().apply(modifiers).build()}"] = expression
}

/**
 * `data-persist` (Pro): keep signals in local storage (or session storage) across page loads.
 *
 * @param key Storage key; Datastar's default is `datastar`.
 * @param filter Which signals to persist.
 * @param session Use `sessionStorage` instead of `localStorage`.
 */
public fun HTMLTag.dataPersist(key: String? = null, filter: SignalFilter? = null, session: Boolean = false) {
    val name = buildString {
        append("data-persist")
        key?.let { append(':').append(it) }
        if (session) append("__session")
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
public fun HTMLTag.dataQueryString(filter: SignalFilter? = null, omitEmpty: Boolean = false, history: Boolean = false) {
    val name = buildString {
        append("data-query-string")
        if (omitEmpty) append("__filter")
        if (history) append("__history")
    }
    attributes[name] = filter?.toJs() ?: ""
}

/** `data-replace-url="expression"` (Pro): replace the browser URL without a reload. */
public fun HTMLTag.dataReplaceUrl(expression: String) {
    attributes["data-replace-url"] = expression
}

/** `data-scroll-into-view` (Pro): scroll the element into the viewport when it appears. */
public fun HTMLTag.dataScrollIntoView(
    behavior: ScrollBehavior? = null,
    horizontal: HorizontalAlign? = null,
    vertical: VerticalAlign? = null,
    focus: Boolean = false,
) {
    val name = buildString {
        append("data-scroll-into-view")
        behavior?.let { append(it.modifier) }
        horizontal?.let { append(it.modifier) }
        vertical?.let { append(it.modifier) }
        if (focus) append("__focus")
    }
    attributes[name] = ""
}

/** `data-view-transition="expression"` (Pro): set the element's `view-transition-name`. */
public fun HTMLTag.dataViewTransition(expression: String) {
    attributes["data-view-transition"] = expression
}
