# Changelog

All notable changes to the Streamlord plugin for IntelliJ IDEA are recorded here.

## Unreleased

- Stream Inspector: the request goes out as the curl line shows it. The signals are no longer
  written anew (`\u00e9` was sent as `é` and `</` as `<\/`), and a header the HTTP client will
  not set, such as `Host`, is reported as such instead of as an invalid URL. A URL that cannot be
  sent as written, such as a relative one, one that still holds `{id}` or one with a space, is
  refused with the reason and the way to write it, in the same words in both editors.
- `patchElements("#rows") { li { } }`, the kotlinx.html form, had its selector read as markup
  and flagged as text outside an element, and a mode without a selector went unreported. With a
  trailing lambda the first string is the selector and the second argument the mode.
- Spring routes: a method took the `@RequestMapping` prefix of the class before it in the
  file. A class without `@RequestMapping` now has no prefix, a nested class keeps its mapping to
  itself, and a class mapping is no longer reported as a route when another annotation with
  parentheses in a string, such as `@PreAuthorize("hasRole('ADMIN')")`, stands before the class.

## 0.2.0

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
- The JavaScript check is a port of acorn, the parser the VS Code extension uses, so the two
  editors give the same verdict on an expression.

## 0.1.1

- The plugin icon fills its tile. The mark was drawn at half the width of the square and sat low
  in it, which at the size a marketplace listing shows it reads as a small badge in a large dark
  square. Same drawing, scaled about its own centre and centred in the tile.
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
