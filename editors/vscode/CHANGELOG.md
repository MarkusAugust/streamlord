# Changelog

All notable changes to the Streamlord extension are recorded here.

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
