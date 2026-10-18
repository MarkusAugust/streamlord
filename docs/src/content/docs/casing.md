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

`signal("foo-bar")` gives you `$fooBar`, the signal `data-signals:foo-bar` declares. The pairs
form reads its keys the same way, nested maps included, so all three name one signal:

```kotlin sample=html
div {
    dataSignals("foo-bar" to 7, "user" to mapOf("first-name" to ""))
    // data-signals="{"fooBar":7,"user":{"firstName":""}}"
    span { dataText(signal("foo-bar")) }          // $fooBar
    span { dataText(signal("user.first-name")) }  // $user.firstName
}
```

A dotted name in the pairs nests, as the keyed form does: `"user.first-name" to ""` declares the
same `$user.firstName`. A dotted key inside a map is refused, because the client would store
`{"a.b": 1}` as one key that `$a.b` does not reach.

A key the DSL does not write keeps its hyphen: the object form, `dataSignals("{'foo-bar': 1}")`,
and a signal patched from the server. In an expression Datastar reads `$foo-bar` as the signal
`foo-bar`, which `signal("foo-bar")` cannot write, so reach such a key by indexing the store:
`signal("['foo-bar']")` gives `$['foo-bar']`.

An explicit `case =` is kept, but the key is still written in kebab-case, because the browser
lowercases it either way.

An event or class name that mixes capitals with a hyphen before a lower-case letter, such as
`fooBar-baz`, cannot travel in a key: the capitals need `__case.camel`, and that turns the hyphen
into a capital too, so the client would listen for or toggle `fooBarBaz`. `dataOn` and
`dataClass` refuse it; write the name in one case, `fooBarBaz` or `foo-bar-baz`. A class you
cannot rename, such as `MuiButton-root` from a component library, goes in the object form,
`dataClass("{'MuiButton-root': \$active}")`, whose keys the client uses as written. In `dataBind`,
the `events` are used as the browser hands them over, so one with a capital (which arrives in
lowercase) or a `.` (which splits it in two) is refused, and a `prop` is refused when Datastar's
own camel-casing would not give it back: `x2y` comes back as `x2Y`, `_value` as `Value`. `dataBind`, `dataRef` and `dataIndicator` write the name in the
*value*, which keeps its case, unless you ask for a `case`, since Datastar applies `__case` to a
key only.

The object forms (`dataSignals("{...}")`, `dataComputed("{...}")`, `dataClass("{...}")`) use the
keys of the object as written and take no `case`.

A name that ends in `_` is written as it is, `data-class:b_`, until a modifier follows it. The
client cuts the attribute name at the first `__`, so `b_` with `__case.pascal` would be
`b___case.pascal`, read as the key `b` and a modifier it does not know. The DSL refuses such a
name wherever it would put a modifier after it.

## Keys that are not signal names

These keep their own characters, because they are not names Datastar has to give back to you:

```kotlin sample=html
div {
    dataClass("hover:bg-red-500", signal("open"))
    dataAttr("xlink:href", "'/x'")
    dataStyle("--brand", "'#10161f'")
}
```

A custom property keeps its case in CSS, and Datastar cannot set one with capitals: the browser
lowercases the key, and the object form turns each capital into a hyphen, so
`{'--brandColor': ...}` sets `--brand-color`. `dataStyle("--brandColor", ...)` is therefore
refused. Name the property in lowercase. The object form also puts a hyphen before a digit, so
`{'--brand2': ...}` sets `--brand-2`; the keyed form, `dataStyle("--brand2", ...)`, keeps it.

References may index: `signal("items[0].name")` and `signal("items['sub-total']")`. A reference
is JavaScript, so `signal`, `set` and the other expression helpers refuse only a blank name, one
with whitespace, and one that starts with a character Datastar does not read after `$`. A name
that becomes a key or an attribute value, as in `dataClass`, also refuses quotes, `/`, NUL,
the characters that end an attribute, and white space as either Java or JavaScript counts it, so
a no-break space too: the client splits a class list on it. A name that declares a signal, as in `dataSignals`, `dataBind`
and `dataComputed`, must be ASCII letters, digits and `_`, in parts joined by `.` or `-`, because
that is all Datastar reads after a `$`: a signal named `über` exists, and `$über` never reaches
it. All of these throw `InvalidSignalNameException` at render time. None of it makes a name from
a request safe to pass in: a reference is written into an expression as it stands.

## The hyphen after a signal

In an *expression*, `$foo-bar` is not `$foo` minus `bar`. Datastar reads the hyphen as part of
the signal name, so `$count-1` is the signal `count-1` and not "count minus one". Write
`$count - 1` with spaces, or use camelCase names and avoid the question.

Sometimes `$foo-bar` is what you mean, because the page stores a signal with that hyphen: a key
of the object form, `data-signals="{'foo-bar': 1}"`, a name in the value of `data-bind`,
`data-ref` or `data-indicator`, `data-bind="foo-bar"`, a key with `__case.kebab`, or a key whose
hyphen comes before a digit, `data-signals:item-2`, which camel casing leaves alone. The editors
read the declarations of the file as the client stores them and say nothing about `$foo-bar`
when it is declared there.

## Handled is not hidden

The DSL corrects your casing silently, which would be a bad trade if you never learned the rule.
So the VS Code extension puts a hint on every such call saying what actually goes on the wire.
For strings and templates it flags a capital letter in a key (with the kebab-case fix) and a
hyphen after a signal that nothing in the file declares with its hyphen (with the camelCase or
spaced-subtraction fix).

The default per attribute lives in `catalog/datastar-1.0.4.json` as `keyCase`, and both the SDK's
tests and the extensions read it from there.
