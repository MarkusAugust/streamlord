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
long, for the proxies in between; it is on [Operations](/operations/#heartbeats).

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
the flow produces. It ends when the flow ends, or when the client leaves.

```kotlin sample=ktor-routing
get("/counter") {
    call.respondDatastar(
        ticks.map { n -> patchElements { span { id = "counter"; +"$n" } } },
    )
}
```

Cancellation arrives as coroutine cancellation, so a `flow { }` with a `finally` block cleans up
the way you would expect. One mutex per stream means concurrent coroutines writing to the same
stream never interleave their frames.

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
`SignalsCodecException` mapped to `400` beside it; [Security](/security/) has the block. Authentication, rate limiting and
tracing are Ktor's, unchanged; Streamlord adds no interceptors and has no opinion about them.
