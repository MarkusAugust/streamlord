@file:Suppress("TooManyFunctions")

package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.json.JsonWriter
import kotlinx.html.HTMLTag

/*
 * The `data-*` attributes of Datastar 1.0.4 as kotlinx.html extension functions.
 *
 * Every function writes exactly one attribute with the exact key Datastar parses, modifiers
 * included. Expressions are passed through untouched; kotlinx.html escapes them for the
 * attribute context. Remember that `$` starts a template in Kotlin strings: write signal
 * references as `"${'$'}count"` or, far nicer, with the helpers in Expressions.kt.
 */

// ---- Signals ----------------------------------------------------------------------------------

/** `data-signals="{...}"` from a JavaScript object expression. */
public fun HTMLTag.dataSignals(expression: String, case: Case? = null, ifMissing: Boolean = false) {
    attributes["data-signals${signalMods(case, ifMissing)}"] = expression
}

/** `data-signals:name="expression"`. Dotted names create nested signals. */
public fun HTMLTag.dataSignals(name: String, expression: String, case: Case? = null, ifMissing: Boolean = false) {
    attributes["data-signals:$name${signalMods(case, ifMissing)}"] = expression
}

/**
 * `data-signals` from Kotlin values, serialised with the built-in JSON writer.
 *
 * ```kotlin
 * div { dataSignals("count" to 0, "user" to mapOf("name" to "")) }
 * ```
 */
public fun HTMLTag.dataSignals(vararg signals: Pair<String, Any?>, case: Case? = null, ifMissing: Boolean = false) {
    attributes["data-signals${signalMods(case, ifMissing)}"] = JsonWriter.write(signals.toMap())
}

/** `data-computed="{...}"`. */
public fun HTMLTag.dataComputed(expression: String, case: Case? = null) {
    attributes["data-computed${caseMod(case)}"] = expression
}

/** `data-computed:name="expression"`. */
public fun HTMLTag.dataComputed(name: String, expression: String, case: Case? = null) {
    attributes["data-computed:$name${caseMod(case)}"] = expression
}

/** `data-json-signals`: renders the signal store as JSON into the element. Handy while debugging. */
public fun HTMLTag.dataJsonSignals(filter: SignalFilter? = null, terse: Boolean = false) {
    attributes[if (terse) "data-json-signals__terse" else "data-json-signals"] = filter?.toJs() ?: ""
}

// ---- Lifecycle --------------------------------------------------------------------------------

/** `data-init="expression"`, run once when the element enters the DOM. */
public fun HTMLTag.dataInit(expression: String, modifiers: InitModifiers.() -> Unit = {}) {
    attributes["data-init${InitModifiers().apply(modifiers).build()}"] = expression
}

/** `data-effect="expression"`, re-run whenever a signal it reads changes. */
public fun HTMLTag.dataEffect(expression: String) {
    attributes["data-effect"] = expression
}

// ---- Events -----------------------------------------------------------------------------------

/** `data-on:event="expression"` with optional modifiers. */
public fun HTMLTag.dataOn(event: String, expression: String, modifiers: OnModifiers.() -> Unit = {}) {
    attributes["data-on:$event${OnModifiers().apply(modifiers).build()}"] = expression
}

public fun HTMLTag.dataOnClick(expression: String, modifiers: OnModifiers.() -> Unit = {}): Unit =
    dataOn("click", expression, modifiers)

public fun HTMLTag.dataOnSubmit(expression: String, modifiers: OnModifiers.() -> Unit = {}): Unit =
    dataOn("submit", expression, modifiers)

public fun HTMLTag.dataOnChange(expression: String, modifiers: OnModifiers.() -> Unit = {}): Unit =
    dataOn("change", expression, modifiers)

public fun HTMLTag.dataOnInput(expression: String, modifiers: OnModifiers.() -> Unit = {}): Unit =
    dataOn("input", expression, modifiers)

public fun HTMLTag.dataOnKeydown(expression: String, modifiers: OnModifiers.() -> Unit = {}): Unit =
    dataOn("keydown", expression, modifiers)

