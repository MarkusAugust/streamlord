package io.github.markusaugust.streamlord.core.protocol

/**
 * The prefix every Datastar attribute starts with.
 *
 * The standard bundle reads `data-*`. The aliased bundle (`datastar-aliased.js`), built for
 * pages where another library already claims those names, reads `data-star-*` instead. Set
 * [prefix] once at startup to match the bundle you load. The core honours it where it writes
 * an attribute itself (the `data-effect="el.remove()"` of [io.github.markusaugust.streamlord.core.domain.ExecuteScript]),
 * and `streamlord-html` exposes the same switch as `DatastarAttributes` in its own package.
 */
public object DatastarAttributes {
    /** `"data-"` for the standard bundle, `"data-star-"` for the aliased one. */
    @Volatile
    public var prefix: String = "data-"

    /** The full attribute name for a Datastar attribute such as `on:click__once`. */
    public fun name(suffix: String): String = prefix + suffix
}
