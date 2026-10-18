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
and JTE files. The Streamlord VS Code extension covers all of the above (JTE, kte, FreeMarker,
Velocity, Mustache, Pebble, and Thymeleaf, which is plain HTML) and understands each engine's
syntax well enough not to flag its own tags as errors.

## If your engine is not one of those

The list above is of engines someone tested. What the analysis actually carries is a set of
delimiter *shapes*, not names: `{{ }}`, `{% %}`, `${ }`, `<% %>`, `[# ]` and the control-flow
keywords of JTE and Velocity. Any engine using one of those shapes is covered whatever it is
called, which is most of them.

For an engine outside all of them, Qute's `{name}`, Closure's `{$name}`, Rocker's `@name`, the
analysis reads the tag as Datastar 1.0.4 would read the same text unrendered. Measured in Chrome
against the real client, that splits three ways.

**Qute's `{name}` is fine in expressions.** `data-text="{name}"` and the rest produce no
findings: to the client it is an object literal, and it compiles.

**Closure's `{$name}` and Rocker's `@name` are errors where the attribute returns a value.**
`data-text="{$name}"`, `data-show="{$open}"`, `data-attr:title="{$name}"` and `data-text="@name"`
report `expression-syntax`, and the client fails on the same text with a `SyntaxError`:
`{$name}` is an object literal with a signal where a name has to be, and `@name` without
parentheses is not an action. In `data-on:click`, `data-effect` and `data-init`, which run
statements and return nothing, `{$name}` is a block and gives no findings. Where the value is
text, put the tag in a JavaScript string, `data-text="'{$name}'"`: the browser gets a string
literal once the page is rendered, and the editors leave the quoted text alone.

**Attributes that take a signal name are not fine for any of them.** `data-bind="{name}"`,
`data-bind="{$name}"`, `data-bind="@name"` and the same tags in `data-indicator` and its kind
report `signal-name-expected`, because a signal name is held to a strict pattern and neither a
brace nor an `@` is in it.

The rendered page never holds the tag, so these findings are wrong about it, and there is
currently no way to tell the analysis otherwise. It is on [the roadmap](/roadmap/).

Nothing about rendering is affected either way. This is the editor's opinion of your file, not
Streamlord's opinion of your markup.

## The guard

Template output is a string like any other, so [`guardElements`](/strings/) walks it the same way
and catches the same mistyped attributes. What it will not catch is a mistake inside the template
language itself; that belongs to your engine's own checks.
