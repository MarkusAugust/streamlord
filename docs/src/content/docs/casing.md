---
title: "Casing"
description: "One name for a signal, in Kotlin and in the browser, and the keys the DSL writes for it."
---

The browser lowercases every attribute name. `data-signals:fooBar` therefore reaches Datastar as
`foobar`, and the signal you meant is gone. Datastar's answer is to read the keys of certain
attributes as camelCase: `foo-bar` becomes the signal `$fooBar`.

Streamlord turns that into one rule:

> **A signal has one name, the one you write. Write it in camelCase, without a hyphen, and the
> DSL writes the key that comes back as that name.**

## Which attributes read keys as camelCase

`signals`, `computed`, `bind`, `ref`, `indicator` and `match-media`. The keys of `on` and `class`
stay as written unless `__case` says otherwise, and `attr`, `style` and `animate` use the key
exactly as it comes.

## The DSL applies the rule for you

You write the name you mean; the DSL writes the key that comes back as that name, adding
`__case` where the attribute's default casing would not give it back: `.camel` for a camelCase
event or class, `.pascal` for a name that starts with a capital, such as `URL`.

```kotlin sample=html
div {
    dataSignals("fooBar", "1")             // data-signals:foo-bar            -> $fooBar
    dataOn("widgetLoaded", "x()")          // data-on:widget-loaded__case.camel
    dataClass("isOpen", signal("open"))    // data-class:is-open__case.camel
    dataAttr("ariaLabel", "'x'")           // data-attr:aria-label
}
```

The same name works everywhere else a signal is named. `signal("fooBar")` is `$fooBar`, the pairs
form takes the key of each pair as the name, and a dotted name nests, as the keyed form does:

```kotlin sample=html
div {
    dataSignals("fooBar" to 7, "user.firstName" to "")
    // data-signals="{"fooBar":7,"user":{"firstName":""}}"
    span { dataText(signal("fooBar")) }          // $fooBar
    span { dataText(signal("user.firstName")) }  // $user.firstName
}
```

`patchSignals("fooBar" to 1)` on the server, `removeSignals("fooBar")`, `dataBind("fooBar")` and
`readSignals<Form>().fooBar` all mean that signal too.

## Why a signal name has no hyphen

The hyphen is the one character the client reads two ways. In an attribute key it is a capital:
`data-signals:foo-bar` is `$fooBar`. In an expression and in a patch it is itself: `$foo-bar` is
the signal `foo-bar`, and so is `{"foo-bar": 1}` from the server. A name with a hyphen would
therefore mean two signals, depending on where you wrote it. Every name without one comes back
exactly as written, `URL`, `x2Y` and `_value` included.

So the helpers that name a signal refuse a hyphen: `dataSignals`, `dataComputed`, `dataBind`,
`dataRef`, `dataIndicator`, `dataMatchMedia`, `signal` and the other expression helpers,
`patchSignals` and `removeSignals`. They throw at render or send time with the name to write
instead, and the editors flag the call before you run it, with a fix:

```text
Name "form.first-name" has a hyphen the client reads as part of a signal name or as a minus,
which a signal name may not hold; write form.firstName, or form['first-name'] for a key with a
hyphen in it.
```

A key with a hyphen is still allowed as data: a key inside a value, which the client merges as it
stands, a key of the object form, `dataSignals("{'foo-bar': 1}")`, and a key from your codec,
`dataSignals(codec.toJsonValue(form))`. `"user" to mapOf("first-name" to "")` declares
`user['first-name']`, and an expression reaches it with brackets, as in JavaScript:
`signal("user['first-name']")` gives `$user['first-name']`. To patch or remove such a key from the
server, put it inside a value, `patchSignals("user" to mapOf("first-name" to null))`. A top-level
key with a hyphen, such as the one the object form above declares, takes the JSON form:
`patchSignals("""{"foo-bar": null}""")`.

A dotted key inside a map is refused, because the client would store `{"a.b": 1}` as one key
that `$a.b` does not reach.

The helpers that name a signal take no `case`: the DSL picks the key, so a `case` could only give
the signal a name other than the one you wrote. `dataOn` and `dataClass` keep theirs, because an
event or a class is not a signal.

An event or class name that mixes capitals with a hyphen before a lower-case letter, such as
`fooBar-baz`, cannot travel in a key: the capitals need `__case.camel`, and that turns the hyphen
into a capital too, so the client would listen for or toggle `fooBarBaz`. `dataOn` and
`dataClass` refuse it; write the name in one case, `fooBarBaz` or `foo-bar-baz`. A class you
cannot rename, such as `MuiButton-root` from a component library, goes in the object form,
`dataClass("{'MuiButton-root': \$active}")`, whose keys the client uses as written. In `dataBind`,
the `events` are used as the browser hands them over, so one with a capital (which arrives in
lowercase) or a `.` (which splits it in two) is refused, and a `prop` is refused when Datastar's
own camel-casing would not give it back: `x2y` comes back as `x2Y`, `_value` as `Value`.
`dataBind`, `dataRef` and `dataIndicator` write the name in the *value*, which keeps its case.

The object forms (`dataSignals("{...}")`, `dataComputed("{...}")`, `dataClass("{...}")`) use the
keys of the object as written.

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
with whitespace, one that starts with a character Datastar does not read after `$`, and a hyphen
the client would read as part of a signal name or as a minus. They read the name as the client
reads `$` and the name: a quoted key in brackets is text, whatever it holds, so
`signal("a['x]y-z']")` is fine, while `signal("items[\$i-1]")` reads the signal `i-1` and
`signal("a['x'].b-c")` subtracts `c`, and both are refused. A name that becomes a key or an attribute value, as in `dataClass`, also refuses
quotes, `/`, NUL, the characters that end an attribute, and white space as either Java or
JavaScript counts it, so a no-break space too: the client splits a class list on it. A name that
declares a signal, as in `dataSignals`, `dataBind` and `dataComputed`, must be ASCII letters,
digits and `_`, in parts joined by `.`, because that is all Datastar reads after a `$`: a signal
named `über` exists, and `$über` never reaches it. All of these throw `InvalidSignalNameException`
at render time. None of it makes a name from a request safe to pass in: a reference is written
into an expression as it stands.

## The hyphen after a signal

In an expression you write yourself, `$foo-bar` is not `$foo` minus `bar`. Datastar reads the
hyphen as part of the signal name, so `$count-1` is the signal `count-1` and not "count minus
one". Write `$count - 1` with spaces.

Sometimes `$foo-bar` is what you mean, because the page stores a signal with that hyphen: a key of
the object form, `data-signals="{'foo-bar': 1}"`, a name in the value of `data-bind`, `data-ref`
or `data-indicator` in plain HTML, a key with `__case.kebab`, or a key whose hyphen comes before a
digit, `data-signals:item-2`, which camel casing leaves alone. The editors read the declarations
of the file as the client stores them and say nothing about `$foo-bar` when it is declared there.

## Handled is not hidden

The DSL writes the key for you, which would be a bad trade if you never learned the rule. So the
editors put a hint on every such call saying what actually goes on the wire, and flag a hyphen in
a signal name with the camelCase fix. For strings and templates they flag a capital letter in a
key (with the kebab-case fix) and a hyphen after a signal that nothing in the file declares with
its hyphen (with the camelCase or spaced-subtraction fix).

The default per attribute lives in `catalog/datastar-1.0.4.json` as `keyCase`, and both the SDK's
tests and the extensions read it from there.
