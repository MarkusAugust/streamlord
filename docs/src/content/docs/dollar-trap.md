---
title: "The $ trap"
description: "Kotlin's dollar and Datastar's dollar are the same character and not the same thing."
---

Datastar reads `$count` as a signal. Kotlin reads `$count` as a string template. In a Kotlin
string, Kotlin wins:

```kotlin sample=none
// Sends the value of count, then "++": "3++", or "++" if it is empty.
dataOnClick("$count++")

// Sends user.toString(), then ".name".
dataText("$user.name")
```

If `count` is not in scope, that line does not compile and you find out immediately. If a `count`
*is* in scope (and in a handler that has just read signals, one often is), it compiles and ships
whatever the variable held at that moment. The browser gets `3++`, which is not an expression
about a signal at all, and the page simply does nothing.

## Four places Streamlord catches it

| Where | When | `3++`, the variable held a value | `++`, the variable was empty |
|---|---|---|---|
| [The editors](/editors/) | As you type | Caught | Caught |
| [The analysis](/analysis/) | In CI, as a test you add | Caught | Caught |
| [The DSL's own guard](#the-guard-in-the-dsl) | When the event is built | Not caught | Caught |
| [`guardElements`](/strings/) | When a string patch is built, if you turn it on | Not caught | Caught |

The first two read your source, so they see the dollar before Kotlin takes it. The last two read
the text after, and can only tell when what is left cannot be an expression. That is why the
editor and the CI test are the two to have.

## The guard in the DSL

Every expression helper runs the text through `ExpressionGuard`, which throws
`InterpolatedExpressionException` at render time when the text has the shape an eaten signal
leaves behind when the variable was empty: nothing at all, only operators, or an operator with a
missing side. That is the common case in a handler, where the value Kotlin substituted is an empty
query or a blank field. An array with a hole, `[, 1]`, is refused too, since that is what `[$first, 1]`
leaves; write `[undefined, 1]` when you mean the hole. So is a `+` or `-` with a space after it at
the start, `- 1`, which is what `$a - 1` leaves: write `-$x` and `-1` without the space.

It cannot catch the other case. When the variable held `3`, the text is `3++`, and nothing in
that string says a signal was meant. Only something that reads your source can see it, which is
what [the editors](#in-the-editors-and-in-ci) and the same analysis [run in CI](/analysis/) are for.

A stack trace is not a fix, but it is an enormous improvement on a page that quietly does
nothing.

## Three ways to write it right

In order of preference:

```kotlin sample=html
button { dataOnClick(increment("count")) }
```

The helpers (`signal`, `set`, `toggle`, `not`, `increment`, `decrement`, `statements`) build the
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
be walked instead, and that is [`guardElements`](/strings/), which is opt-in, because walking every patch
is a choice you should make rather than inherit.

Templates have no trap at all: the template engine owns the file, and Kotlin never sees the
dollar.

## In the editors and in CI

Both the VS Code extension and the IntelliJ plugin flag the trap while you type, with a quick fix
that rewrites the line into one of the three forms above. That is the cheapest place to catch it:
before the code exists, rather than in a stack trace or, worse, in a page that does nothing.

The editor only sees the files someone opens. [The analysis](/analysis/) is the same check as a
test over `src/main/kotlin`, so a trap in a file nobody opened still fails the build.
