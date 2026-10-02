---
title: "Architecture"
description: "Ports and adapters, and why the core imports no framework."
---

```sample=none
                 driving port                       driven ports
   your code ──▶ DatastarStream ──▶ [ core ] ──▶ SseSink        ◀── Ktor channel / servlet stream
                                     │            SignalsCodec   ◀── kotlinx / Jackson / built-in
                 Streamlord.readSignals ──────▶ IncomingRequest  ◀── ApplicationCall / HttpServletRequest
```

## The layers

**Domain** (`core.domain`): `DatastarEvent` as a sealed hierarchy with `PatchElements`,
`PatchSignals` and `ExecuteScript`; `ElementPatchMode` and `ElementNamespace`; the non-SSE
`DatastarResponse` types; and the guards, which are the part that makes this an SDK rather than
an encoder. `Wire` keeps line breaks out of single-line fields, `ExpressionGuard` and
`ElementsGuard` hunt the `$` trap, `interpolate` asks where a value landed, and `CspNonce` holds
the nonce half of Datastar's CSP mode.

**Protocol** (`core.protocol`): `SseEncoder`, which is pure, `SseFrame`, `DatastarAttributes` and
every constant of Datastar 1.0.4.

**Ports** (`core.port`): the driving `DatastarStream`; the driven `SseSink`, `SignalsCodec` and
`IncomingRequest`.

**JSON** (`core.json`): `JsonParser`, `JsonWriter` and the `JsonValue` hierarchy. It is here
because the core refuses a JSON dependency, not because anybody wanted to write one.

**Application** (`core.application`): `Streamlord`, the configured facade the adapters call, and
`StreamAuthorisation`, which a stream carries for as long as it runs.

**Adapters**: everything outside `streamlord-core`.

## Why it is shaped this way

The core never imports a framework. That is not architectural taste for its own sake; it is what
makes three claims on the front page true at once.

It is why the core has **no dependencies** but the standard library and coroutines. There is
nothing in it that needs a JSON library, because `SignalsCodec` is a port and the built-in
implementation is a strict RFC 8259 parser and writer of about the size that one interface
deserves.

It is why **Ktor and Spring are equals**. Neither is the port the other was bolted onto. Both are
an `SseSink` and an `IncomingRequest`, and the encoder they share is the thing the golden files
test.

And it is why a **new realm is small**. Vert.x, http4k, or a plain `com.sun.net.httpserver.HttpServer`
is one `SseSink` and one `IncomingRequest` away. Nothing in the core has to change, and the
conformance tests apply to the result unchanged.

## What the pure encoder buys

`SseEncoder` takes a `DatastarEvent` and returns bytes. No IO, no coroutines, no framework. That
makes it testable against the official SDK golden files byte for byte, which is exactly what the
build does, and it means a protocol bug can be found without starting a server.

## Where to break it

If you are extending Streamlord, the rule is short: **nothing in `streamlord-core` may import a
framework**, and every module is compiled with `explicitApi()` so that the surface you expose is
the surface you meant. Adapters may depend on their framework, and declare it `compileOnly` so it
never reaches a consumer who did not already have it.
