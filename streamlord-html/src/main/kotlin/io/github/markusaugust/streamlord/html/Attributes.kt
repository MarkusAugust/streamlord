@file:Suppress("TooManyFunctions")

package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.domain.ExpressionGuard
import io.github.markusaugust.streamlord.core.json.JsonWriter
import kotlinx.html.HTMLTag

/**
 * The prefix every Datastar attribute starts with.
 *
 * The standard bundle reads `data-*`. The aliased bundle (`datastar-aliased.js`), built for
 * pages where another library already claims those names, reads `data-star-*` instead. Set
 * [prefix] once at startup to match the bundle you load; every helper in this module and in
 * `streamlord-html-pro` honours it.
 */
public object DatastarAttributes {
    /** `"data-"` for the standard bundle, `"data-star-"` for the aliased one. */
    @Volatile
    public var prefix: String = "data-"

    /** The full attribute name for a Datastar attribute such as `on:click__once`. */
    public fun name(suffix: String): String = prefix + suffix
}

private fun ds(suffix: String): String = DatastarAttributes.name(suffix)

/*
 * The `data-*` attributes of Datastar 1.0.4 as kotlinx.html extension functions.
 *
 * Every function writes exactly one attribute with the exact key Datastar parses, modifiers
 * included. kotlinx.html escapes the values for the attribute context.
 *
 * Every expression passes [ExpressionGuard] first. `$` starts a template in Kotlin strings, so
 * `"$count++"` reaches the DSL as `"++"`; the guard throws [InterpolatedExpressionException]
 * at render time instead of letting the browser ignore a broken attribute. Write signals with
 * the helpers in Expressions.kt, or with a `$$"..."` string literal (Kotlin 2.2+), where a
 * single dollar is just a dollar.
 */

// ---- Signals ----------------------------------------------------------------------------------

/** `data-signals="{...}"` from a JavaScript object expression. */
public fun HTMLTag.dataSignals(expression: String, case: Case? = null, ifMissing: Boolean = false) {
    attributes[ds("signals${signalMods(case, ifMissing)}")] = ExpressionGuard.check(expression)
}

/**
 * `data-signals:name="expression"`. Dotted names create nested signals. A camelCase [name] is
 * written in kebab-case, which Datastar reads back as the same camelCase signal; see [Casing].
 */
public fun HTMLTag.dataSignals(name: String, expression: String, case: Case? = null, ifMissing: Boolean = false) {
    val (key, mod) = Casing.key(name, case, Case.CAMEL)
    attributes[ds("signals:$key${signalMods(mod, ifMissing)}")] = ExpressionGuard.check(expression)
}

/**
 * `data-signals` from Kotlin values, serialised with the built-in JSON writer.
 *
 * ```kotlin
 * div { dataSignals("count" to 0, "user" to mapOf("name" to "")) }
 * ```
 */
public fun HTMLTag.dataSignals(vararg signals: Pair<String, Any?>, case: Case? = null, ifMissing: Boolean = false) {
    attributes[ds("signals${signalMods(case, ifMissing)}")] = JsonWriter.write(signals.toMap())
}

/** `data-computed="{...}"`. */
public fun HTMLTag.dataComputed(expression: String, case: Case? = null) {
    attributes[ds("computed${caseMod(case)}")] = ExpressionGuard.check(expression)
}

/** `data-computed:name="expression"`. */
public fun HTMLTag.dataComputed(name: String, expression: String, case: Case? = null) {
    val (key, mod) = Casing.key(name, case, Case.CAMEL)
    attributes[ds("computed:$key${caseMod(mod)}")] = ExpressionGuard.check(expression)
}

/** `data-json-signals`: renders the signal store as JSON into the element. Handy while debugging. */
public fun HTMLTag.dataJsonSignals(filter: SignalFilter? = null, terse: Boolean = false) {
    attributes[ds(if (terse) "json-signals__terse" else "json-signals")] = filter?.toJs() ?: ""
}

// ---- Lifecycle --------------------------------------------------------------------------------

/** `data-init="expression"`, run once when the element enters the DOM. */
public fun HTMLTag.dataInit(expression: String, modifiers: InitModifiers.() -> Unit = {}) {
    attributes[ds("init${InitModifiers().apply(modifiers).build()}")] = ExpressionGuard.check(expression)
}

/** `data-effect="expression"`, re-run whenever a signal it reads changes. */
public fun HTMLTag.dataEffect(expression: String) {
    attributes[ds("effect")] = ExpressionGuard.check(expression)
}

// ---- Events -----------------------------------------------------------------------------------

/**
 * `data-on:event="expression"` with optional modifiers. A camelCase [event] such as
 * `widgetLoaded` is written as `widget-loaded__case.camel`, which is how Datastar listens to
 * it; see [Casing].
 */
