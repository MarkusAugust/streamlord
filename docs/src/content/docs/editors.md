---
title: "The editors"
description: "A VS Code extension and an IntelliJ plugin that read Datastar as a language."
---

Datastar lives inside attribute values and inside Kotlin strings — two places where no editor
checks anything on its own. That is what these are for.

<!--
  Installed from the marketplaces, with their own marks on the links.

  The marks are Simple Icons' monochrome paths, inline rather than fetched: a badge from
  shields.io would be two requests to a third party on every view of this page, in colours that
  fight the palette, at a width the page cannot reserve. The shapes are unmodified and they link
  to the publishers' own listings; the colour follows the text, which is what a monochrome mark
  is for.
-->
<div class="marketplaces">
  <a
    class="marketplace"
    href="https://marketplace.visualstudio.com/items?itemName=MarkusAugust.streamlord"
  >
    <svg class="marketplace__mark" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path fill="currentColor" d="M23.15 2.587L18.21.21a1.494 1.494 0 0 0-1.705.29l-9.46 8.63-4.12-3.128a.999.999 0 0 0-1.276.057L.327 7.261A1 1 0 0 0 .326 8.74L3.899 12 .326 15.26a1 1 0 0 0 .001 1.479L1.65 17.94a.999.999 0 0 0 1.276.057l4.12-3.128 9.46 8.63a1.492 1.492 0 0 0 1.704.29l4.942-2.377A1.5 1.5 0 0 0 24 20.06V3.939a1.5 1.5 0 0 0-.85-1.352zm-5.146 14.861L10.826 12l7.178-5.448v10.896z" />
    </svg>
    <span class="marketplace__where">VS Code Marketplace</span>
    <code class="marketplace__id">MarkusAugust.streamlord</code>
  </a>

  <a class="marketplace" href="https://plugins.jetbrains.com/plugin/34587-streamlord">
    <svg class="marketplace__mark" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path fill="currentColor" d="M2.345 23.997A2.347 2.347 0 0 1 0 21.652V10.988C0 9.665.535 8.37 1.473 7.433l5.965-5.961A5.01 5.01 0 0 1 10.989 0h10.666A2.347 2.347 0 0 1 24 2.345v10.664a5.056 5.056 0 0 1-1.473 3.554l-5.965 5.965A5.017 5.017 0 0 1 13.007 24v-.003H2.345Zm8.969-6.854H5.486v1.371h5.828v-1.371ZM3.963 6.514h13.523v13.519l4.257-4.257a3.936 3.936 0 0 0 1.146-2.767V2.345c0-.678-.552-1.234-1.234-1.234H10.989a3.897 3.897 0 0 0-2.767 1.145L3.963 6.514Zm-.192.192L2.256 8.22a3.944 3.944 0 0 0-1.145 2.768v10.664c0 .678.552 1.234 1.234 1.234h10.666a3.9 3.9 0 0 0 2.767-1.146l1.512-1.511H3.771V6.706Z" />
    </svg>
    <span class="marketplace__where">JetBrains Marketplace</span>
    <code class="marketplace__id">io.github.markusaugust.streamlord</code>
  </a>
</div>

IntelliJ IDEA 2026.1 and newer, Community and Ultimate alike.

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

The code blocks on these pages are highlighted by the extension's own TextMate grammars, with the
same recommended colours it offers you. When the extension's palette changes, the next build of
this site follows.

That holds for the Datastar tokens, which are what these grammars are for. It does not hold for
Kotlin itself: your editor colours Kotlin with the language server's semantic tokens, and a
static site has only the TextMate grammar, which says far less. Code here is greyer than code in
your IDE, and that is why.
