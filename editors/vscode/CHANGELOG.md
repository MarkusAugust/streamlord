# Changelog

All notable changes to the Streamlord extension are recorded here.

## Unreleased

- HTML strings that are not handed to a Streamlord call get the HTML side too: a function
  that returns markup, a `val` with a fragment. A string counts as HTML when it opens with a
  tag, a comment or a doctype, or when `@Language("HTML")` or `// language=HTML` sits just
  before it. Diagnostics for the `data-*` attributes and their expressions, completion of
  attributes, modifiers, signals and actions, hover documentation and syntax highlighting.
- Kotlin interpolation inside a `data-*` expression in an HTML string (`data-text="$count"`)
  is an error with the escape as quick fix; in element text it stays a hint.
- New quick fix on every `$` interpolation trap: "Make it a `$$` literal", which rewrites the
  whole literal so the flagged `$name` stays a signal, every other template becomes `$$name`
  and stays Kotlin, and `${'$'}` becomes a plain dollar. Offered right after the DSL helper.
- Multi-dollar literals (`$$"..."`, `$$"""..."""`, Kotlin 2.2+) are read as Kotlin reads them:
  a single `$` is text, only `$$name` is a template. No interpolation diagnostics there, since
  there is no trap; the expression, markup and attribute checks, completion and highlighting
  apply as in any other string, including as an argument of the DSL calls.
- Signal names declared in HTML strings in Kotlin (`data-signals:draft`, `data-bind="search"`)
  feed signal completion.
- Template languages of the JVM: JTE and kte, FreeMarker, Velocity and Mustache are on the
  default language list, and their syntax is understood: `!{...}`, `@if`/`@for`, `#if`/
  `#foreach`, `<#if>`, `</#list>`, `<@macro>`, `<#-- -->`, `<%-- --%>`, `[#if]`, `[=x]`, and
  Thymeleaf's `*{...}`, `#{...}` and `@{...}`.
- Snippet `htmlFunction`: a function returning HTML as a `$$"""..."""` string under
  `@Language("HTML")`.
- Casing. A capital letter in an attribute key (`data-signals:fooBar`, `data-on:widgetLoaded`,
  `data-class:isOpen`, `data-attr:ariaLabel`) is a warning, since the browser lowercases it
  and Datastar would name the signal `$foobar`; the quick fix writes the kebab-case key that
  comes back as the intended name, with `__case.camel` or `__case.pascal` where needed. A
  `$foo-bar` in an expression, which reads as `$foo` minus `bar`, is a warning with `$fooBar`
  as the fix. Hover documentation of every keyed attribute states its casing rule. In the DSL
  the SDK writes a camelCase key correctly by itself; a hint on the call says what goes on the
  wire (`dataSignals("fooBar", ...)` is `data-signals:foo-bar`), so nothing is hidden and the
  rule is learned where it applies.

## 0.2.0

- Quick fixes for the diagnostics that have one right answer: Kotlin `$` interpolation (DSL
  helper or escape), unknown modifiers, actions and attributes, missing duration, missing `id`,
  missing `selector`.
- Every diagnostic links to the Datastar reference.
- Snippets for Kotlin (routes and DSL calls) and HTML (every `data-*` attribute), the HTML
  ones generated from the catalog.
- HTML custom data: Datastar attributes complete and hover through VS Code's own HTML service.
- The HTML side runs in template languages too, configurable with `streamlord.languages`.

## 0.1.0

The first forging.

- Diagnostics for Datastar expressions and markup in Kotlin DSL strings and `.html` templates:
  Kotlin `$` interpolation traps, expression syntax, unknown actions and modifiers, missing ids,
  modes that need a selector.
- Completions for signals, actions, attributes, modifiers, ids and classes; hover documentation.
- Syntax highlighting of Datastar expressions and HTML inside DSL strings and of `data-*`
  attributes in HTML, with recommended dark and light palettes for Datastar tokens only.
- Stream Inspector: live decoded SSE events with the signal store, saved requests in
  `.streamlord/inspector.json`, recent requests, `{{variables}}`, route code lenses for Ktor
  and Spring, and curl export.
