# Changelog

All notable changes to the Streamlord plugin for IntelliJ IDEA are recorded here.

## 0.1.1

- A hyphen after a signal is read by Datastar 1.0.4 as part of the name (`$count-1` is
  `$['count-1']`, only `$a-$b` is a subtraction); the warning says so, covers the cases it
  excused before, and offers the spaced subtraction beside the camelCase signal.

## 0.1.0

The first forging. What the VS Code extension does, in IntelliJ IDEA 2026.1 and newer, Community
and Ultimate, through the shared `streamlord-analysis` module.

- Inspections for Datastar expressions and markup in Kotlin strings (handed to the DSL or
  free-standing) and in HTML and template files: the Kotlin `$` interpolation trap, expression
  syntax with the error at the right column, unknown and Pro actions, unknown attributes and
  modifiers with "did you mean", modifier arguments, keys, elements, missing ids, modes that need
  a selector, blank selectors, `</script` in scripts, capitals in keys and `$foo-bar` signals,
  and what the DSL writes on the wire. Five inspections, one per family, each with its own
  severity. A call the Kotlin plugin resolves to something other than Streamlord is left alone.
- Quick fixes on Alt+Enter for every diagnostic that has one right answer, including the `$$`
  literal rewrite of a whole string.
- Completion for signals (project-wide, this file first), actions, attributes, modifiers and
  their values, ids and classes in selector arguments; the popup opens on `$`, `@`, `#`, `:`
  and `_`. Attribute names in HTML files yield to the official Datastar plugin when it is
  installed.
- Documentation on hover for attributes, modifiers, actions and the DSL functions.
- Highlighting of Datastar tokens in expressions and of the parts of a `data-*` attribute name,
  with a colour settings page and palettes for the light and dark schemes that touch nothing
  else.
- HTML injected into Kotlin strings that open with a tag, without `@Language("HTML")`.
- Live templates for Ktor and Spring routes, the DSL calls, `htmlFunction`, and every `data-*`
  attribute, the latter generated from the catalog.
- The Stream Inspector as a tool window: live decoded SSE events with the signal store, saved
  requests in `.streamlord/inspector.json` (shared with VS Code, with its JSON schema), recent
  requests, `{{variables}}` from the settings and `.streamlord/env.json`, gutter icons on Ktor
  and Spring routes, curl export, non-SSE responses with their `datastar-*` headers.
