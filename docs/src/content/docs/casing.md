---
title: "Casing"
description: "Keys in kebab-case, signals in camelCase, and the hyphen that is part of a name."
---

The browser lowercases every attribute name. `data-signals:fooBar` therefore reaches Datastar as
`foobar`, and the signal you meant is gone. Datastar's answer is to read the keys of certain
attributes as camelCase: `foo-bar` becomes the signal `$fooBar`.

One rule covers all of it:

> **Write keys in kebab-case, read signals in camelCase, and reach for `__case` only when the
> name really has capitals.**

## Which attributes read keys as camelCase

`signals`, `computed`, `bind`, `ref`, `indicator` and `match-media`. The keys of `on` and `class`
stay as written unless `__case` says otherwise, and `attr`, `style` and `animate` use the key
exactly as it comes.

## The DSL applies the rule for you

You write the name you mean; the DSL writes the key that comes back as that name, adding
`__case` where Datastar's default is not camel.

```kotlin sample=html
div {
    dataSignals("fooBar", "1")             // data-signals:foo-bar            -> $fooBar
    dataOn("widgetLoaded", "x()")          // data-on:widget-loaded__case.camel
    dataClass("isOpen", signal("open"))    // data-class:is-open__case.camel
    dataAttr("ariaLabel", "'x'")           // data-attr:aria-label
}
```

`signal("foo-bar")` gives you `$fooBar`, because that is what Datastar calls it.

An explicit `case =` is kept, but the key is still written in kebab-case, because the browser
lowercases it either way. `dataBind`, `dataRef` and `dataIndicator` write the name in the
*value*, which keeps its case — unless you ask for a `case`, since Datastar applies `__case` to a
key only.

The object forms (`dataSignals("{...}")`, `dataComputed("{...}")`, `dataClass("{...}")`) use the
keys of the object as written and take no `case`.

## Keys that are not signal names

These keep their own characters, because they are not names Datastar has to give back to you:

```kotlin sample=html
div {
    dataClass("hover:bg-red-500", signal("open"))
    dataAttr("xlink:href", "'/x'")
    dataStyle("--brand", "'#10161f'")
}
```

References may index: `signal("items[0].name")`. A blank name, or one with whitespace or quotes,
throws `InvalidSignalNameException` at render time.

## The hyphen after a signal

In an *expression*, `$foo-bar` is not `$foo` minus `bar`. Datastar reads the hyphen as part of
the signal name, so `$count-1` is the signal `count-1` and not "count minus one". Write
`$count - 1` with spaces, or use camelCase names and avoid the question.

## Handled is not hidden

The DSL corrects your casing silently, which would be a bad trade if you never learned the rule.
So the VS Code extension puts a hint on every such call saying what actually goes on the wire.
For strings and templates it flags a capital letter in a key (with the kebab-case fix) and a
hyphen after a signal (with the camelCase or spaced-subtraction fix).

The default per attribute lives in `catalog/datastar-1.0.4.json` as `keyCase`, and both the SDK's
tests and the extensions read it from there.
