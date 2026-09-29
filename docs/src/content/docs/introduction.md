---
title: "Introduction"
description: "A Kotlin SDK for Datastar that brings nothing you do not already carry."
---

> *Every soul in Gallowmark knows my name, and not one knows my face. I took three rubies from
> the Iron Crown of Kell and the land fell apart in my hands. Now I will take one thing from you:
> the belief that the browser must be bought with JavaScript.*
>
> — Sarn the Faceless, whom the river-priests of Thurn call the Lord of Streams

Streamlord speaks the [Datastar](https://data-star.dev) 1.0.4 Server-Sent Events protocol from
Kotlin. Your server sends HTML and signal patches down one connection; the browser applies them.
There is no client bundle of yours to build, no JSON API to design, and no second model of your
page living in a framework.

```kotlin sample=ktor-routing
get("/feed") {
    call.respondDatastar {
        patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
            li { +"A new head hangs on the wall" }
        }
        patchSignals("heads" to 13)
    }
}
```

## What you get

**Two realms, served without favour.** Ktor is the Sword, Spring is the Shield. Neither is the
port the other was bolted onto: both are adapters over the same core, and the bytes they put on
the wire are identical.

**An encoder that cannot be talked into lying.** Eight patch modes exist, and every mode except
`outer` and `replace` requires a selector. Streamlord refuses to construct an event the client
would reject, at the point you construct it, rather than letting you discover it in a browser
console.

**Three ways to write markup, as equals.** The kotlinx.html DSL, plain Kotlin strings, and
template engines. None of them is the real one with the others tolerated beside it.

**Editors that read Datastar as a language.** A VS Code extension and an IntelliJ plugin colour
signals, actions and modifiers inside your Kotlin and your HTML, and say so when a modifier is
misspelled. The code on this site is highlighted by the very same grammars.

## What it costs you

Almost nothing, and that is deliberate. The core carries the Kotlin standard library and
coroutines, and nothing else — not even a JSON library, because it has its own strict RFC 8259
parser and writer. The framework adapters compile against your framework and add no version of
their own. You reach for a codec module only when you want data classes as signals.

```kotlin sample=none
dependencies {
    implementation("io.github.markusaugust.streamlord:streamlord-ktor:0.3.1")
    implementation("io.github.markusaugust.streamlord:streamlord-html:0.3.1")          // optional
    implementation("io.github.markusaugust.streamlord:streamlord-json-kotlinx:0.3.1")  // optional
}
```

## What it does not do

It does not render your pages for you, and it has no opinion about how you build HTML beyond
giving you three ways to do it. It does not ship Datastar itself — the client bundle is yours to
include, from a CDN or from your own static files. It does not ship Datastar Pro, and it never
will; the Pro helpers emit the documented attribute names and nothing more, and the bundle and
the licence are yours to bring.

And it does not guess. Where the protocol is ambiguous, Streamlord refuses rather than picks.

> *The stream was always yours to bend. Sit. Read.*
