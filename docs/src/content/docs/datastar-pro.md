---
title: "Datastar Pro"
description: "Opt-in helpers that write the documented Pro attribute names. No Pro code, ever."
---

Datastar Pro is licensed software. **None of it lives in this repository:** no plugin source, no
bundle, no inspector. The open-source modules cover the free Datastar bundle only.

## What `streamlord-html-pro` is

A separate artifact, so that using it is an explicit choice rather than something that arrives
with a transitive dependency. It contains nothing but kotlinx.html helpers that write the
publicly documented Pro attribute names and action strings.

```kotlin sample=html
div {
    dataPersist("draft", SignalFilter.include("^form"), session = true)
    dataQueryString(history = true)
    button { dataOnClick(clipboardExpr(signal("code"))); +"Copy" }
}
```

`dataPersist()`, `dataScrollIntoView()`, `dataQueryString()`, `clipboard()`, `fit()`, `intl()`
and the rest: attribute names and action strings, and nothing else.

## What it is not

It is not Datastar Pro, and it does not unlock, fetch or bundle it. The helpers are **inert**
until you load the Pro bundle you licensed. Writing `data-persist` into your HTML does nothing at
all unless a script that understands `data-persist` is on the page, and that script is yours to
obtain.

This is the whole reason the module is separate and named the way it is. An attribute name is a
string; the software that acts on it is not, and it is not ours to give.

## Using it

Add the artifact, load your licensed bundle, and write the attributes:

```kotlin sample=none
dependencies {
    implementation("io.github.markusaugust.streamlord:streamlord-html-pro:0.9.0")
}
```

It brings nothing beyond `streamlord-html`.

## If you do not hold a licence

Then you do not need this module, and nothing else in Streamlord changes. The free Datastar
bundle is what the other eight modules speak, and every page on this site other than this one
applies to it.
