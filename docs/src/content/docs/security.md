---
title: "Security"
description: "Every byte from the browser is untrusted, and the protocol has more sharp edges than it looks."
---

> *Enterprise is a siege. I have watched the Greycloaks take a village with a single open gate;
> Streamlord leaves none.*

## No SSE injection

Selectors, event ids, header values and script attribute names are validated **at construction**.
A selector carrying a newline cannot forge a second `data:` line and smuggle in an event you did
not send. The guard is called `Wire`, it lives in `core.domain`, and it refuses rather than
sanitises. A selector with a line break is a bug in your code, not something to quietly fix.

## No script breakout

`ExecuteScript` neutralises `</script` inside the script body and HTML-escapes attribute values.
`redirect()` quotes the URL as a JSON string literal and navigates from a `setTimeout`, the way
the official SDKs do.

## JavaScript-safe JSON

The built-in writer escapes U+2028, U+2029 and `</`. This is not decoration: `data-signals` is
evaluated as an expression on the client, and U+2028 is a line terminator in JavaScript source
but not in JSON. A string containing one, written naively, ends the expression.

## Bounded input

Incoming signals are capped (1 MiB by default, configurable) and the cap applies *while
reading*:

- a declared `Content-Length` above the limit is rejected before a single byte is read;
- a chunked body is cut off one byte past the limit;
- nothing larger than the cap ever sits in memory.

The parser caps nesting depth at 64, rejects everything RFC 8259 rejects, and is fuzzed on every
build.

**You have one thing to do here:** map `SignalsTooLargeException` to `413` in your framework's
error handling. Streamlord will not register a handler behind your back.

```kotlin sample=ktor-application
install(StatusPages) {
    exception<SignalsTooLargeException> { call, _ ->
        call.respond(io.ktor.http.HttpStatusCode.PayloadTooLarge)
    }
}
```

## CSP

`dataNonce(nonce)` on `<html>` turns on Datastar's CSP mode, and script events and responses
accept a `nonce` attribute. Streamlord has no opinion about your policy beyond giving you the
place to put the nonce.

## Ordered delivery

One mutex per stream. Concurrent coroutines writing to the same stream never interleave their
frames, so a half-written `data: elements` line cannot appear in the middle of another event.

## Explicit API

Every published module is compiled with `explicitApi()` and warnings as errors. Nothing leaves a
module by accident, which means the surface you can depend on is the surface that was meant.

## What is still yours

Authentication, authorisation, CSRF tokens, rate limiting and audit logging. Streamlord adds no
interceptors and takes no view. The one place it touches the subject is that your CSRF token is
just another value in the markup, and the DSL will put it wherever you say.
