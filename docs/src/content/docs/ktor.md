---
title: "Ktor"
description: "respondDatastar, readSignals, and the plugin that wires them together."
---

> *That it flows when I say flow. That it stops when I say stop.*
>
> Gorvek of Bonereach

Ktor is the Sword. The adapter is two plugins and a set of extension functions on
`ApplicationCall`: four that answer (`respondDatastar`, `respondElements`, `respondSignals`,
`respondScript`), four that read (`readSignals`, `readSignalsOr`, `readSignalsJson`,
`asIncomingRequest`), several of them with overloads, and three properties, `streamlord`,
`isDatastarRequest` and `cspNonce`. It brings no Ktor version of its own. Ktor is `compileOnly`, so
you stay on whatever you had.

## The plugin

```kotlin sample=ktor-application
install(StreamlordPlugin) {
    codec = KotlinxSignalsCodec()
}
```

The plugin is where the configured `Streamlord` instance lives. Everything below reads it from
the call. The codec is optional: without one, the built-in codec handles maps and raw JSON
perfectly well, and you read signals by name rather than into a data class.

The second plugin is `CspNoncePlugin`, independent of this one and off unless you install it. It
owns the Content Security Policy nonce at both ends and gives `call.cspNonce` its value; it is on
[Security](/security/). `respondDatastar` also takes an `authorisation`, for a stream that should
be asked again whether it may still run, which is on [Operations](/operations/).

`heartbeat` in the plugin writes a keep-alive comment on every stream that has been silent that
long, for the proxies in between, unless the stream passes a `heartbeat` of its own to
`respondDatastar`; it is on [Operations](/operations/#heartbeats).
`compress` gzips every stream whose request takes it; it is on
[Operations](/operations/#compression).

## One-shot streams

The response opens, your block runs, and the response closes when the block returns.

```kotlin sample=ktor-routing
post("/search") {
    val signals = call.readSignalsOr(SearchSignals())
    val hits = repository.search(signals.query)

    call.respondDatastar {
        patchElements(selector = "#results", mode = ElementPatchMode.INNER) {
            ul(classes = "results") {
                hits.forEach { hit -> li { a(href = hit.url) { +hit.title } } }
            }
        }
        patchSignals("total" to hits.size)
    }
}
```

This is the shape most endpoints take. It is a stream in the protocol sense, a
`text/event-stream` response that can carry many frames, but it lives for milliseconds.

## Long-lived streams

Hand `respondDatastar` a `Flow` instead of a block, and the response stays open for as long as
the flow produces. It ends when the flow ends. When the client leaves first, see
[below](#when-the-client-leaves): Ktor does not stop it on its own.

```kotlin sample=ktor-routing
get("/counter") {
    call.respondDatastar(
        ticks.map { n -> patchElements { span { id = "counter"; +"$n" } } },
    )
}
```

One mutex per stream means concurrent coroutines writing to the same stream never interleave
their frames.

## When the client leaves

Ktor does not cancel a handler because its client has gone. A stream that keeps writing ends
when a write fails with an `IOException`, which usually takes a few writes after the client left:
the first ones still land in the operating system's buffer. A stream that waits without writing,
for an event that has not happened yet, waits on with nobody listening.

Install Ktor's `HttpRequestLifecycle` on the route, and the handler is cancelled the moment the
connection closes:

```kotlin sample=ktor-routing
route("/counter") {
    install(HttpRequestLifecycle) { cancelCallOnClose = true }
    get {
        call.respondDatastar(
            ticks.map { n -> patchElements { span { id = "counter"; +"$n" } } },
        )
    }
}
```

Either way the end arrives as an exception in your coroutine, so a `flow { }` or a block with a
`finally` cleans up the way you would expect.

The plugin works on the CIO and Netty engines. It does nothing under `testApplication`, and on
CIO it does nothing for an HTTP/1.0 request or one that sends `Connection: close`, because CIO
stops watching such a connection once the request is read. Install it on the routes that stream
rather than on the whole application: a short request is better left to finish what it started.

## No stream at all

Sometimes you want one HTML body, patched by the client, with no SSE framing:

```kotlin sample=ktor-routing
get("/panel") {
    call.respondElements(elements { div { id = "panel"; +"Quiet." } })
}
```

Datastar reads a plain `text/html` response as a patch. This costs one round trip and no
streaming machinery, and it is often the right answer.

## Reading signals

The protocol decides where the signals are: the `datastar` query parameter for `GET` and
`DELETE`, the request body for `POST`, `PUT`, `PATCH` and `QUERY`. You do not have to remember
that; `readSignals` does.

```kotlin sample=ktor-routing
get("/page") {
    val signals = call.readSignals()
    val query = signals.string("search") ?: ""
    val page = signals.int("page") ?: 1
}
```

`Signals` is dependency-free: `string`, `int`, `long`, `double`, `decimal`, `boolean`, `obj`,
`array`, `has`, and `path` for reaching into nested objects. With a codec configured,
`call.readSignals<T>()` gives you a data class instead, and `call.readSignalsOr(default)` gives
you one without the null check.

## What this page does not cover

Error handling is yours. `SignalsTooLargeException` is thrown while the body is being read, and
you will want it mapped to `413` in a `StatusPages` block, with `JsonParseException` and
`SignalsDecodeException` mapped to `400` beside it; [Security](/security/) has the block.
Authentication, rate limiting and tracing are Ktor's, unchanged; Streamlord adds no interceptors
and has no opinion about them.
