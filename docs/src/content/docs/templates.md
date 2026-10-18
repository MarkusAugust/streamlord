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
analysis reads an expression as Datastar 1.0.4 would read the same text unrendered, and is
stricter than the client about signal names. Measured in Chrome against the real client:

**None of the three works inside an expression.** Qute's `{count}` is an object literal that
reads a variable the page does not have, a `ReferenceError`, and `{item.name}` is a
`SyntaxError` in `data-text` and a `ReferenceError` in `data-on:click` once it is clicked. A bare
`{name}` only seems to work, because every page has `window.name`: `data-text="{name}"` shows
`[object Object]`. Closure's `{$name}` is a `SyntaxError` where the attribute returns a value,
such as `data-text`, `data-show` or `data-attr:title`, and compiles only as a block in
`data-on:click`, `data-effect` or `data-init`. Rocker's `@name` is a `SyntaxError` in every
expression attribute, those three included, since an `@` name without parentheses is no action.
The editors report `expression-syntax` where the client cannot compile the text: `{$name}` and
`@name` as above, and `{item.name}` in `data-text`. A name the page lacks, such as Qute's
`{count}`, is no syntax error, and they say nothing about it.

**Do not render a value into an expression, in any engine.** Putting the tag in a JavaScript
string, `data-text="'{$name}'"`, looks like the fix and is code injection. The engine escapes
the value for HTML, the browser undoes that escaping when it reads the attribute, and Datastar
runs what is left as JavaScript. Measured: `O'Brien`, `a\` and a line break each stop the
expression from compiling, and `x' + (window.__pwn = 1) + '` runs. Put the value in an attribute
of its own, which the browser hands over as text, and read it with `el.dataset`:

```html
<!-- Before: the value is JavaScript. O'Brien breaks it, and x' + (window.__pwn = 1) + ' runs. -->
<span data-text="'{$name}'"></span>

<!-- After: the value is text, whatever it holds. -->
<span data-value="{$name}" data-text="el.dataset.value"></span>

<!-- Text that never changes needs no Datastar: the engine's own escaping is enough. -->
<span>{$name}</span>
```

Qute's `{name}` and Rocker's `@name` go in `data-value` the same way. With the `el.dataset` form,
`Gorvek`, `O'Brien`, `x' + (window.__pwn = 1) + '`, `a\`, a line break and
`</span><img src=x onerror=alert(1)>` each show as written and nothing runs, and the editors
report nothing for any of the three tags. It is the rule `interpolate` enforces in Kotlin, where
`data-text="%s"` throws; [Values from outside](/strings/#values-from-outside) says why.

**Attributes that take a signal name report all three.** `data-bind="{name}"`,
`data-bind="{$name}"`, `data-bind="@name"` and the same tags in `data-indicator` and its kind
report `signal-name-expected`. The client is laxer: `data-bind="{$name}"` makes a signal named
`{$name}`, which no `$name` in an expression can reach. The editors hold a signal name to the
pattern an expression can reach, since a brace or an `@` in one is almost always a mistake.

In the rendered page the tag is gone, so in a signal-name attribute the finding is wrong about
it, and there is currently no way to tell the analysis otherwise. It is on
[the roadmap](/roadmap/).

Nothing about rendering is affected either way. This is the editor's opinion of your file, not
Streamlord's opinion of your markup.

## The guard

Template output is a string like any other, so [`guardElements`](/strings/) walks it the same way
and catches the same mistyped attributes. What it will not catch is a mistake inside the template
language itself; that belongs to your engine's own checks.
