# Changelog

All notable changes to Streamlord are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/).

## [0.2.0] - 2026-09-27

### Added

- `streamlord-core`: `ElementsGuard`, the `$` trap hunted in HTML written as a string. It walks
  the tags, finds every `data-*` attribute whose value is an expression and runs it through
  `ExpressionGuard`; the exception names the attribute. `Streamlord(guardElements = true)`
  applies it to every element patch and elements response that leaves through that instance:
  streams, `encode(flow)`, Ktor's `respondDatastar(response)`, Spring's `datastarElements` and
  `asServerSentEvents`. Off by default. The Ktor plugin takes it as
  `install(StreamlordPlugin) { guardElements = true }`. The same guard catches a mistyped
  attribute: a `data-*` name one letter away from a Datastar attribute (`data-onn:click`,
  `data-signal:x`) throws `MistypedAttributeException` naming the attribute it resembles,
  while names further away are taken to be your own and left alone. A bare name without key
  or modifier is only judged when a letter is swapped in a long name (`data-indicater`,
  `data-computer`), so `data-test`, `data-kind`, `data-animated` and `data-effects` pass.
  `<script>` and `<style>` bodies are skipped.
  `ElementsGuard.attributes` lists every Datastar attribute name, bound to the catalog by test.
- `ExpressionGuard` accepts `[]`, `{}`, spread (`...$items`) and leading-dot decimals (`.5`),
  which it refused before as operator-only or dangling, and now catches what an eaten signal
  leaves inside a literal or argument list (`{count: }`, `{n: , m: 1}`, `[, 1]`), a missing
  left operand of `<` and a missing right operand of `+` or `-` (`'Hello, ' + `), which it let
  through before, while `$count ++` with a space, valid JavaScript, is no longer refused.
  `ElementsGuard` decodes the HTML entities kotlinx.html and template engines write
  (`&amp;&amp;`, `&lt;`, `&#x27;`) once, as the browser does, before judging a value; skips
  `<script>`, `<style>`, `<textarea>` and `<title>` bodies; and string and regex literals inside
  an expression (`':)'`, `split(/[,;]/)`, `/(?:a|b)/`) are never read as operators, which
  0.1.1's guard got wrong for `(?` and `(=`. `Streamlord(attributePrefixes = ...)` and
  `ElementsGuard.check(html, prefixes)` take a custom bundle alias, longest matched first.
  `Flow<DatastarEvent>.asSse()` takes the instance too.
- `catalog/datastar-1.0.4.json`: `keyCase` on every keyed attribute (`camel`, `kebab` or
  `raw`), the one place the casing rule is written; the catalog test binds `Casing` to it and
  the extension reads it.
- `@Language("HTML")`, `@Language("JSON")` and `@Language("JavaScript")` on every `elements`,
  `signals` and `script` parameter in core, Ktor and Spring, so IntelliJ injects the right
  language into a literal passed straight in. The annotation's artifact,
  `org.jetbrains:annotations`, is declared `compileOnly`, so nothing new reaches a consumer;
  kotlin-stdlib ships the same artifact anyway.
- README: "Three ways to write markup", covering the DSL, strings (`$$"""..."""`,
  `@Language("HTML")`) and templates as equals, with what each gets from the SDK and the editors.
- `streamlord-html`: `Casing`, the casing rules of Datastar applied so that the name you write
  is the name you get. The browser lowercases attribute names, so the keyed helpers write a
  camelCase name as the kebab-case key Datastar reads back as that name (`dataSignals("fooBar")`
  is `data-signals:foo-bar`, the signal `$fooBar`), adding `__case.camel` for `dataOn` and
  `dataClass` where Datastar's default is kebab, and `__case.pascal` for a leading capital.
  `dataAttr`, `dataStyle`, `dataAnimate` and `dataPersist` write the key in kebab-case. `signal()` and the
  other expression helpers read a kebab-case key the way Datastar names it (`signal("foo-bar")`
  is `$fooBar`). `InvalidSignalNameException` for a blank name or one with whitespace, quotes
  or a character that ends an attribute; `hover:bg-red-500`, `xlink:href` and `--brand` are
  fine as keys, and a reference is JavaScript, so `items[0].name` and `items['sub-total']`
  are fine there. An explicit `case =` is kept, with the key still in kebab-case.

### Changed

- `ExpressionGuard` and `InterpolatedExpressionException` moved from `streamlord-html` to
  `streamlord-core` (`io.github.markusaugust.streamlord.core.domain`), so the string and
  template paths can use them without the DSL. `InterpolatedExpressionException` gained an
  `attribute` property.
- `datastarElements`, `DatastarResponse.toResponseEntity` (Spring), `asServerSentEvents` and
  `DatastarEvent.toServerSentEvent` (Spring WebFlux) take an optional `streamlord` parameter,
  last, for the guard; Spring has no auto-configuration, so pass your bean. `Streamlord` gained the `guardElements`
  constructor parameter, also last. Kotlin callers recompile unchanged; Java callers of the
  full constructor pass one more argument.

### Deprecated

- The `streamlord-html` names `ExpressionGuard` and `InterpolatedExpressionException` remain as
  type aliases of the core ones.

### Fixed

An audit of the DSL against the Datastar 1.0.4 client source and bundles found these; the
protocol layer came through it unchanged.

