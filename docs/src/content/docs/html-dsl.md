---
title: "The kotlinx.html DSL"
description: "Every Datastar attribute, action and modifier of 1.0.4, as typed extension functions."
---

`streamlord-html` adds one extension function per Datastar attribute to kotlinx.html. You get
completion, types, and KDoc from your IDE, and you stop writing `data-on:click` as a string that
nothing checks.

```kotlin sample=html
div {
    dataSignals("count" to 0, "open" to false)
    dataOnClick(post("/increment")) { debounce = 300.milliseconds; prevent = true }
    dataOnIntersect(get("/more")) { once = true; threshold = 50 }
    dataBind("search") { events = listOf("input", "blur") }
    dataText(signal("count"))
    dataShow(not("open"))
    dataClass("active", signal("open"))
    dataIndicator("busy")
    button {
        dataOnClick(statements(toggle("open"), set("count", 0)))
        +"Reset"
    }
}
```

`dataSignals` takes its signals in four forms. One name and its expression,
`dataSignals("count", "0")`, which writes `data-signals:count`. Pairs, as above, where the key of
each pair is a signal name as `data-signals:name` reads it. An object expression as a string,
`dataSignals("{count: 0}")`. And a JSON object, such as a class your codec encoded with
`toJsonValue`, whose keys are written as they stand; [Signals](/signals/#declaring-them-from-the-class)
shows it with the class a handler reads.

## Modifiers are a block, not a string

Datastar writes modifiers as suffixes: `__debounce.300ms`, `__once`, `__prevent`. In the DSL they
are properties on a receiver, so a misspelling is a compile error rather than a suffix the browser
silently ignores.

```kotlin sample=html
input {
    dataOnInput("@get('/search')") { debounce = 300.milliseconds }
}
```

Durations are `kotlin.time.Duration`, which means `300.milliseconds` and `1.seconds` rather than
a string you have to get the unit right in. They are written in whole milliseconds, a fraction
rounded down: `1.5.milliseconds` becomes `__debounce.1ms`. Datastar's client could not read more,
since it splits a modifier at each `.` and takes `__debounce.1.5ms` for 1 ms. One shorter than a
millisecond would be written as zero and is refused.

## Actions render their options only when set

```kotlin sample=html
button {
    dataOnClick(post("/form") { contentType = FetchOptions.ContentType.FORM })
    +"Send"
}
```

That renders `@post('/form', {contentType: 'form'})`. An option you do not set does not appear,
so the attribute stays as short as what you actually asked for.

Everything the helpers quote (`js()`, `set()`, `setAll()`, the fetch options, the Pro actions)
is written as a JavaScript literal in single quotes, the way the Datastar documentation writes
it. Single and double quotes, `&` and `\` inside a value are written as `\x22`, `\x26`
and the like, so the same text works unchanged in the DSL and between the double quotes of an
attribute in a string or a template.

## Feeding streams directly

The DSL is not only for whole pages. It builds the elements a patch carries:

```kotlin sample=ktor-routing
get("/feed") {
    call.respondDatastar {
        patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
            li { +"one more" }
        }
    }
}
```

`elements { }` builds a string for the places that take one, and `elementsResponse { }` builds a
non-SSE reply.

## The aliased bundle

Using Datastar with a different attribute prefix? Set it once at startup:

```kotlin sample=statements
DatastarAttributes.prefix = "data-star-"
```

It reaches everything, including the `data-effect="el.remove()"` the core writes on a script
event.

## An attribute without a helper

A Datastar plugin of your own, or an attribute the DSL has no helper for, is written with the two
calls the helpers use: `DatastarAttributes.name` puts the prefix in front, and
`DatastarAttributes.expression` checks the expression and hands it to kotlinx.html so the browser
reads it back as written.

```kotlin sample=html
div {
    attributes[DatastarAttributes.name("tooltip")] = DatastarAttributes.expression("\$hint")
}
```

That renders `data-tooltip="$hint"`, or `data-star-tooltip` with the aliased prefix.

## What this page does not cover

Datastar Rocket, the separate `datastar-rocket.js` bundle with `data-if`, `data-for` and its
web-component API, is in beta and not covered by the DSL. And the DSL is one of
[three ways to write markup](/choosing-a-style/), not the way; if your team reads strings or
templates better, those are served as equals.

Custom elements, chart libraries and your own scripts next to the attributes are on
[Components and plain JavaScript](/components/).
