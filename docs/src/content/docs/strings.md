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
fun hit(title: String, url: String): Html =
    interpolate("""<li><a class="lt-link" href="%s">%s</a></li>""", url, title)
```

`%s` is a hole and `%%` writes one `%`. Every other `%` is literal, so `width: 50%` and `/a%20b`
need no ceremony. A miscounted hole throws rather than shipping a half-filled page.

What happens to a value is decided by where it landed, not by where it came from:

| Where the hole is | What happens to the value |
|---|---|
| Element text | Escaped |
| A quoted attribute that is not Datastar's | Escaped |
| A URL attribute: `href`, `src`, `action`, and `values`, `to` or `from` on an SVG animation | Escaped, and **refused** unless the whole value, every hole filled, is relative, `http`, `https`, `mailto` or `tel` |
| A `style` attribute | **Refused**, unless it is a number |
| A `data-*` attribute Datastar evaluates, an expression or a signal filter, or follows as a signal path: `data-bind`, `data-ref`, `data-indicator` | **Refused**, unless it is a number or a boolean |
| A `data-*` attribute Datastar reads as it stands: `data-nonce`, `data-preserve-attr`, `data-ignore`, `data-ignore-morph` | Escaped |
| An event handler such as `onclick`, or `srcdoc` | **Refused** |
| A tag name, an attribute name, an unquoted value | **Refused** |
| Inside `<script>` or `<style>` | **Refused** |

So the `url` in the example above may be `/heads/1` or `https://thurn.example/`, and a
`javascript:` URL throws instead of becoming a link that runs. The value is judged whole, as the
browser reads it: a hole that ends a path the markup begins takes anything, because the markup
already chose where it points, and `href="%s%s"` cannot be made into `javascript:` across its two
holes.

The markup is read the way the browser's tokenizer reads it, comments, end tags and all, and
twice: as HTML and as the inside of an `<svg>` or `<math>`, where a `title` or a `textarea` holds
elements rather than text. A value has to be safe both ways, since a patch with an SVG namespace
carries no `<svg>` of its own. So a `title` or a `textarea` that shows markup as a code sample cannot
take a hole inside a `data-*` attribute of that sample; put the whole sample in one hole instead,
where it is text either way.

The refusals are the point. Escaping cannot save these: a value in a `data-*` attribute Datastar
evaluates becomes part of a Datastar expression, and your own server signed the page it arrived
on; a value in `data-bind`, `data-ref` or `data-indicator` is a signal path the client follows
without a guard, so `__proto__.x` there writes into every object of the page; a value in an
attribute name or an unquoted value can add attributes beside itself, because escaping does not
escape a space; HTML escaping inside a `<script>` is meaningless; and an escaped value in
`onclick` or at the head of an `href` is still something the browser runs. `UnsafeInterpolationException`
says which value, where it landed, and what to do instead.

```kotlin sample=statements
val name = "Gorvek"

interpolate("""<li data-text="%s"></li>""", name)    // throws

// Passes: a number carries no expression.
interpolate("""<li data-signals="{n: %s}"></li>""", 3)
```

A number or a boolean is let into a Datastar attribute because its text is digits, a dot, a sign
or `true`/`false`. No string renders that way and also closes a quote, so the common honest case
of a server-rendered initial signal needs no waiver.

### Markup built from markup

What `interpolate` returns is `Html`: markup whose values have been through it. Hand it to
`patchElements`, `respondElements` or `datastarElements` as it is; inside `respondDatastar { }`
`patchElements(html)` needs no import of its own. Hand it to another `interpolate`, and it is
written as it stands in element text, as long as its own markup closes everything it opens: an
`Html` that ends inside a tag, a quoted value, a comment or a `<script>` would change how the
browser reads the values after it, so it is escaped like a string instead. Anywhere else, inside
an attribute, it is characters like any value, and escaped or refused as one.

```kotlin sample=statements
val heading = interpolate("""<h2 class="lt-heading">%s</h2>""", "Gorvek")

interpolate("""<section id="hero">%s<p>%s</p></section>""", heading, "<the oath>")
```

Only `interpolate` makes an `Html`, so holding one says where it came from. A Kotlin template
writes its markup, so `"<main>$page</main>"` works too, but the editors cannot see that
`page` is markup unless its type says so. An `Html` is never equal to a `String`: JUnit's
`assertEquals("<p>x</p>", interpolate(...))`, or AssertJ's `isEqualTo`, compiles and fails though
both print the same, so compare `.text`.

