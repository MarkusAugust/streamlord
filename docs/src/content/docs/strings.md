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
when a `count` happens to be in scope, silently ships its value, `data-text="3"`, or
`data-text=""` when it was empty. On older Kotlin, write `${'$'}count`.

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
`ExpressionGuard`, so a `data-text=""` left behind by a template that ate an empty value throws
`InterpolatedExpressionException` **naming the attribute** instead of reaching the browser.

The same walk catches a mistyped attribute. `data-onn:click` is one letter from `data-on:click`
and throws `MistypedAttributeException` saying so. A name that carries a key or a modifier is
judged on any one-letter slip, because `data-signal:name` is nobody's own attribute. A bare
`data-*` name is judged far more narrowly: only a swapped letter in a long name, so
`data-indicater` and `data-computer` are caught while `data-test`, `data-kind`, `data-size` and
`data-theme` are yours and pass untouched. A bare name that merely adds a letter passes too:
`data-signal` is left alone, because `data-animated` and `data-effects` are honest words in the
same shape.

It is a small scan that builds no tree and copies no markup, and it skips `<script>` and `<style>`
bodies. `ElementsGuard.check(html)` is also there to call directly, which is worth doing in the
tests of your markup functions.

In IntelliJ, where nothing flags an unknown `data-*` name on its own, this guard is the check.

## Values from outside

`guardElements` walks a finished string, so it can tell you an expression was eaten. What it
cannot tell you is where a value came from, because by then Kotlin has concatenated everything.
`interpolate` turns that around: leave the holes standing and Streamlord fills them, which means
it knows exactly where each value landed.

```kotlin sample=declarations
fun hit(title: String, url: String): String =
    interpolate("""<li><a class="fs-link" href="%s">%s</a></li>""", url, title)
```

`%s` is a hole and `%%` writes one `%`. Every other `%` is literal, so `width: 50%` and `/a%20b`
need no ceremony. A miscounted hole throws rather than shipping a half-filled page.

What happens to a value is decided by where it landed, not by where it came from:

| Where the hole is | What happens to the value |
|---|---|
| Element text | Escaped |
| A quoted attribute that is not Datastar's | Escaped |
| The start of a URL attribute: `href`, `src`, `action` | Escaped, and **refused** unless it is relative, `http`, `https`, `mailto` or `tel` |
| A `style` attribute | **Refused**, unless it is a number |
| A `data-*` attribute Datastar reads | **Refused**, unless it is a number or a boolean |
| An event handler such as `onclick`, or `srcdoc` | **Refused** |
| A tag name, an attribute name, an unquoted value | **Refused** |
| Inside `<script>` or `<style>` | **Refused** |

So the `url` in the example above may be `/heads/1` or `https://thurn.example/`, and a
`javascript:` URL throws instead of becoming a link that runs. A hole further into the value,
such as the last segment of a path the markup begins, is an ordinary attribute, because the
markup already chose where it points.

The refusals are the point. Escaping cannot save these: a value in a `data-*` attribute
becomes part of a Datastar expression, and your own server signed the page it arrived on; a value
in an attribute name or an unquoted value can add attributes beside itself, because escaping does
not escape a space; HTML escaping inside a `<script>` is meaningless; and an escaped value in
`onclick` or at the head of an `href` is still something the browser runs. `UnsafeInterpolationException`
says which value, where it landed, and what to do instead.

```kotlin sample=statements
val name = "Gorvek"

interpolate("""<li data-text="%s"></li>""", name)        // throws
interpolate("""<li data-signals="{n: %s}"></li>""", 3)   // passes: a number carries no expression
```

A number or a boolean is let into a Datastar attribute because its text is digits, a dot, a sign
or `true`/`false`. No string renders that way and also closes a quote, so the common honest case
of a server-rendered initial signal needs no waiver.

### When you mean it to be markup

Wrap it in `Trusted`, which writes the value as it stands wherever it lands:

```kotlin sample=statements
val hits = repository.search("gate")

interpolate("""<ul class="fs-list">%s</ul>""", Trusted(renderResults(hits)))
```

That waives both the escaping and the refusals, which is right for a fragment you rendered
yourself and wrong for anything that came out of a request. It is a word in your source that a
reviewer can search for, which is the whole reason it is a type and not a flag.

### This is the editors' rule, at run time

The editors already refuse a Kotlin `${'$'}{...}` inside a `data-*` expression attribute, because
they read your source and can see it. At run time the source is gone and only the finished string
arrives, so the holes have to be left standing for the same question to be asked. That is the
whole of what `interpolate` wants from you.

## On Spring

Spring has no auto-configuration, so the helpers take the bean: `datastarElements(html, streamlord = bean)`,
`response.toResponseEntity(streamlord = bean)`, `events.asDatastarResponse(bean)`. With
`Streamlord.Default` the guard is off.