/** `data-on-signal-patch`, with the optional `data-on-signal-patch-filter` companion. */
public fun HTMLTag.dataOnSignalPatch(
    expression: String,
    filter: SignalFilter? = null,
    modifiers: TimingModifiers.() -> Unit = {},
) {
    attributes["data-on-signal-patch${TimingModifiers().apply(modifiers).build()}"] = expression
    filter?.let { attributes["data-on-signal-patch-filter"] = it.toJs() }
}

/** `data-on-interval`. */
public fun HTMLTag.dataOnInterval(expression: String, modifiers: IntervalModifiers.() -> Unit = {}) {
    attributes["data-on-interval${IntervalModifiers().apply(modifiers).build()}"] = expression
}

/** `data-on-intersect`. */
public fun HTMLTag.dataOnIntersect(expression: String, modifiers: IntersectModifiers.() -> Unit = {}) {
    attributes["data-on-intersect${IntersectModifiers().apply(modifiers).build()}"] = expression
}

// ---- Binding and references -------------------------------------------------------------------

/** `data-bind="signal"`: two-way binding between an input and a signal. */
public fun HTMLTag.dataBind(signal: String, modifiers: BindModifiers.() -> Unit = {}) {
    attributes["data-bind${BindModifiers().apply(modifiers).build()}"] = signal
}

/** `data-ref="name"`: expose the element as a signal. */
public fun HTMLTag.dataRef(name: String, case: Case? = null) {
    attributes["data-ref${caseMod(case)}"] = name
}

/** `data-indicator="signal"`: a boolean signal that is `true` while a fetch is in flight. */
public fun HTMLTag.dataIndicator(signal: String, case: Case? = null) {
    attributes["data-indicator${caseMod(case)}"] = signal
}

// ---- Display ----------------------------------------------------------------------------------

public fun HTMLTag.dataText(expression: String) {
    attributes["data-text"] = expression
}

public fun HTMLTag.dataShow(expression: String) {
    attributes["data-show"] = expression
}

/** `data-class="{name: expression}"`. */
public fun HTMLTag.dataClass(expression: String, case: Case? = null) {
    attributes["data-class${caseMod(case)}"] = expression
}

/** `data-class:name="expression"`. */
public fun HTMLTag.dataClass(name: String, expression: String, case: Case? = null) {
    attributes["data-class:$name${caseMod(case)}"] = expression
}

/** `data-style="{property: expression}"`. */
public fun HTMLTag.dataStyle(expression: String) {
    attributes["data-style"] = expression
}

/** `data-style:property="expression"`. */
public fun HTMLTag.dataStyle(property: String, expression: String) {
    attributes["data-style:$property"] = expression
}

/** `data-attr="{name: expression}"`. */
public fun HTMLTag.dataAttr(expression: String) {
    attributes["data-attr"] = expression
}

/** `data-attr:name="expression"`. */
public fun HTMLTag.dataAttr(name: String, expression: String) {
    attributes["data-attr:$name"] = expression
}

// ---- Morphing and walker control --------------------------------------------------------------

/** `data-ignore`: Datastar leaves this subtree alone. `self` limits it to the element itself. */
public fun HTMLTag.dataIgnore(self: Boolean = false) {
    attributes[if (self) "data-ignore__self" else "data-ignore"] = ""
}

/** `data-ignore-morph`: this element is never morphed. */
public fun HTMLTag.dataIgnoreMorph() {
    attributes["data-ignore-morph"] = ""
}

/** `data-preserve-attr="open class"`: keep these attributes through a morph. */
public fun HTMLTag.dataPreserveAttr(vararg names: String) {
    attributes["data-preserve-attr"] = names.joinToString(" ")
}

/**
 * `data-nonce="..."` on the `<html>` element switches the Datastar client into CSP mode:
 * expressions are compiled through nonce-bearing script elements instead of `eval`.
 */
public fun HTMLTag.dataNonce(nonce: String) {
    attributes["data-nonce"] = nonce
}

// ---- helpers ----------------------------------------------------------------------------------

private fun caseMod(case: Case?): String = case?.let { "__case.${it.wire}" } ?: ""

private fun signalMods(case: Case?, ifMissing: Boolean): String = buildString {
    case?.let { append("__case.").append(it.wire) }
    if (ifMissing) append("__ifmissing")
}
