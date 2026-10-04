# Changelog

All notable changes to the Streamlord extension are recorded here.

## 0.4.0

- Ordinary attributes such as `data-id`, `data-test` and `data-href` are no longer flagged as
  misspelt Datastar attributes. A bare name is judged only when it is a long Datastar name with a
  letter swapped, such as `data-indicater`.
- Valid markup that was refused is accepted: optional end tags (`<li>a<li>b`), `__debounce.500`,
  `$foo.0.name`, HTML entities in an attribute value, and an `@` inside a string or a comment.
- A string followed by `.trimIndent()` or `.trimMargin()` is checked like any other argument, and
  a positional selector in `patchElements` is seen.
- New errors for what the Datastar client refuses at run time: `@get ('/x')` with a space, an
  attribute that needs a value and has none, and `data-bind` with both a key and a value.
- The quick fix for a hyphenated signal keeps an escaped dollar escaped.
- Both editors now run one shared corpus of cases, and agree on every one.
- A quick fix for an expression inside an HTML attribute edited the start of the tag instead of
  the expression.

## 0.3.3

- The icon fills its tile. The mark was drawn at half the width of the square and sat low in it,
  which on a marketplace listing reads as a small badge in a large dark square. Same drawing,
  scaled about its own centre and centred in the tile.

## 0.3.2

- A hyphen after a signal is read by Datastar 1.0.4 as part of the name: `$foo-bar` is
  `$['foo-bar']`, `$count-1` is `$['count-1']` and `$total-el.offsetWidth` is
  `$['total-el']['offsetWidth']`; only `$a-$b` is a subtraction. The warning now says so, covers
  `$count-1` and the scope-variable cases it excused before, and offers the spaced subtraction
  (`$count - 1`) beside the camelCase signal. The old wording, "reads as `$foo` minus `bar`",
  described a JavaScript that never ran.

## 0.3.1

- An expression that opens with `{` is read as the object literal Datastar makes of it, which
  wraps the last statement in `return (...)`: `data-signals="{count: 0, name: ''}"` no longer
  draws a syntax error. To a script parser it was a block.

## 0.3.0

- The wire hint also covers `dataBind`, `dataRef` and `dataIndicator` once a `case` is given
  (named, positional or in the trailing lambda), since the SDK then moves the name into the
  key: `dataRef("myRef", Case.CAMEL)` says `data-ref:my-ref__case.camel`.
- HTML strings that are not handed to a Streamlord call get the HTML side too: a function
  that returns markup, a `val` with a fragment. A string counts as HTML when it opens with a
  tag, a comment or a doctype, or when `@Language("HTML")` or `// language=HTML` sits just
  before it. Diagnostics for the `data-*` attributes and their expressions, completion of
  attributes, modifiers, signals and actions, hover documentation and syntax highlighting.
- Kotlin interpolation inside a `data-*` expression in an HTML string (`data-text="$count"`,
  `data-signals="{count: $initial}"`) is a warning with the `$$` and escape fixes: a server
  value there works (`data-show="true"`) as often as it is the trap; in element text it stays
  a hint. In the DSL's own expression arguments (`dataText("$count")`) it remains an error.
- The `$$` fix uses one more dollar than the longest run already in the literal, so a `$$name`
  template in a plain string becomes text under a `$$$` literal, as the fix promises.
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
  as the fix. An explicit `__case` does not excuse a capital in the key, since the browser
  lowercases it first; the fix keeps the modifier and kebab-cases the key. Hover documentation
  of every keyed attribute states its casing rule, read from `keyCase` in the catalog. In the DSL
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
