---
title: "The $ trap"
description: "Kotlin's dollar and Datastar's dollar are the same character and not the same thing."
---

Datastar reads `$count` as a signal. Kotlin reads `$count` as a string template. In a Kotlin
string, Kotlin wins:

```kotlin sample=none
dataOnClick("$count++")     // sends "++" to the browser
dataText("$user.name")      // sends ".name"
```

If `count` is not in scope, that line does not compile and you find out immediately. If a `count`
*is* in scope — and in a handler that has just read signals, one often is — it compiles, ships,
and Datastar ignores the malformed expression **without a word**. The page simply does nothing.

## What Streamlord does about it

Every expression helper runs the text through `ExpressionGuard`, which throws
`InterpolatedExpressionException` at render time when the text has the shape only an eaten signal
leaves behind: empty, only operators, or an operator with a missing side.

A stack trace is not a fix, but it is an enormous improvement on a page that quietly does
nothing.

## Three ways to write it right

In order of preference:

```kotlin sample=html
button { dataOnClick(increment("count")) }
```

The helpers — `signal`, `set`, `toggle`, `not`, `increment`, `decrement`, `statements` — build the
expression for you, and there is no dollar in your source at all. This is the one to reach for.

```kotlin sample=html
button { dataOnClick($$"$count++") }
```

Kotlin 2.2 and newer. In a `$$"..."` literal, a single `$` is just a dollar and `$$` opens a
template. Good when the expression is genuinely JavaScript that no helper covers.

```kotlin sample=html
button { dataOnClick("${'$'}count++") }
```

Works on any Kotlin version, and reads as badly as it looks. Use it when you are stuck on an
older compiler.

## For strings and templates

The DSL guards its own expressions because it builds them. HTML that arrives as a string has to
be walked instead, and that is [`guardElements`](/strings/) — opt-in, because walking every patch
is a choice you should make rather than inherit.

Templates have no trap at all: the template engine owns the file, and Kotlin never sees the
dollar.

## In the editors

Both the VS Code extension and the IntelliJ plugin flag the trap while you type, with a quick fix
that rewrites the line into one of the three forms above. That is the cheapest place to catch it —
before the code exists, rather than in a stack trace or, worse, in a page that does nothing.