- `streamlord-html`: `js()`, `set()`, `setAll()`, the fetch options (`headers`, `payload`) and
  the Pro actions (`clipboard()`, `intl()`) write JavaScript literals in single quotes through
  the new `JsLiteral`, as the Datastar documentation does: `@get('/x')`, `$name = 'Gorvek'`,
  `{headers: {'X-Csrf-Token': 't'}}`. The JSON they wrote before survived only the kotlinx.html
  DSL, which escapes double quotes; pasted into a double-quoted attribute in a string or a
  template, it ended the attribute. `dataSignals(vararg)` still writes JSON, which is an
  attribute value only.
- `streamlord-html`: `dataBind`, `dataRef` and `dataIndicator` wrote `__case` on the value form
  (`data-bind__case.kebab="foo"`), where Datastar ignores it, since casing applies to a key
  only. With a `case` they now write the key form (`data-bind:foo__case.kebab`), through
  `Casing.key`; without one they write the value, reading a kebab-case name as Datastar names
  the signal (`dataBind("foo-bar")` is `data-bind="fooBar"`, matching `signal("foo-bar")`).
  The `case` parameter of the object forms `dataSignals("{...}")`, `dataComputed("{...}")`
  and `dataClass("{...}")` was equally inert and is removed; the keys of the object are used
  as written.
- `streamlord-html`: `SignalFilter.regexLiteral` dropped the `RegexOption`s, so
  `Regex("user", IGNORE_CASE)` reached the browser as `/user/`. `IGNORE_CASE`, `MULTILINE`
  and `DOT_MATCHES_ALL` become the `i`, `m` and `s` flags; the options with no JavaScript
  equivalent throw `IllegalArgumentException`. A `/` that the pattern had already escaped is
  no longer escaped twice.
- `streamlord-html`: `setAll()` and `toggleAll()` write `include: /.*/` in front of a filter
  that only excludes, matching the documented signature of the two actions.
- `streamlord-html-pro`: `dataMatchMedia` wrapped the query in bare quotes; a `'` in the query
  broke the expression. It is quoted with `js()`.
- `streamlord-core`: `ExecuteScript`'s `data-effect="el.remove()"` follows the attribute
  prefix, so the aliased bundle gets `data-star-effect` and the script tags are removed as
  intended. The switch is `DatastarAttributes.prefix` in `core.protocol`, and the
  `streamlord-html` object of the same name now sets it.
- `streamlord-core`: `redirect()` navigates from a `setTimeout`, as the official SDKs do, so the
  script element is applied and removed before the page unloads.
- `streamlord-spring`: `datastarStream` and `prepareForSse` take an optional
  `HttpServletRequest`; with it, `Connection: keep-alive` is only set for HTTP/1.1, as the SDK
  specification says and HTTP/2 requires.
- Documentation: the default `filterSignals` exclusion is `/(^|\.)_/`, which also drops nested
  `_` keys such as `user._token`; and Datastar Rocket, the separate beta bundle with a
  web-component API, is stated to be outside what the DSL covers.

## [0.1.1] - 2026-09-26

### Added

- `streamlord-html`: `ExpressionGuard` and `InterpolatedExpressionException`. Every expression
  helper in `streamlord-html` and `streamlord-html-pro` now refuses, at render time, text that
  has the shape a Kotlin string template leaves behind when it interpolates a signal
  (`"$count++"` arriving as `"++"`). The message names the helpers and the `$$"..."` literal.
- README: a section on the `$` trap and the three ways to avoid it.

### Fixed

- CI: the Maven Central publish job no longer fires on `vscode-v*` tags.

## [0.1.0] - 2026-09-26

The first forging.

### Added

- `editors/vscode`: the Streamlord VS Code extension (diagnostics, completions, hover,
  highlighting, Stream Inspector) and `catalog/datastar-1.0.4.json`, the shared source of
  truth verified by `CatalogTest`.

- `streamlord-core`: Datastar 1.0.4 protocol model (`PatchElements`, `PatchSignals`,
  `ExecuteScript`), `SseEncoder` verified against the official SDK golden files, non-SSE
  `DatastarResponse` types, a dependency-free strict JSON parser and writer, the
  `DatastarStream` driving port and the `SseSink`, `SignalsCodec` and `IncomingRequest`
  driven ports, and the `Streamlord` facade with bounded signal reading.
- `streamlord-html`: kotlinx.html DSL for every Datastar attribute, modifier, action and
  expression helper; `patchElements { }` for streams, events and responses.
- `streamlord-ktor`: `respondDatastar`, `respondElements`/`respondSignals`/`respondScript`,
  `readSignals`, the `StreamlordPlugin`, and an in-process run of the official SDK
  conformance server.
- `streamlord-spring`: `datastarStream` over `StreamingResponseBody`, `readSignals` over
  `HttpServletRequest`, `ResponseEntity` helpers, `Flow<DatastarEvent>.asServerSentEvents()`
  for WebFlux, and `ElementPatchModeConverter`.
- `streamlord-json-kotlinx`, `streamlord-json-jackson` (Jackson 3, Spring Boot 4) and
  `streamlord-json-jackson2` (Jackson 2, Spring Boot 3): codec adapters for typed signals.
- `streamlord-html`: `payload` and `responseOverrides` fetch options, `dataOnFetch` with
  `FetchEventType` constants, and `DatastarAttributes.prefix` for the aliased `data-star-*` bundle.
- `streamlord-html-pro`: opt-in helpers for the attribute names and actions of Datastar Pro,
  containing no Pro code. The Spring adapter is compiled against Spring Framework 6.2 and
  tested against 6.2 and 7.0.
