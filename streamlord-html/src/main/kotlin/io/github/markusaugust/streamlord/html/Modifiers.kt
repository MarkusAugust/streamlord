package io.github.markusaugust.streamlord.html

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Casing applied by `__case` modifiers when a signal or event name is derived from an attribute key. */
public enum class Case(
    public val wire: String,
) {
    CAMEL("camel"),
    KEBAB("kebab"),
    SNAKE("snake"),
    PASCAL("pascal"),
}

/**
 * A regex filter of the shape Datastar expects: `{include: /.../, exclude: /.../}`.
 * Used by `data-json-signals`, `data-on-signal-patch-filter`, `filterSignals` and `@setAll`.
 *
 * The patterns are written as JavaScript regex literals. [RegexOption.IGNORE_CASE],
 * [RegexOption.MULTILINE] and [RegexOption.DOT_MATCHES_ALL] become the `i`, `m` and `s` flags;
 * the other options have no JavaScript equivalent and are refused. The quoting of
 * [Regex.escape], `\Q...\E`, is written out as escaped text, and the syntax only Java reads
 * is refused, since JavaScript would match something else or refuse the filter: inline flags,
 * possessive quantifiers, atomic groups, `\A`, `\z`, `\Z`, `\G`, `\p{...}`, `\x{...}` and
 * the other Java-only escapes, and nested or intersected classes.
 */
public data class SignalFilter(
    val include: Regex? = null,
    val exclude: Regex? = null,
) {
    /** Render as a JavaScript object literal with regex literals. */
    public fun toJs(): String =
        buildList {
            include?.let { add("include: ${regexLiteral(it)}") }
            exclude?.let { add("exclude: ${regexLiteral(it)}") }
        }.joinToString(", ", "{", "}")

    override fun toString(): String = toJs()

    public companion object {
        public fun include(pattern: String): SignalFilter = SignalFilter(include = Regex(pattern))

        public fun exclude(pattern: String): SignalFilter = SignalFilter(exclude = Regex(pattern))

        /**
         * A JavaScript regex literal with the flags the options translate to. An unescaped `/` in
         * the pattern is escaped so the literal stays intact; one already escaped is left alone.
         */
        public fun regexLiteral(regex: Regex): String {
            val flags =
                regex.options.joinToString("") { option ->
                    when (option) {
                        RegexOption.IGNORE_CASE -> "i"
                        RegexOption.MULTILINE -> "m"
                        RegexOption.DOT_MATCHES_ALL -> "s"
                        else -> throw IllegalArgumentException("Regex option $option has no JavaScript equivalent")
                    }
                }
            // Kotlin's inline flags have no spelling in a JavaScript literal, which would refuse the group.
            require(!INLINE_FLAGS.containsMatchIn(regex.pattern)) {
                "Inline flags such as (?i) are not JavaScript; pass RegexOption.IGNORE_CASE to Regex(...) instead"
            }
            val body = javaScriptBody(regex.pattern)
            // An empty literal is `//`, which JavaScript reads as the start of a comment.
            if (body.isEmpty()) return "/(?:)/$flags"
            return "/$body/$flags"
        }

        private val INLINE_FLAGS = Regex("""\(\?[a-zA-Z-]+[:)]""")

        // Escapes Java reads one way and JavaScript, without the u flag, another or not at all.
        private const val JAVA_ONLY_ESCAPES = "AzZGhHRXeapPvVNE"

        /**
         * The pattern as the body of a JavaScript literal: `\Q...\E` as escaped text, `/` and the
         * line terminators escaped so the literal stays one token, and Java-only syntax refused.
         */
        private fun javaScriptBody(pattern: String): String =
            buildString(pattern.length + 4) {
                var i = 0
                var inClass = false
                var quantified = false
                // Whether the open { began a {n,m} quantifier; a } that closes nothing is a literal.
                var countOpen = false
                while (i < pattern.length) {
                    val ch = pattern[i]
                    if (ch == '\\' && i + 1 < pattern.length) {
                        val next = pattern[i + 1]
                        when {
                            next == 'Q' -> {
                                val end = pattern.indexOf("\\E", i + 2).let { if (it < 0) pattern.length else it }
                                pattern.substring(i + 2, end).forEach { appendLiteral(it) }
                                i = end + 2
                                quantified = false
                                continue
                            }

                            next in JAVA_ONLY_ESCAPES -> {
                                javaOnly("\\$next")
                            }

                            next == 'x' && pattern.getOrNull(i + 2) == '{' -> {
                                javaOnly("\\x{...}")
                            }

                            next in LINE_TERMINATORS -> {
                                appendLiteral(next)
                            }

                            else -> {
                                append(ch).append(next)
                            }
                        }
                        i += 2
                        quantified = false
                        continue
                    }
                    if (inClass) {
                        when {
                            ch == ']' -> inClass = false

                            ch == '[' -> javaOnly("a [ inside a class", "Java reads it as a nested class; escape it")

                            ch == '&' && pattern.getOrNull(
                                i + 1,
                            ) == '&' -> javaOnly("&& inside a class", "Java reads it as an intersection")
                        }
                    } else {
                        when {
                            ch == '[' -> {
                                inClass = true
                                // A ] right after [ or [^ is a literal in Java and closes an empty class in JavaScript.
                                val start = if (pattern.getOrNull(i + 1) == '^') i + 2 else i + 1
                                if (pattern.getOrNull(start) == ']') javaOnly("a ] first in a class", "escape it")
                            }

                            ch == '(' && pattern.startsWith("(?>", i) -> {
                                javaOnly("an atomic group (?>...)")
                            }

                            ch == '+' && quantified -> {
                                javaOnly("a possessive quantifier")
                            }
                        }
                    }
                    quantified =
                        !inClass &&
                        when (ch) {
                            '*', '+' -> true
                            '?' -> pattern.getOrNull(i - 1) != '('
                            '}' -> countOpen
                            else -> false
                        }
                    if (!inClass && ch == '{') countOpen = pattern.getOrNull(i + 1)?.isDigit() == true
                    if (ch == '}') countOpen = false
                    when (ch) {
                        '/' -> append("\\/")
                        in LINE_TERMINATORS -> appendLiteral(ch)
                        else -> append(ch)
                    }
                    i++
                }
            }

        private const val LINE_TERMINATORS = "\n\r\u2028\u2029"

        /** [ch] as text that matches itself in a JavaScript literal, in a class or out of one. */
        private fun StringBuilder.appendLiteral(ch: Char) {
            when {
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\u2028' -> append("\\u2028")
                ch == '\u2029' -> append("\\u2029")
                ch.isLetterOrDigit() || ch == '_' || ch.code > 0x7F -> append(ch)
                else -> append('\\').append(ch)
            }
        }

        private fun javaOnly(
            what: String,
            hint: String? = null,
        ): Nothing =
            throw IllegalArgumentException(
                "$what is Java regex syntax that JavaScript reads differently or refuses" + (hint?.let { ": $it" } ?: ""),
            )
    }
}

