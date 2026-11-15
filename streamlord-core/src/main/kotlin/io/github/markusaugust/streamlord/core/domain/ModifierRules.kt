package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.InternalStreamlordApi

/**
 * What the DSL's modifiers refuse as they are written, for the DSL that throws and the analysis
 * that reads the lambda of modifiers before it runs, so the two say the same thing.
 */
@InternalStreamlordApi
public object ModifierRules {
    /** Each duration of the modifiers, by its property, and the flags written only after it. */
    public val durationFlags: Map<String, List<String>> =
        mapOf(
            "duration" to listOf("leading"),
            "debounce" to listOf("debounceLeading", "debounceNoTrailing"),
            "throttle" to listOf("throttleNoLeading", "throttleTrailing"),
        )

    /** Why [flag] is refused when [duration] is not set: the client reads it only after the duration. */
    public fun flagWithoutDuration(
        flag: String,
        duration: String,
    ): String = "$flag needs $duration, which is not set"

    /** Why `half`, `full` and `threshold` are refused together: the client reads full, then half, then threshold, and drops the rest. */
    public const val ALTERNATIVES: String = "half, full and threshold are alternatives; set one"

    /** The percentages `threshold` takes. */
    public val thresholds: IntRange = 0..100

    /** Why a threshold outside [thresholds] is refused. */
    public const val THRESHOLD_RANGE: String = "threshold must be between 0 and 100"

    /** Why an interval of [duration], no time or less, is refused: it would run as often as the browser's timers allow. */
    public fun intervalNotPositive(duration: String): String = "duration must be positive, not $duration"
}
