---
title: "Strings"
description: "A function that returns HTML is a first-class way to write markup here."
---

Every place Streamlord takes HTML takes a `String`. A function returning a triple-quoted literal
is not a fallback for people who have not learned the DSL. It is one of the three supported
ways, and the editor tooling treats it as such.

```kotlin sample=declarations
@org.intellij.lang.annotations.Language("HTML")
fun counter(count: Int): String =
    $$"""
    <div id="counter" data-signals="{count: $$count}">
        <button data-on:click="$count++">Raise</button>
        <span data-text="$count"></span>
    </div>
    """.trimIndent()
```

```kotlin sample=ktor-routing
get("/counter") {
    call.respondDatastar { patchElements(counter(3)) }
}
```

Two things make this comfortable.

## `$$"""..."""`

Kotlin 2.2 and newer. In a multi-dollar string a single `$` is just a dollar, so `$count` reaches
the browser as the signal it is, and `$$count` is the Kotlin template.

Without it you are back in [the `$` trap](/dollar-trap/): `$count` either fails to compile or,
when a `count` happens to be in scope, silently ships `data-text=""`. On older Kotlin, write
`${'$'}count`.

## `@Language("HTML")`

From `org.intellij.lang.annotations`, which your build already has through the Kotlin standard
library. Streamlord declares it `compileOnly`, so nothing new reaches you.

IntelliJ then treats the string as HTML: highlighting, tag completion, and, with the official
Datastar plugin, completion of every `data-*` attribute. Streamlord's own parameters already
carry the annotation (`patchElements`, `PatchElements`, `respondElements`, `datastarElements`,
`ElementsResponse`; `JSON` for signals, `JavaScript` for scripts), so a literal passed straight in
is injected without you writing anything. On your own functions, add it yourself.

The VS Code extension does not need the annotation: it treats a string as HTML when it opens with
a tag, carries the annotation, or has a `// language=HTML` comment, and gives it the same
diagnostics, completions, hover and highlighting as a template file.

## The guard

The DSL guards its own expressions because it builds them. A string arrives finished, so the
guard has to walk it, and that is opt-in:

```kotlin sample=ktor-application
install(StreamlordPlugin) { guardElements = true }
```

With it on, every element patch and elements response leaving that instance is walked by
`ElementsGuard`. It finds each `data-*` attribute whose value is an expression and runs it through
`ExpressionGuard`, so a `data-text=""` left behind by an eaten template throws
`InterpolatedExpressionException` **naming the attribute** instead of reaching the browser.

The same walk catches a mistyped attribute. `data-onn:click` is one letter from `data-on:click`
and throws `MistypedAttributeException` saying so. A bare name with no key or modifier is only
judged against the long Datastar names (`data-signal`, `data-indicater`) because the short ones
have too many honest neighbours: `data-test`, `data-kind`, `data-size` and `data-theme` are yours
and pass untouched.

It is a small allocation-free scan that skips `<script>` and `<style>` bodies. `ElementsGuard.check(html)`
is also there to call directly, which is worth doing in the tests of your markup functions.

In IntelliJ, where nothing flags an unknown `data-*` name on its own, this guard is the check.

## On Spring

Spring has no auto-configuration, so the helpers take the bean: `datastarElements(html, streamlord = bean)`,
`response.toResponseEntity(streamlord = bean)`, `events.asServerSentEvents(bean)`. With
`Streamlord.Default` the guard is off.
