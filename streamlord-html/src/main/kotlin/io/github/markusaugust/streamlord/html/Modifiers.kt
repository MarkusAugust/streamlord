package io.github.markusaugust.streamlord.html

import kotlin.time.Duration

/** Casing applied by `__case` modifiers when a signal or event name is derived from an attribute key. */
public enum class Case(public val wire: String) {
    CAMEL("camel"),
    KEBAB("kebab"),
    SNAKE("snake"),
    PASCAL("pascal"),
}

/**
 * A regex filter of the shape Datastar expects: `{include: /.../, exclude: /.../}`.
 * Used by `data-json-signals`, `data-on-signal-patch-filter`, `filterSignals` and `@setAll`.
 */
public data class SignalFilter(val include: Regex? = null, val exclude: Regex? = null) {
    /** Render as a JavaScript object literal with regex literals. */
    public fun toJs(): String = buildList {
        include?.let { add("include: ${regexLiteral(it)}") }
        exclude?.let { add("exclude: ${regexLiteral(it)}") }
    }.joinToString(", ", "{", "}")

    override fun toString(): String = toJs()

    public companion object {
        public fun include(pattern: String): SignalFilter = SignalFilter(include = Regex(pattern))
        public fun exclude(pattern: String): SignalFilter = SignalFilter(exclude = Regex(pattern))

        /** A JavaScript regex literal. Forward slashes in the pattern are escaped so the literal stays intact. */
        public fun regexLiteral(regex: Regex): String = "/" + regex.pattern.replace("/", "\\/") + "/"
    }
}

/** Render a duration the way Datastar modifiers want it: whole milliseconds with a `ms` suffix. */
internal fun Duration.toModifier(): String = "${inWholeMilliseconds}ms"

/**
 * The `__delay`, `__debounce` and `__throttle` modifiers shared by several attributes.
 */
public open class TimingModifiers {
    public var delay: Duration? = null
    public var debounce: Duration? = null
    public var debounceLeading: Boolean = false
    public var debounceNoTrailing: Boolean = false
    public var throttle: Duration? = null
    public var throttleNoLeading: Boolean = false
    public var throttleTrailing: Boolean = false

    internal fun appendTiming(sb: StringBuilder) {
        delay?.let { sb.append("__delay.").append(it.toModifier()) }
        debounce?.let {
            sb.append("__debounce.").append(it.toModifier())
            if (debounceLeading) sb.append(".leading")
            if (debounceNoTrailing) sb.append(".notrailing")
        }
        throttle?.let {
            sb.append("__throttle.").append(it.toModifier())
            if (throttleNoLeading) sb.append(".noleading")
            if (throttleTrailing) sb.append(".trailing")
        }
    }

    internal open fun build(): String = buildString { appendTiming(this) }
}

/** Modifiers for `data-on:*`. */
public class OnModifiers : TimingModifiers() {
    public var once: Boolean = false
    public var passive: Boolean = false
    public var capture: Boolean = false
    public var case: Case? = null
    public var viewTransition: Boolean = false
    public var window: Boolean = false
    public var document: Boolean = false
    public var outside: Boolean = false
    public var prevent: Boolean = false
    public var stop: Boolean = false

    override fun build(): String = buildString {
        if (once) append("__once")
        if (passive) append("__passive")
        if (capture) append("__capture")
        case?.let { append("__case.").append(it.wire) }
        appendTiming(this)
        if (viewTransition) append("__viewtransition")
        if (window) append("__window")
        if (document) append("__document")
        if (outside) append("__outside")
        if (prevent) append("__prevent")
        if (stop) append("__stop")
    }
}

/** Modifiers for `data-on-intersect`. */
public class IntersectModifiers : TimingModifiers() {
    public var once: Boolean = false
    public var exit: Boolean = false
    public var half: Boolean = false
    public var full: Boolean = false

    /** Percentage (0-100) of the element that must be visible. */
    public var threshold: Int? = null
    public var viewTransition: Boolean = false

    override fun build(): String = buildString {
        if (once) append("__once")
        if (exit) append("__exit")
        if (half) append("__half")
        if (full) append("__full")
        threshold?.let {
            require(it in 0..100) { "threshold must be between 0 and 100" }
            append("__threshold.").append(it)
        }
        appendTiming(this)
        if (viewTransition) append("__viewtransition")
    }
}

/** Modifiers for `data-on-interval`. */
public class IntervalModifiers {
    public var duration: Duration? = null

    /** Fire immediately as well as on every interval. Requires [duration]. */
    public var leading: Boolean = false
    public var viewTransition: Boolean = false

    internal fun build(): String = buildString {
        duration?.let {
            append("__duration.").append(it.toModifier())
            if (leading) append(".leading")
        }
        if (viewTransition) append("__viewtransition")
    }
}

/** Modifiers for `data-bind`. */
public class BindModifiers {
    public var case: Case? = null

    /** Bind to a specific element property instead of the default. */
    public var prop: String? = null

    /** Events that sync the element back into the signal. */
    public var events: List<String> = emptyList()

    internal fun build(): String = buildString {
        case?.let { append("__case.").append(it.wire) }
        prop?.let { append("__prop.").append(it) }
        if (events.isNotEmpty()) {
            append("__event")
            events.forEach { append('.').append(it) }
        }
    }
}

/** Modifiers for `data-init`. */
public class InitModifiers {
    public var delay: Duration? = null
    public var viewTransition: Boolean = false

    internal fun build(): String = buildString {
        delay?.let { append("__delay.").append(it.toModifier()) }
        if (viewTransition) append("__viewtransition")
    }
}