/** Render a duration the way Datastar modifiers want it: whole milliseconds with a `ms` suffix. */
internal fun Duration.toModifier(): String = "${wholeMilliseconds()}ms"

/**
 * Datastar counts in whole milliseconds and hands them to the browser's timers. A shorter
 * positive duration would be written as zero, which says something other than what was asked
 * for, so it is refused instead. The timers take a negative wait as none and wrap one over
 * [Int.MAX_VALUE] milliseconds, about 24.8 days, into a negative one, so both fire at once and
 * are refused too.
 */
internal fun Duration.wholeMilliseconds(): Long {
    require(!isNegative() && this <= Int.MAX_VALUE.milliseconds) {
        "$this is outside what the browser's timers take: 0 to ${Int.MAX_VALUE}ms"
    }
    require(!isPositive() || inWholeMilliseconds > 0) { "$this is shorter than a millisecond, the smallest duration Datastar reads" }
    return inWholeMilliseconds
}

/** Refuse a flag that would be dropped because the duration it belongs to is not set. */
internal fun requireDuration(
    duration: Duration?,
    vararg flags: Pair<String, Boolean>,
    name: String,
) {
    if (duration != null) return
    flags.firstOrNull { it.second }?.let { throw IllegalArgumentException("${it.first} needs $name, which is not set") }
}

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
        requireDuration(debounce, "debounceLeading" to debounceLeading, "debounceNoTrailing" to debounceNoTrailing, name = "debounce")
        requireDuration(throttle, "throttleNoLeading" to throttleNoLeading, "throttleTrailing" to throttleTrailing, name = "throttle")
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

    override fun build(): String =
        buildString {
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

    /** Percentage (0-100) of the element that must be visible. Datastar reads one of [half], [full] and this. */
    public var threshold: Int? = null
    public var viewTransition: Boolean = false

    override fun build(): String =
        buildString {
            // Datastar's client takes full over half over threshold, so a second one would be dropped.
            require(listOf(half, full, threshold != null).count { it } <= 1) { "half, full and threshold are alternatives; set one" }
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

    internal fun build(): String =
        buildString {
            requireDuration(duration, "leading" to leading, name = "duration")
            duration?.let {
                // A zero interval runs the expression as often as the browser's timers allow.
                require(it.isPositive()) { "duration must be positive, not $it" }
                append("__duration.").append(it.toModifier())
                if (leading) append(".leading")
            }
            if (viewTransition) append("__viewtransition")
        }
}

/** Modifiers for `data-bind`. */
public class BindModifiers {
    /**
     * Casing of the signal name. Datastar applies `__case` only to a name written in the key,
     * so setting this switches the helper to `data-bind:name__case.x`; see [Casing].
     */
    public var case: Case? = null

    /** Bind to a specific element property instead of the default. */
    public var prop: String? = null

    /** Events that sync the element back into the signal. */
    public var events: List<String> = emptyList()

    internal fun build(): String =
        buildString {
            case?.let { append("__case.").append(it.wire) }
            // The browser lowercases attribute names and the client camel-cases the modifier back.
            prop?.let { append("__prop.").append(Casing.kebab(it)) }
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

    internal fun build(): String =
        buildString {
            delay?.let { append("__delay.").append(it.toModifier()) }
            if (viewTransition) append("__viewtransition")
        }
}
