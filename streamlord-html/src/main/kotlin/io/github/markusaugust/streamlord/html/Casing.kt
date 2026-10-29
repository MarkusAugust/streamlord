package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.StreamlordException
import io.github.markusaugust.streamlord.core.client.ClientCasing
import io.github.markusaugust.streamlord.core.domain.AttributeKeys
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

    /** `fooBar` -> `foo-bar`, `myURL` -> `my-u-r-l`: one hyphen per capital, so Datastar's camel conversion gives the name back. */
    public fun kebab(name: String): String = ClientCasing.kebab(name)

    /** Datastar's own reading of a key: `-x` becomes `X`. */
    public fun camel(name: String): String = ClientCasing.camel(name)

    /** [name], if it can be a key or a signal name ([AttributeKeys.nameProblem]); else it throws [InvalidSignalNameException]. */
    public fun validate(name: String): String {
        AttributeKeys.nameProblem(name)?.let { throw InvalidSignalNameException(name, it) }
        return name
    }

    /**
     * [name], if it can declare a signal ([SignalNames.nameProblem]): what [validate] admits,
     * without a hyphen, and only ASCII letters, digits and `_`, in parts joined by `.`, none of them
     * a property every object has, such as `constructor`. Else it throws [InvalidSignalNameException].
     */
    public fun signalName(name: String): String {
        SignalNames.nameProblem(name)?.let { throw InvalidSignalNameException(name, it) }
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
        val choice =
            ClientCasing.keyFor(name, case?.wire, default.wire) ?: throw InvalidSignalNameException(
                name,
                "mixes capitals with a hyphen before a lower-case letter; Datastar's client turns that hyphen " +
                    "into a capital as well, so it would read ${ClientCasing.readsAs(name)}. $advice",
            )
        return choice.key to Case.entries.firstOrNull { it.wire == choice.case }
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
        AttributeKeys.runsIntoModifiers(key, modifiers)?.let { throw InvalidSignalNameException(name, it) }
        return key + modifiers
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
        val held = ClientCasing.readKey(written)
        SignalNames.pathProblem(held)?.let { throw InvalidSignalNameException(name, "is read by Datastar's client as $held, which $it") }
        return written
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
        val read = ClientCasing.textCamel(wire.lowercase())
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
        AttributeKeys.capitalIn(name, helper)?.let { throw InvalidSignalNameException(name, it) }
        return name
    }

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
