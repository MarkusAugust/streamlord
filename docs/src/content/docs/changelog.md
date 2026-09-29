---
title: "Changelog"
description: "What each release changed, and what it asks of your code."
---

[CHANGELOG.md](https://github.com/MarkusAugust/streamlord/blob/master/CHANGELOG.md) in the
repository is authoritative and complete. This page carries only what changes how you write code,
with a sentence about what to do.

## Unreleased

## 0.3.1

**Name your serializers for a native image.** `KotlinxSignalsCodec` now takes them by the type
they handle and consults them before the reflective lookup, and `strict = true` refuses that
fallback so a type you forgot fails on the JVM rather than inside the image. Nothing changes if
you pass none. See [Native image](/native-image/).

**The javadoc jar is no longer empty.** Every module up to 0.3.0 published an empty one, so
javadoc.io had nothing to show for a library whose source is mostly documentation. From this
release it carries the API reference generated from the KDoc.

**A hyphen after a signal is part of the name.** Datastar 1.0.4 reads `$foo-bar` as the signal
`foo-bar`, and `$count-1` as the signal `count-1` — not as subtraction. Only `$a-$b` subtracts.
The editors now say so, and offer both fixes: a camelCase signal name, or the subtraction written
with spaces. See [Casing](/casing/).

## 0.3.0

**`streamlord-analysis`.** The analysis behind the editor tooling, extracted into a Kotlin module
that depends on nothing beyond `streamlord-core`. `Analyzer().analyzeKotlin(source)` and
`analyzeHtml(source)` return every issue with its offset, severity, message and quick fixes — and
you can call it yourself, which is worth doing in the tests of your markup functions. See
[The editors](/editors/).

**The IntelliJ plugin**, for IDEA 2026.1 and newer, Community and Ultimate. Inspections,
completion, hover, highlighting, HTML injection into marker-less strings, and the Stream
Inspector as a tool window, sharing saved requests with VS Code.

**Fixed:** an expression opening with `{` is now read as the object literal Datastar makes of it,
so `data-signals="{count: 0}"` is no longer flagged as a syntax error.

## 0.2.0

**`ElementsGuard`, and `guardElements`.** The `$` trap hunted in HTML written as a string. Turn it
on with `Streamlord(guardElements = true)`, or `install(StreamlordPlugin) { guardElements = true }`
on Ktor, and every element patch leaving that instance is walked: an expression an eaten Kotlin
template left behind throws with the attribute named, and a `data-*` name one letter from a real
one throws `MistypedAttributeException`. Off by default. See [Strings](/strings/).

## 0.1.1

**`ExpressionGuard`.** Every expression helper in the DSL refuses, at render time, text with the
shape a Kotlin string template leaves behind when it eats a signal. The message names the helpers
and the `$$"..."` literal. See [The $ trap](/dollar-trap/).

## 0.1.0

The first forging: the protocol and the encoder verified against the official golden files, both
adapters, the kotlinx.html DSL, the codec modules, the VS Code extension, and
`catalog/datastar-1.0.4.json` as the shared source of truth.

## On versioning

Streamlord is 0.x, and the API is still allowed to move. A breaking change is listed here with
its migration in the same entry. At 1.0 the ordinary rule takes over.

The Datastar protocol version is tracked separately and stated on every release. This one speaks
**1.0.4**.
