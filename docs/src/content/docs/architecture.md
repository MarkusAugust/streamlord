---
title: "Architecture"
description: "Ports and adapters, and why the core imports no framework."
---

<!--
  Drawn as elements rather than as text art. The ASCII version wrapped on a phone into a column
  of single letters, because a code block here wraps by design: indentation is how Kotlin says
  what belongs to what, so a line that wraps hangs from where it started. A drawing has no
  indentation to preserve and nothing sensible to wrap to.

  Each box is a <details>, so the shape stays small enough to take in at a glance and the
  explanation is one press away. No script: the element already carries the keyboard handling,
  aria-expanded, and find-in-page opening the box it matched.

  No blank lines inside the figure. Markdown ends a raw HTML block at the first one, and the
  rest of it arrives in the page as an indented code block.
-->
<figure class="hex">
  <div class="hex__flow">
    <details class="hex__box">
      <summary><span class="hex__role">your code</span><span class="hex__name">handlers and markup</span></summary>
      <p>Streamlord is called from here and calls nothing back. You keep your routing, your templates and your domain; the SDK is a library you call, not a framework you sit inside.</p>
    </details>
    <div class="hex__arrow" aria-hidden="true"></div>
    <details class="hex__box hex__box--port">
      <summary><span class="hex__role">driving port</span><span class="hex__name"><code>DatastarStream</code></span></summary>
      <p>The way in. <code>patchElements</code>, <code>patchSignals</code>, <code>executeScript</code>, <code>redirect</code>, <code>replaceUrl</code> and <code>pushUrl</code> are all on this one interface, and <code>respondDatastar</code> hands you one. <code>Streamlord.readSignals</code> is the other direction of the same door.</p>
    </details>
    <div class="hex__arrow" aria-hidden="true"></div>
    <details class="hex__box hex__box--core">
      <summary><span class="hex__role">the core</span><span class="hex__name"><code>streamlord-core</code></span></summary>
      <p>The protocol, the events, the pure encoder, the guards and a strict JSON engine. It imports no framework and depends on nothing but the standard library and coroutines, which is what lets Ktor and Spring WebMVC put identical bytes on the wire.</p>
    </details>
    <div class="hex__arrow" aria-hidden="true"></div>
    <div class="hex__stack">
      <details class="hex__box hex__box--port">
        <summary><span class="hex__role">driven port</span><span class="hex__name"><code>SseSink</code></span></summary>
        <p>Where frames are written. Ktor writes into a response channel, the servlet adapter into an output stream. One mutex per stream means concurrent coroutines never interleave their frames.</p>
      </details>
      <details class="hex__box hex__box--port">
        <summary><span class="hex__role">driven port</span><span class="hex__name"><code>SignalsCodec</code></span></summary>
        <p>How signals become JSON and back. kotlinx.serialization, Jackson 2 or Jackson 3, or the built-in reader that needs no JSON library at all.</p>
      </details>
      <details class="hex__box hex__box--port">
        <summary><span class="hex__role">driven port</span><span class="hex__name"><code>IncomingRequest</code></span></summary>
        <p>Where signals are read from, with the size cap applied while reading. An <code>ApplicationCall</code> on Ktor, an <code>HttpServletRequest</code> on Spring.</p>
      </details>
    </div>
  </div>
  <figcaption>Press a box for what it is. Your code calls the driving port, the core calls the driven ports, and the adapters implement them. Nothing points the other way, which is why the core imports no framework.</figcaption>
</figure>

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
makes three claims on this site true at once.

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

`SseEncoder` takes a `DatastarEvent` and returns the frame as text. No IO, no coroutines, no
framework. That makes it testable against the official SDK golden files on every build, and it
means a protocol bug can be found without starting a server. The comparison is the one the
official suite makes: the same event, the same data lines grouped by their first word, and the
same HTML once whitespace is normalised. It is an equivalence the client cannot tell from
identity, not a comparison of bytes.

## Where to break it

If you are extending Streamlord, the rule is short: **nothing in `streamlord-core` may import a
framework**, and every module is compiled with `explicitApi()` so that the surface you expose is
the surface you meant. Adapters may depend on their framework, and declare it `compileOnly` so it
never reaches a consumer who did not already have it.
