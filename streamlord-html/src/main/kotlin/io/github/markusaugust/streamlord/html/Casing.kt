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
 * The casing rules of Datastar, applied so that a signal goes by the name you write.
 *
 * The browser lowercases every attribute name, so `data-signals:fooBar` reaches Datastar as
 * `foobar`. Datastar then reads keys of the signal attributes (`signals`, `computed`, `bind`,
 * `ref`, `indicator`, `match-media`) in camel case, turning `foo-bar` into `fooBar`; keys of
 * `on` and `class` stay kebab unless `__case` says otherwise; keys of `attr`, `style` and
 * `animate` are used as they are. The catalog records the default per attribute as `keyCase`,
 * and the catalog test holds this object to it.
 *
 * A signal has one name, in Kotlin and in the browser: `dataSignals("fooBar", "1")` is written
 * `data-signals:foo-bar`, which the client reads back as `fooBar`, and `signal("fooBar")` is
 * `$fooBar`. A signal name may not hold a hyphen ([SignalNames.hyphenProblem]): it is the one
 * character the client reads as a capital in an attribute key and as itself in an expression or
 * a patch, so `foo-bar` would name two signals. Every name without one comes back as written.
 *
 * An event or class name is not a signal and keeps its `case`: `dataOn("widgetLoaded", ...)` is
 * `widget-loaded__case.camel`, and `dataClass("isOpen", ...)` toggles `isOpen`.
 *
 * A key with a hyphen is still data: inside a value, `"user" to mapOf("first-name" to "")`, in the
 * object form, `dataSignals("{'foo-bar': 1}")`, or from a codec. An expression reaches it with
 * brackets, `signal("user['first-name']")`.
 */
public object Casing {
    private val UPPER = Regex("[A-Z]")
    private val KEBAB = Regex("-([a-z])")

    // Java's \s, the spaces JavaScript's \s adds (class.ts splits a class list on it), NUL, which
    // the HTML parser turns into U+FFFD, and the characters that end an attribute or its name.
    private val FORBIDDEN = Regex("[\\s\\x00\\xa0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff\"'<>=/]")

    // What Datastar's client finds behind a `$`, less the hyphen a signal name may not hold: ASCII
    // word characters, in parts joined by `.`.
    private val SIGNAL_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

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
     * A name that declares a signal: what [validate] admits, without a hyphen
     * ([SignalNames.hyphenProblem]), and only ASCII letters, digits and `_`, in parts joined by `.`.
     * Datastar's client reads nothing else after a `$`, so `$über` or `$a:b` would reach no signal.
     * No part may be a property every object has, such as `constructor` or `toString` ([SignalNames]).
     */
    public fun signalName(name: String): String {
        validate(name)
        (SignalNames.hyphenProblem(name) ?: SignalNames.pathProblem(name))?.let { throw InvalidSignalNameException(name, it) }
        if (!SIGNAL_NAME.matches(name)) {
            throw InvalidSignalNameException(
                name,
                "is not a name a \$ reference reaches: Datastar reads ASCII letters, digits and _, in parts joined by .",
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
     * camel (`signals`, `bind`), the name holds no hyphen, since [signalName] refuses one before
     * it gets here, and every such name comes back as written (`modifyCasing` in text.ts, held to
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

    /** The key for an attribute whose key is used as written (`style`, `persist`): `backgroundColor` -> `background-color`. */
    public fun plainKey(name: String): String = kebab(validate(name))

    /**
     * The key of `data-attr` and `data-animate`, the name of an HTML attribute as HTML has it. The
     * browser lowercases an attribute name, so a capital could only reach another attribute, such
     * as `view-box` for SVG's `viewBox`, and is refused with the two forms that work. [helper] is
     * the DSL function the message names.
     */
    public fun attributeKey(
        name: String,
        helper: String,
    ): String {
        validate(name)
        if (UPPER.containsMatchIn(name)) {
            throw InvalidSignalNameException(
                name,
                "has a capital, which the browser lowercases in an attribute name; write it as HTML has it, " +
                    "${attributeSpellings(name).joinToString(" or ")}, or, for an attribute that has capitals such as SVG's " +
                    "viewBox, the object form $helper(\"${attributeObjectForm(name)}\")",
            )
        }
        return name
    }

    /**
     * The spellings HTML may have for an attribute name written with capitals: lowercased when it
     * has a hyphen already (`aria-Label`), else kebab-case or one word, which only the attribute
     * tells apart (`ariaLabel` is `aria-label`, `tabIndex` is `tabindex`).
     */
    internal fun attributeSpellings(name: String): List<String> =
        if ('-' in name) listOf(name.lowercase()) else listOf(kebab(name), name.lowercase()).distinct()

    /** The object form with [name] as its key, quoted when it is no JavaScript name. */
    internal fun attributeObjectForm(name: String): String =
        if (Regex("[A-Za-z_$][A-Za-z0-9_$]*").matches(name)) "{$name: ...}" else "{'$name': ...}"

    /**
     * [name] as an expression refers to it, which is as written: `form.firstName`, `items[0]`, and
     * `['sub-total']` or `form['first-name']` for a key with a hyphen. A reference is JavaScript,
     * so only blank names, whitespace, a first character Datastar does not read after a `$`, and a
     * hyphen the client reads in a signal name or as a minus are refused: `$foo-bar` would read a
     * signal no name declares, and `items[$i-1]` the signal `i-1` ([SignalNames.withoutReferenceHyphens]). A
     * hyphen before a digit or a `$` looks like a subtraction, and the advice says to write one in
     * the expression ([SignalNames.referenceAsSubtraction]).
     */
    public fun reference(name: String): String {
        if (name.isBlank()) throw InvalidSignalNameException(name, "is blank")
        if (name.any { it.isWhitespace() }) throw InvalidSignalNameException(name, "contains whitespace")
        val first = name.first()
        if (first != '[' && first != '_' && first !in 'A'..'Z' && first !in 'a'..'z' && first !in '0'..'9') {
            throw InvalidSignalNameException(name, "starts with a character Datastar does not read after \$")
        }
        SignalNames.referenceAsSubtraction(name)?.let { expression ->
            throw InvalidSignalNameException(
                name,
                "has a hyphen that looks like a subtraction, which a signal name may not hold; write it in the expression: $expression",
            )
        }
        SignalNames.withoutReferenceHyphens(name)?.let { fixed ->
            throw InvalidSignalNameException(
                name,
                "has a hyphen the client reads as part of a signal name or as a minus, which a signal name may not hold; write $fixed" +
                    (bracketed(name)?.let { ", or $it for a key with a hyphen in it" } ?: ""),
            )
        }
        return name
    }

    /**
     * [name] with its one hyphenated part in brackets, `form.first-name` as `form['first-name']`,
     * or null when that is no simple rewrite: a name with brackets already, or more than one such part.
     */
    private fun bracketed(name: String): String? {
        if ('[' in name || ']' in name) return null
        val parts = name.split('.')
        val i = parts.indexOfFirst { '-' in it }
        if (i < 0 || parts.count { '-' in it } > 1) return null
        return parts.take(i).joinToString(".") + "['" + parts[i] + "']" + parts.drop(i + 1).joinToString("") { ".$it" }
    }
}
