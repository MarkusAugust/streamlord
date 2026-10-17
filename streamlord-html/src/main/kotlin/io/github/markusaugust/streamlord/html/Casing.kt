package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.StreamlordException
import io.github.markusaugust.streamlord.core.domain.SignalNames

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
 * `signal("foo-bar")` is `$fooBar`, the signal `data-signals:foo-bar` declares. The pairs form,
 * `dataSignals("foo-bar" to 1)`, camel-cases its keys the same way, so all three name one signal.
 *
 * A key the DSL does not write keeps its hyphen: the object form, `dataSignals("{'foo-bar': 1}")`,
 * and a signal patched from the server. Datastar reads `$foo-bar` in an expression as the signal
 * `foo-bar`, which `signal` cannot write, so reach such a key with `signal("['foo-bar']")`.
 */
public object Casing {
    private val UPPER = Regex("[A-Z]")
    private val KEBAB = Regex("-([a-z])")
    private val FORBIDDEN = Regex("[\\s\"'<>=/]")

    // What Datastar's client finds behind a `$`: ASCII word characters, in parts joined by `.` or `-`.
    private val SIGNAL_NAME = Regex("[A-Za-z0-9_]+(?:[.-][A-Za-z0-9_]+)*")

    /** `fooBar` -> `foo-bar`, `myURL` -> `my-u-r-l`: one hyphen per capital, so Datastar's camel conversion gives the name back. */
    public fun kebab(name: String): String {
        val out = name.replace(UPPER) { "-" + it.value.lowercase() }
        // Only the hyphen a leading capital introduced is dropped; a CSS custom property keeps its `--`.
        return if (name.firstOrNull()?.isUpperCase() == true) out.removePrefix("-") else out
    }

    /** Datastar's own reading of a key: `-x` becomes `X`. */
    public fun camel(name: String): String = name.replace(KEBAB) { it.groupValues[1].uppercase() }

    /** A key or signal name must be non-blank and free of whitespace, quotes and the characters that end an attribute or its name. */
    public fun validate(name: String): String {
        if (name.isBlank()) throw InvalidSignalNameException(name, "is blank")
        if (FORBIDDEN.containsMatchIn(name)) {
            throw InvalidSignalNameException(name, "contains whitespace, quotes or a character that ends an attribute or its name")
        }
        // Datastar splits an attribute name at the first `__`: what follows is read as modifiers.
        if ("__" in name) throw InvalidSignalNameException(name, "contains __, where Datastar starts reading modifiers")
        return name
    }

    /**
     * A name that declares a signal: what [validate] admits, and only ASCII letters, digits and
     * `_`, in parts joined by `.` or `-`. Datastar's client reads nothing else after a `$`, so
     * `$über` or `$a:b` would reach no signal. No part may be `__proto__` or `constructor`, which
     * the client cannot hold ([SignalNames]).
     */
    public fun signalName(name: String): String {
        validate(name)
        SignalNames.pathProblem(name)?.let { throw InvalidSignalNameException(name, "names no signal the client can hold: $it") }
        if (!SIGNAL_NAME.matches(name)) {
            throw InvalidSignalNameException(
                name,
                "is not a name a \$ reference reaches: Datastar reads ASCII letters, digits and _, in parts joined by . or -",
            )
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

    /**
     * The name as an expression refers to it: `foo-bar` -> `fooBar`, `form.first-name` -> `form.firstName`,
     * `items[0]` and `items['sub-total']` untouched, and `['sub-total']` reaches a top-level key
     * with a hyphen. A reference is JavaScript, so only blank names, whitespace and a first
     * character Datastar does not read after a `$` are refused.
     */
    public fun reference(name: String): String {
        if (name.isBlank()) throw InvalidSignalNameException(name, "is blank")
        if (name.any { it.isWhitespace() }) throw InvalidSignalNameException(name, "contains whitespace")
        val first = name.first()
        if (first != '[' && first != '_' && first !in 'A'..'Z' && first !in 'a'..'z' && first !in '0'..'9') {
            throw InvalidSignalNameException(name, "starts with a character Datastar does not read after \$")
        }
        return name.replace(KEBAB_OUTSIDE_BRACKETS) { it.groupValues[1].uppercase() }
    }

    /** `-x` outside `[...]`: inside brackets a quoted key such as `['sub-total']` is text. */
    private val KEBAB_OUTSIDE_BRACKETS = Regex("-([a-z])(?![^\\[]*\\])")
}
