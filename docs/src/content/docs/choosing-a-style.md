---
title: "Choosing a style"
description: "Three ways to write markup, served as equals, and what each one costs."
---

The DSL is one way, not the way. Everywhere Streamlord takes HTML it takes a `String`, so a
function returning a multi-line literal or a template engine rendering to one is as much a
first-class citizen as `div { }`.

Pick what your team reads best. The SDK and both editor extensions serve all three.

## What each gets

| | DSL | Strings | Templates |
|---|---|---|---|
| Passed to the SDK as | `div { }` blocks | `String` | `String` |
| The `$` trap | caught by the helpers | `$$"""` avoids it, `guardElements` catches it | none |
| Type safety of the markup | full | none | none |
| Refactoring | renames follow | text search | text search |
| Designers can edit it | no | awkwardly | yes |
| IntelliJ | Kotlin plugin: completion, KDoc, types. Streamlord plugin: expression diagnostics, hover, the wire hint | Streamlord plugin: markup and attribute diagnostics, completion, hover, HTML injection | the same, per template plugin |
| VS Code | expression diagnostics, completion, hover | markup and attribute diagnostics, completion, hover, highlighting | the same, per language |

## How to choose

**Take the DSL** when the markup is small, generated, or close to the data: a list of rows, a
fragment that differs by a field, anything a loop builds. Type safety is worth most exactly where
markup and logic are entangled, and that is where the DSL is pleasant.

**Take strings** when the markup is a page-shaped thing that someone will want to read as HTML.
A forty-line layout expressed as nested builder calls is harder to see than the same forty lines
of HTML, whatever the type system says.

**Take templates** when someone who does not write Kotlin has to edit the markup, or when you
already have an engine and a library of partials. There is no trap to avoid and nothing to
integrate.

## Mixing them

Nothing stops you, and most projects end up mixed: templates for pages, the DSL for the fragments
a stream patches in. They meet at `patchElements`, which does not care which one produced the
string.

The one thing worth being consistent about is *within a single response*. A patch built half from
a template and half from builder calls is hard to read in either language.