### When you mean it to be markup

Wrap it in `Trusted`, which writes the value as it stands wherever it lands:

```kotlin sample=statements
val hits = repository.search("gate")

interpolate("""<ul class="lt-list">%s</ul>""", Trusted(renderResults(hits)))
```

That waives both the escaping and the refusals, which is right for a fragment you rendered
yourself and wrong for anything that came out of a request. It is a word in your source that a
reviewer can search for, which is the whole reason it is a type and not a flag.

### This is the editors' rule, at run time

The editors ask the same question of a Kotlin template in an HTML string as you type, with the
same reading of the markup: core's, which `interpolate` uses and the VS Code analysis is held to.
Every `$name`, `${...}` and value joined with `+` is a hole, and each is judged by where it
lands:

| Where the hole is | What the editors say |
|---|---|
| Element text, a quoted attribute, a URL whose literal start settles the scheme (`#`, `/`, `?`, `https://`) | `unescaped-interpolation`: a hint, a warning when the value is a `String` or markup, with a fix that rewrites the literal as `interpolate(...)` |
| The start of a URL, `style`, a `data-*` expression, signal path or filter, `<script>`, `<style>`, `on*`, `srcdoc` | `unsafe-interpolation`: a warning, since escaping cannot help there |
| A value without quotes | `unsafe-interpolation`: a warning, a hint for a number or a boolean, with a fix that quotes the value |
| A tag name, an attribute name, a list of attributes | `unsafe-interpolation`: a hint, a warning when the value is a `String` or markup |
| `$name` where a Datastar signal could stand | `kotlin-interpolation`, the [dollar trap](/dollar-trap/); a hint when `name` is declared a number or a boolean, which is safe to write in, so only what you meant is in question |

A value the editor can see is yours says nothing anywhere: a `const val`, a literal, a call to one
of Streamlord's expression helpers, `${signal("count")}` or `${post("/save")}`, or an `if` or a
`when` whose every branch is one. Any other value is judged by what `interpolate` would let in
where it lands. A number or a boolean says nothing in text, an attribute, a URL or a `data-*`
expression, and a number in `style` too; one that may be null, an `Int?`, only where escaping is
enough. A hole that is markup you built, as a whole, says nothing in element text and is a warning
anywhere else, where its quotes and tags can end what holds it: an HTML literal, an
`interpolate(...)` or `Trusted(...)` call, a value declared `Html` or `@Language("HTML")`,
kotlinx.html output, or a `joinToString` whose every element is markup. Markup joined to a value is
read as one string: in `${"<b>" + query + "</b>"}` the literals are markup and `query` is the hole
that is judged. An `if`, a `when` or an `?:` is judged by its weakest result, so
`${if (blank) "nothing" else query}` is the `String` it may be.

VS Code knows what the file it is in declares, each name in the scope it is declared in, and every
`const val` in the workspace that the file does not declare as something else; IntelliJ also knows
the types, and what it knows of a value comes before anything the text suggests. Nothing here is an error, because a value the text cannot see into may well
be a number. The `interpolate(...)` fix is offered only when the rewrite keeps the page as it was,
escaping aside: not when a value may be null, which a template writes as `null` and `interpolate`
as nothing, nor when a hole in element text may hold markup, which `interpolate` would escape.

A call to `interpolate` is judged as it will run. A miscounted `%s` is an error,
`interpolate-holes`, because it always throws. A `%s` whose value `interpolate` will refuse, a
`String` in a `data-*` expression, is an error where the value is known to be one and a warning
where it is not. And a Kotlin template written into the format, `interpolate("<p>$name</p>")`, is
`interpolate-template`: Kotlin fills it in before `interpolate` sees the markup, so it is neither
escaped nor refused, and the fix turns it into a `%s` hole with the value among the arguments.

At run time the source is gone and only the finished string arrives, so the holes have to be left
standing for the same question to be asked. That is the whole of what `interpolate` wants from you.

## On Spring

Spring has no auto-configuration, so the helpers take the bean: `datastarElements(html, streamlord = bean)`,
`response.toResponseEntity(streamlord = bean)`, `events.asDatastarResponse(bean)`. With
`Streamlord.Default` the guard is off.
