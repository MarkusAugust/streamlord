---
title: "Templates"
description: "Pebble, Thymeleaf, JTE, kte, FreeMarker, Velocity, Mustache: render, then pass the string."
---

There is no template integration to learn, because there is nothing to integrate. Streamlord
takes a `String`. Your engine produces one.

```kotlin sample=statements
val html = pebble.render("cards/case.peb", mapOf("case" to 1))
```

```kotlin sample=ktor-routing
get("/cases") {
    call.respondDatastar {
        patchElements(
            pebble.render("cards/case.peb", mapOf("case" to 1)),
            selector = "#cases",
            mode = ElementPatchMode.APPEND,
        )
    }
}
```

## No dollar trap

This is the quiet advantage. The template engine owns the file, so Kotlin never parses the
markup and `$count` is just `$count`. The whole of [the `$` trap](/dollar-trap/) page does not
apply to you.

## What the editors give you

IntelliJ with the official Datastar plugin completes `data-*` attributes in Thymeleaf, FreeMarker
and JTE files. The Streamlord VS Code extension covers all of the above — JTE, kte, FreeMarker,
Velocity, Mustache, Pebble, and Thymeleaf, which is plain HTML — and understands each engine's
syntax well enough not to flag its own tags as errors.

## The guard

Template output is a string like any other, so [`guardElements`](/strings/) walks it the same way
and catches the same mistyped attributes. What it will not catch is a mistake inside the template
language itself; that belongs to your engine's own checks.

## A note on the roadmap

An `ElementsRenderer` port is planned, so that an engine can be plugged in behind an interface
rather than called at each site. It changes nothing about the above: passing a rendered string
will keep working, because it is the simplest thing that can work.
