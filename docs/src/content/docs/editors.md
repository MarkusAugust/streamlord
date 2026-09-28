---
title: "The editors"
description: "A VS Code extension and an IntelliJ plugin that read Datastar as a language."
---

Datastar lives inside attribute values and inside Kotlin strings — two places where no editor
checks anything on its own. That is what these are for.

| | Where |
|---|---|
| VS Code | Marketplace, `MarkusAugust.streamlord` |
| IntelliJ IDEA 2026.1+ | JetBrains Marketplace, `io.github.markusaugust.streamlord` |

Community and Ultimate both work.

## What they see

**Diagnostics with quick fixes** for Datastar expressions and markup — inside Kotlin strings,
whether handed to the DSL or standing free, and in HTML and template files. Kotlin `$`
interpolation traps, syntax errors with the right column, missing ids, unknown attributes and
modifiers, capitals in keys.

**Completions** for signals, actions, attributes, modifiers, ids and classes, collected from the
file you are in.

**Hover documentation**, snippets, and syntax highlighting of the Datastar tokens only — never
of your Kotlin or your HTML, which belong to your theme.

**A Stream Inspector** that shows a live SSE stream decoded rather than as raw text, with saved
requests in `.streamlord/inspector.json` that the two editors share.

**Route code lenses** in VS Code, gutter icons in IntelliJ.

The JVM template languages are covered: JTE, kte, FreeMarker, Velocity, Mustache and Pebble.
Thymeleaf is plain HTML and needs nothing special.

## One implementation, two editors

Everything they know comes from `catalog/datastar-1.0.4.json`, which the SDK's own tests bind to
the DSL — so the catalog cannot drift from the library without a test failing.

The checks live once, in `streamlord-analysis`: a Kotlin module with no dependency beyond
`streamlord-core`, holding the Kotlin string reader, the JavaScript syntax check, the markup and
attribute rules, the signal and selector collectors, the route finder and the inspector's file
format. The IntelliJ plugin calls it. The VS Code extension mirrors it in TypeScript, and the two
are tested against the same cases.

**You can call it too**, which is worth doing in the tests of your own markup functions:

```kotlin sample=none
Analyzer().analyzeHtml(html)   // every issue, with offset, message and fix
```

## In IntelliJ specifically

The plugin adds what the IDE does not have. The Kotlin plugin already gives you completion, KDoc
and type errors for the DSL, and `@Language("HTML")` on Streamlord's parameters turns on HTML
injection by itself. What the plugin brings is the Datastar checks, HTML injection into the
strings that carry no annotation, and stepping aside for the official Datastar plugin's attribute
completion when that one is installed.

## And on this site

The code blocks on these pages are highlighted by the extension's own TextMate grammars, with its
own recommended colours. When the extension's palette changes, the next build of this site
follows. What you read here is what your editor shows you.
