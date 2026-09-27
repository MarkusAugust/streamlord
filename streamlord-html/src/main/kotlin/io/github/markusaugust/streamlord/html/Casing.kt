package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.StreamlordException

/**
 * Raised for a name no signal or attribute key can carry: blank, or with whitespace, quotes or
 * a character that ends an attribute.
 */
public class InvalidSignalNameException(
    public val name: String,
    reason: String,
) : StreamlordException("Name \"$name\" $reason.")

/**
 * The casing rules of Datastar, applied so that the name you write is the name you get.
 *
 * The browser lowercases every attribute name, so `data-signals:fooBar` reaches Datastar as
 * `foobar`. Datastar then reads keys of the signal attributes (`signals`, `computed`, `bind`,
 * `ref`, `indicator`, `match-media`) in camel case, turning `foo-bar` into `fooBar`; keys of
 * `on` and `class` stay kebab unless `__case` says otherwise; keys of `attr`, `style` and
 * `animate` are used as they are. Hence the rule: keys are written in kebab-case, signals are
 * read in camelCase, and `__case` is the escape hatch. The catalog records the default per
 * attribute as `keyCase`, and the catalog test holds this object to it.
 *
 * The DSL applies the rule for you. A camelCase key becomes kebab-case on the wire, plus
 * `__case.camel` where the default is kebab, so that `dataSignals("fooBar", "1")` declares
 * `$fooBar`, `dataOn("widgetLoaded", ...)` listens to `widgetLoaded`, and `dataClass("isOpen", ...)`
 * toggles `isOpen`. An explicit `case =` is kept, but the key is still written in kebab-case,
 * because the browser lowercases it either way. The expression helpers go the other way:
 * `signal("foo-bar")` is `$fooBar`, because that is what Datastar calls it.
 */
public object Casing {
    private val UPPER = Regex("[A-Z]")
    private val KEBAB = Regex("-([a-z])")
    private val FORBIDDEN = Regex("[\\s\"'<>=]")

    /** `fooBar` -> `foo-bar`, `myURL` -> `my-u-r-l`: one hyphen per capital, so Datastar's camel conversion gives the name back. */
    public fun kebab(name: String): String {
        val out = name.replace(UPPER) { "-" + it.value.lowercase() }
        // Only the hyphen a leading capital introduced is dropped; a CSS custom property keeps its `--`.
        return if (name.firstOrNull()?.isUpperCase() == true) out.removePrefix("-") else out
    }

    /** Datastar's own reading of a key: `-x` becomes `X`. */
    public fun camel(name: String): String = name.replace(KEBAB) { it.groupValues[1].uppercase() }

    /** A key or signal name must be non-blank and free of whitespace, quotes and the characters that end an attribute. */
    public fun validate(name: String): String {
        if (name.isBlank()) throw InvalidSignalNameException(name, "is blank")
        if (FORBIDDEN.containsMatchIn(name)) {
            throw InvalidSignalNameException(name, "contains whitespace, quotes or a character that ends an attribute")
        }
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
        if (!UPPER.containsMatchIn(name)) return name to case
        val key = kebab(name)
        if (case != null) return key to case
        val wanted = if (name.first().isUpperCase()) Case.PASCAL else Case.CAMEL
        return key to (if (wanted == default) null else wanted)
    }

    /** The key for an attribute whose key is used as written (`attr`, `style`, `animate`): `ariaLabel` -> `aria-label`. */
    public fun plainKey(name: String): String = kebab(validate(name))

    /** The name as an expression refers to it: `foo-bar` -> `fooBar`, `form.first-name` -> `form.firstName`, `items[0]` untouched. */
    public fun reference(name: String): String = camel(validate(name))
}
