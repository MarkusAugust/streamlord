package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.StreamlordException

/**
 * Raised for a name no signal can carry: blank, or with characters that are neither letters,
 * digits, `_`, `$`, `.` nor `-`.
 */
public class InvalidSignalNameException(
    public val name: String,
    reason: String,
) : StreamlordException("Signal name \"$name\" $reason.")

/**
 * The casing rules of Datastar, applied so that the name you write is the name you get.
 *
 * The browser lowercases every attribute name, so `data-signals:fooBar` reaches Datastar as
 * `foobar`. Datastar then reads keys of the signal attributes (`signals`, `computed`, `bind`,
 * `ref`, `indicator`, `match-media`) in camel case, turning `foo-bar` into `fooBar`; keys of
 * `on` and `class` stay kebab unless `__case` says otherwise; keys of `attr`, `style` and
 * `animate` are used as they are. Hence the rule: keys are written in kebab-case, signals are
 * read in camelCase, and `__case` is the escape hatch.
 *
 * The DSL applies the rule for you. A camelCase key becomes kebab-case on the wire, plus
 * `__case.camel` where the default is kebab, so that `dataSignals("fooBar", "1")` declares
 * `$fooBar`, `dataOn("widgetLoaded", ...)` listens to `widgetLoaded`, and `dataClass("isOpen", ...)`
 * toggles `isOpen`. The expression helpers go the other way: `signal("foo-bar")` is `$fooBar`,
 * because that is what Datastar calls it.
 */
public object Casing {
    private val ALLOWED = Regex("^[A-Za-z0-9_$.\\-]+$")
    private val UPPER = Regex("[A-Z]")
    private val KEBAB = Regex("-([a-z])")

    /** `fooBar` -> `foo-bar`, `myURL` -> `my-u-r-l`: one hyphen per capital, so Datastar's camel conversion gives the name back. */
    public fun kebab(name: String): String = name.replace(UPPER) { "-" + it.value.lowercase() }.removePrefix("-")

    /** Datastar's own reading of a key: `-x` becomes `X`. */
    public fun camel(name: String): String = name.replace(KEBAB) { it.groupValues[1].uppercase() }

    public fun validate(name: String): String {
        if (name.isBlank()) throw InvalidSignalNameException(name, "is blank")
        if (!ALLOWED.matches(name)) throw InvalidSignalNameException(name, "contains characters a signal name cannot have")
        return name
    }

    /**
     * The key and the `__case` to write so that the client derives exactly [name].
     * [default] is what the client applies to the key when no `__case` is given.
     */
    public fun key(
        name: String,
        case: Case?,
        default: Case,
    ): Pair<String, Case?> {
        validate(name)
        if (case != null || !UPPER.containsMatchIn(name)) return name to case
        val wanted = if (name.first().isUpperCase()) Case.PASCAL else Case.CAMEL
        return kebab(name) to (if (wanted == default) null else wanted)
    }

    /** The key for an attribute whose key is used as written (`attr`, `style`, `animate`): `ariaLabel` -> `aria-label`. */
    public fun plainKey(name: String): String = kebab(validate(name))

    /** The name as an expression refers to it: `foo-bar` -> `fooBar`, `form.first-name` -> `form.firstName`. */
    public fun reference(name: String): String = camel(validate(name))
}