public fun HTMLTag.dataOn(event: String, expression: String, modifiers: OnModifiers.() -> Unit = {}) {
    val mods = OnModifiers().apply(modifiers)
    val (key, mod) = Casing.key(event, mods.case, Case.KEBAB)
    mods.case = mod
    attributes[ds("on:$key${mods.build()}")] = ExpressionGuard.check(expression)
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

/**
 * `data-on:datastar-fetch="expression"`: react to fetch lifecycle events. Inside the expression,
 * `evt.detail.type` is one of [FetchEventType].
 */
public fun HTMLTag.dataOnFetch(expression: String, modifiers: OnModifiers.() -> Unit = {}): Unit =
    dataOn("datastar-fetch", expression, modifiers)

/** The `evt.detail.type` values of the `datastar-fetch` event. */
public object FetchEventType {
    public const val STARTED: String = "started"
    public const val FINISHED: String = "finished"
    public const val ERROR: String = "error"
    public const val RETRYING: String = "retrying"
    public const val RETRIES_FAILED: String = "retries-failed"
}

/** `data-on-signal-patch`, with the optional `data-on-signal-patch-filter` companion. */
public fun HTMLTag.dataOnSignalPatch(
    expression: String,
    filter: SignalFilter? = null,
    modifiers: TimingModifiers.() -> Unit = {},
) {
    attributes[ds("on-signal-patch${TimingModifiers().apply(modifiers).build()}")] = ExpressionGuard.check(expression)
    filter?.let { attributes[ds("on-signal-patch-filter")] = it.toJs() }
}

/** `data-on-interval`. */
public fun HTMLTag.dataOnInterval(expression: String, modifiers: IntervalModifiers.() -> Unit = {}) {
    attributes[ds("on-interval${IntervalModifiers().apply(modifiers).build()}")] = ExpressionGuard.check(expression)
}

/** `data-on-intersect`. */
public fun HTMLTag.dataOnIntersect(expression: String, modifiers: IntersectModifiers.() -> Unit = {}) {
    attributes[ds("on-intersect${IntersectModifiers().apply(modifiers).build()}")] = ExpressionGuard.check(expression)
}

// ---- Binding and references -------------------------------------------------------------------

/** `data-bind="signal"`: two-way binding between an input and a signal. */
public fun HTMLTag.dataBind(signal: String, modifiers: BindModifiers.() -> Unit = {}) {
    attributes[ds("bind${BindModifiers().apply(modifiers).build()}")] = signal
}

/** `data-ref="name"`: expose the element as a signal. */
public fun HTMLTag.dataRef(name: String, case: Case? = null) {
    attributes[ds("ref${caseMod(case)}")] = name
}

/** `data-indicator="signal"`: a boolean signal that is `true` while a fetch is in flight. */
public fun HTMLTag.dataIndicator(signal: String, case: Case? = null) {
    attributes[ds("indicator${caseMod(case)}")] = signal
}

// ---- Display ----------------------------------------------------------------------------------

public fun HTMLTag.dataText(expression: String) {
    attributes[ds("text")] = ExpressionGuard.check(expression)
}

public fun HTMLTag.dataShow(expression: String) {
    attributes[ds("show")] = ExpressionGuard.check(expression)
}

/** `data-class="{name: expression}"`. */
public fun HTMLTag.dataClass(expression: String, case: Case? = null) {
    attributes[ds("class${caseMod(case)}")] = ExpressionGuard.check(expression)
}

/** `data-class:name="expression"`. A camelCase [name] is written as `is-open__case.camel`, so the class stays `isOpen`; see [Casing]. */
public fun HTMLTag.dataClass(name: String, expression: String, case: Case? = null) {
    val (key, mod) = Casing.key(name, case, Case.KEBAB)
    attributes[ds("class:$key${caseMod(mod)}")] = ExpressionGuard.check(expression)
}

/** `data-style="{property: expression}"`. */
public fun HTMLTag.dataStyle(expression: String) {
    attributes[ds("style")] = ExpressionGuard.check(expression)
}

/** `data-style:property="expression"`. A camelCase [property] such as `backgroundColor` is written as `background-color`. */
public fun HTMLTag.dataStyle(property: String, expression: String) {
    attributes[ds("style:${Casing.plainKey(property)}")] = ExpressionGuard.check(expression)
}

/** `data-attr="{name: expression}"`. */
public fun HTMLTag.dataAttr(expression: String) {
    attributes[ds("attr")] = ExpressionGuard.check(expression)
}

/**
 * `data-attr:name="expression"`. A camelCase [name] such as `ariaLabel` is written as
 * `aria-label`. An attribute that really is camelCase (SVG's `viewBox`) cannot travel in a key,
 * since the browser lowercases it; use the object form, `dataAttr("{viewBox: ...}")`.
 */
public fun HTMLTag.dataAttr(name: String, expression: String) {
    attributes[ds("attr:${Casing.plainKey(name)}")] = ExpressionGuard.check(expression)
}

// ---- Morphing and walker control --------------------------------------------------------------

/** `data-ignore`: Datastar leaves this subtree alone. `self` limits it to the element itself. */
public fun HTMLTag.dataIgnore(self: Boolean = false) {
    attributes[ds(if (self) "ignore__self" else "ignore")] = ""
}

/** `data-ignore-morph`: this element is never morphed. */
public fun HTMLTag.dataIgnoreMorph() {
    attributes[ds("ignore-morph")] = ""
}

/** `data-preserve-attr="open class"`: keep these attributes through a morph. */
public fun HTMLTag.dataPreserveAttr(vararg names: String) {
    attributes[ds("preserve-attr")] = names.joinToString(" ")
}

/**
 * `data-nonce="..."` on the `<html>` element switches the Datastar client into CSP mode:
 * expressions are compiled through nonce-bearing script elements instead of `eval`.
 */
public fun HTMLTag.dataNonce(nonce: String) {
    attributes[ds("nonce")] = nonce
}

// ---- helpers ----------------------------------------------------------------------------------

private fun caseMod(case: Case?): String = case?.let { "__case.${it.wire}" } ?: ""

private fun signalMods(case: Case?, ifMissing: Boolean): String = buildString {
    case?.let { append("__case.").append(it.wire) }
    if (ifMissing) append("__ifmissing")
}
