package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.DatastarEventValidationException

/**
 * The eight ways an element may be driven into the living DOM.
 *
 * Two of them, [OUTER] and [INNER], are *morphs*: the existing element is bent towards the new
 * shape while its state (focus, scroll position, input values) survives. The rest are blunt
 * instruments that insert, replace or remove without mercy.
 *
 * [wire] is the exact token Datastar 1.0.4 expects on the `mode` data line.
 */
public enum class ElementPatchMode(public val wire: String) {
    /** Morph the entire element, preserving state. The default, and the wise choice. */
    OUTER("outer"),

    /** Morph only the inner HTML of the target, preserving state. */
    INNER("inner"),

    /** Replace the entire element. State is lost; sometimes that is the point. */
    REPLACE("replace"),

    /** Insert the elements as the first children of the target. */
    PREPEND("prepend"),

    /** Insert the elements as the last children of the target. */
    APPEND("append"),

    /** Insert the elements immediately before the target. */
    BEFORE("before"),

    /** Insert the elements immediately after the target. */
    AFTER("after"),

    /** Remove the target from the DOM. No elements are needed, only a selector. */
    REMOVE("remove"),
    ;

    /** `true` for the modes that morph rather than overwrite. */
    public val isMorph: Boolean get() = this == OUTER || this == INNER

    /**
     * `true` for the modes that cannot work without a `selector`. Only [OUTER] and [REPLACE]
     * may target elements by their own `id`; the Datastar client rejects every other mode
     * that arrives without a selector, so Streamlord rejects it first.
     */
    public val requiresSelector: Boolean get() = this != OUTER && this != REPLACE

    public companion object {
        /** The mode assumed when none is written on the wire. */
        public val DEFAULT: ElementPatchMode = OUTER

        /** Resolve a wire token such as `"append"` (case-insensitive) to its mode. */
        public fun fromWire(value: String): ElementPatchMode =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
                ?: throw DatastarEventValidationException("Unknown element patch mode: '$value'")
    }
}
