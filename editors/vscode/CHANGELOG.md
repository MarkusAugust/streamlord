# Changelog

All notable changes to the Streamlord extension are recorded here.

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
