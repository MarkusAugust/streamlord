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

    // Java's \s, the spaces JavaScript's \s adds (class.ts splits a class list on it), NUL, which
    // the HTML parser turns into U+FFFD, and the characters that end an attribute or its name.
    private val FORBIDDEN = Regex("[\\s\\x00\\xa0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff\"'<>=/]")

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

    /**
     * A key or signal name must be non-blank and free of whitespace (as Java or JavaScript counts
     * it, so a no-break space too), NUL, quotes and the characters that end an attribute or its name.
     */
    public fun validate(name: String): String {
        if (name.isBlank()) throw InvalidSignalNameException(name, "is blank")
        if (FORBIDDEN.containsMatchIn(name)) {
            throw InvalidSignalNameException(name, "contains whitespace, NUL, quotes or a character that ends an attribute or its name")
        }
        // Datastar splits an attribute name at the first `__`: what follows is read as modifiers.
        if ("__" in name) throw InvalidSignalNameException(name, "contains __, where Datastar starts reading modifiers")
        return name
    }

    /**
     * A name that declares a signal: what [validate] admits, and only ASCII letters, digits and
     * `_`, in parts joined by `.` or `-`. Datastar's client reads nothing else after a `$`, so
     * `$über` or `$a:b` would reach no signal. No part, as written or as Datastar camel-cases it,
     * may be a property every object has, such as `constructor` or `toString` ([SignalNames]).
     */
    public fun signalName(name: String): String {
        validate(name)
        (SignalNames.pathProblem(name) ?: SignalNames.pathProblem(camel(name)))?.let { throw InvalidSignalNameException(name, it) }
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
     *
     * Where the default keeps the key as written (`on`, `class`), a name that mixes capitals with
     * a hyphen before a lower-case letter, such as `fooBar-baz`, is refused: the capitals need
     * `__case.camel` or `__case.pascal`, which turns every such hyphen into a capital too, and the
     * browser has lowercased the key, so no key and case give the name back. Where the default is
     * camel (`signals`, `bind`), that hyphen is read as camel, `fooBarBaz`, which is what
     * [reference] reads from the same name, so the two agree (`modifyCasing` in text.ts, held to
     * by the client oracle). An explicit `case` is applied as asked.
     */
    public fun key(
        name: String,
        case: Case?,
        default: Case,
    ): Pair<String, Case?> = key(name, case, default, "Write it in one case")

    /** [key], with [advice] on what to write instead of a name no key gives back. */
    internal fun key(
        name: String,
        case: Case?,
        default: Case,
        advice: String,
    ): Pair<String, Case?> {
        validate(name)
        if (!UPPER.containsMatchIn(name)) return name to case
        val key = kebab(name)
        if (case != null) return key to case
        val wanted = if (name.first().isUpperCase()) Case.PASCAL else Case.CAMEL
        if (default == Case.KEBAB && clientCase(key, wanted) != name) {
            throw InvalidSignalNameException(
                name,
                "mixes capitals with a hyphen before a lower-case letter; Datastar's client turns that hyphen " +
                    "into a capital as well, so it would read ${clientCase(key, wanted)}. $advice",
            )
        }
        return key to (if (wanted == default) null else wanted)
    }

    /**
     * [key] with the [modifiers] written after it. The client cuts the attribute name at the first
     * `__`, so a key that ends in `_` before a modifier loses that `_` to it: `b_` with
     * `__case.pascal` is `b___case.pascal`, read as the key `b` and a modifier `_case`. Such a key
     * is refused; without modifiers it is written as it is.
     */
    public fun withModifiers(
        name: String,
        key: String,
        modifiers: String,
    ): String {
        if (modifiers.isNotEmpty() && key.endsWith('_')) {
            throw InvalidSignalNameException(
                name,
                "ends in _, which runs into the __ of the modifiers after it ($key$modifiers), so Datastar's client " +
                    "would read the key as ${key.dropLast(1)}",
            )
        }
        return key + modifiers
    }

    /** [key] as Datastar's client reads it with [case] applied (`caseFns` in text.ts); kebab leaves it as it is. */
    internal fun clientCase(
        key: String,
        case: Case,
    ): String =
        when (case) {
            Case.CAMEL -> camel(key)
            Case.SNAKE -> key.replace('-', '_')
            Case.PASCAL -> key.take(1).uppercase() + camel(key.drop(1))
            Case.KEBAB -> key
        }

    /**
     * [written], the key and modifiers of an attribute that declares the signal [name], once the
     * name Datastar's client reads from it is one it can hold. The browser lowercases the key, the
     * client cuts it at the first `__` and applies each `__case` it is given, so `constructor_`
     * with `__ifmissing` is read as `constructor`, and `to-string` with `__case.camel` as
     * `toString` (`parseAttributeKey` in engine.ts, `modifyCasing` in text.ts).
     */
    public fun heldKey(
        name: String,
        written: String,
    ): String {
        val held = readKey(written)
        SignalNames.pathProblem(held)?.let { throw InvalidSignalNameException(name, "is read by Datastar's client as $held, which $it") }
        return written
    }

    /**
     * The signal the client reads from a key [written] with its modifiers, once the browser has
     * lowercased it: cut at the first `__`, and each `__case` applied in turn, camel when none is
     * (`parseAttributeKey` in engine.ts, `modifyCasing` in text.ts).
     */
    internal fun readKey(written: String): String {
        val parts = written.lowercase().split("__")
        val modifiers = parts.drop(1).map { it.split('.') }
        val cases = modifiers.lastOrNull { it.first() == "case" }?.drop(1) ?: listOf("camel")
        return cases.fold(parts.first()) { key, case ->
            Case.entries.firstOrNull { it.wire == case }?.let { clientCase(key, it) } ?: key
        }
    }

    /**
     * [prop] as the `__prop` modifier of `data-bind`: one hyphen before each capital, a leading
     * one included. The browser lowercases it and bind.ts reads it back with its own `camel`,
     * which also splits letters from digits and turns `_` into a hyphen; a name that does not
     * come back as written is refused.
     */
    internal fun bindProp(prop: String): String {
        validate(prop)
        if ('.' in prop) throw InvalidSignalNameException(prop, "contains ., where Datastar starts reading the next modifier value")
        val wire = prop.replace(UPPER) { "-" + it.value.lowercase() }
        val read = clientCamel(wire.lowercase())
        if (read != prop) {
            throw InvalidSignalNameException(prop, "is read back by Datastar's client from the __prop modifier as $read")
        }
        return wire
    }

    /** [event] as a value of the `__event` modifier of `data-bind`, which Datastar listens to as the browser hands it over. */
    internal fun bindEvent(event: String): String {
        validate(event)
        if ('.' in event) throw InvalidSignalNameException(event, "contains ., which splits it into two events in the __event modifier")
        if (UPPER.containsMatchIn(event)) {
            throw InvalidSignalNameException(
                event,
                "has capitals, which the browser lowercases in the __event modifier, so Datastar would listen to ${event.lowercase()}",
            )
        }
        return event
    }

    // `camel` in Datastar's utils/text.ts: its own `kebab`, then a capital for the character after each hyphen.
    private val CLIENT_KEBAB =
        listOf(
            Regex("([A-Z]+)([A-Z][a-z])"),
            Regex("([a-z0-9])([A-Z])"),
            Regex("([A-Za-z])([0-9]+)"),
            Regex("([0-9]+)([A-Za-z])"),
        )
    private val CLIENT_SPACE = Regex("[\\s_]+")
    private val CLIENT_HYPHEN = Regex("-.")

    internal fun clientCamel(name: String): String =
        CLIENT_KEBAB
            .fold(name) { s, re -> re.replace(s, "$1-$2") }
            .replace(CLIENT_SPACE, "-")
            .lowercase()
            .replace(CLIENT_HYPHEN) { it.value.substring(1).uppercase() }

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
