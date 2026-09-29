---
title: "Operations"
description: "Reconnects, heartbeats, proxies and threads: what a long-lived stream needs before it goes to production."
---

> *A stream that stops when no one is watching was never a stream. It was a message.*
>
> Gorvek of Bonereach

Datastar's documentation does not describe what its client does when a connection drops, and no
SDK describes it either. Everything here was read out of the Datastar 1.0.4 client source or
measured against a deployed service, and each claim says which. The ones marked **observed** are
behaviour of the client, not promises of the protocol. Check them against the version you ship.

## Your stream dies when the reader switches tabs

`@get` is the only action whose connection closes while the page is hidden:

```
createHttpMethod('get', 'GET', false)   // fetch.ts:241, openWhenHidden defaults to false
createHttpMethod('post', 'POST')        // every other method defaults to true
```

A long-lived stream is almost always a `@get`, so this is almost always your case. The client
aborts the request on `visibilitychange` and builds a fresh one when the page comes back
(`fetch.ts:506`). **Observed.**

Turn it off where you open the stream:

```kotlin sample=html
div {
    dataInit(get("/feed") { openWhenHidden = true })
}
```

Leaving the default is a defensible choice, since a reader who has tabbed away costs you a
connection for nothing. But then every return to the tab is a new request, and your handler has
to answer it from the top.

## When Datastar reconnects, and when it does not

The default is `retry: 'auto'`, which reconnects **only on a transport failure**. Not on a 4xx or
5xx (`fetch.ts:594`), and not when a 200 stream ends cleanly (`fetch.ts:676`). Returning 503 from
a handler under load does not buy you a retry; it buys you a dead stream.

| Value | Reconnects on |
|---|---|
| `auto` (default) | network errors only |
| `error` | network errors, and 4xx/5xx |
| `always` | anything that is not 204 or a redirect, including a clean end |
| `never` | nothing |

Backoff is `retryInterval` (1000 ms) multiplied by `retryScaler` (2) up to `retryMaxWait`
(30 000 ms), for at most `retryMaxCount` (10) attempts. All five are typed:

```kotlin sample=html
div {
    dataInit(
        get("/feed") {
            openWhenHidden = true
            retry = FetchOptions.Retry.ALWAYS
            retryInterval = 2.seconds
            retryMaxCount = 20
        },
    )
}
```

## Resuming: `id:` and `last-event-id`

Two SSE fields the
[SDK specification](https://github.com/starfederation/datastar/blob/develop/sdk/ADR.md) requires
and Datastar's own reference never mentions.

**`retry:`** sets the client's base retry interval, overriding
`retryInterval` (`fetch.ts:669`). **Observed.** Streamlord omits the line when it is the
protocol default of 1000 ms, as the specification says to.

**`id:`**, when you send one, is kept by the client and put on the **next** attempt as a
`last-event-id` request header (`fetch.ts:662`). **Observed.** Send no id and the header is
dropped again.

That is the whole of a resumable stream: number your events, read the header, start after it.

```kotlin sample=ktor-routing
get("/feed") {
    val resumeAfter = call.request.headers["last-event-id"]?.toLongOrNull() ?: 0L
    call.respondDatastar {
        for (entry in 1L..20L) {
            if (entry <= resumeAfter) continue
            patchElements(
                "<li>entry $entry</li>",
                selector = "#feed",
                mode = ElementPatchMode.APPEND,
                eventId = entry.toString(),
            )
        }
    }
}
```

The header name is lower-case on the wire. Ktor and the servlet adapter both look headers up
case-insensitively, so either spelling finds it.

## Heartbeats

An idle proxy closes a connection that has been silent too long. An SSE comment is the cheapest
thing you can put on the wire: the client ignores it, the proxy sees traffic.

```kotlin sample=ktor-routing
get("/slow") {
    call.respondDatastar {
        coroutineScope {
            val heartbeat = launch {
                while (true) {
                    delay(15.seconds)
                    comment()
                }
            }
            try {
                patchElements("""<div id="report">Working</div>""")
                delay(120.seconds)
                patchElements("""<div id="report">Done</div>""")
            } finally {
                heartbeat.cancel()
            }
        }
    }
}
```

Fifteen seconds sits under every default idle timeout we have met. Raise it once you know your
own proxy's.

## Through the proxy

Streamlord sets two headers on every stream:

| Header | Why |
|---|---|
| `Cache-Control: no-cache` | required by the SDK specification |
| `X-Accel-Buffering: no` | nginx buffers a response body by default, which holds your events back until the stream ends |

`Connection: keep-alive` is added **only for HTTP/1.1**, because the header is forbidden over
HTTP/2 and the specification says as much. Ktor reads the request's protocol version by itself.
The servlet adapter does too, when you hand it the request:

```kotlin sample=spring
@GetMapping("/feed")
fun feed(
    request: HttpServletRequest,
    response: HttpServletResponse,
): org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody =
    response.datastarStream(request = request) {
        patchElements("""<div id="feed">Open</div>""")
    }
```

Without the request the header is set anyway, which is right for the servlet containers that
still speak HTTP/1.1 and harmless for the ones that drop it themselves.

### One proxy, measured

Railway does not buffer Server-Sent Events. That is not a guess. The CI run that deploys this
project's demo holds a stream open against the live service for six seconds and fails the build
unless two counter values arrive inside that window. A proxy that buffered the body would hand
back nothing at all. The step is `Check that the deployment answers` in
`.github/workflows/ci.yml`.

That is one proxy. nginx needs `X-Accel-Buffering: no`, which Streamlord sends, or
`proxy_buffering off;` in your own configuration. For anything else, that six-second check is the
shape of the test worth running against it.

## Threads

**Ktor** and **Spring WebFlux** hold no thread per stream. Ktor writes into the response channel
from the handler's own coroutine, and `asServerSentEvents` is a `Flow` mapping that Spring drives
reactively.

**Spring WebMVC** does hold one. `datastarStream` returns a `StreamingResponseBody` and bridges
into the suspending core with `runBlocking` (`Servlet.kt:54`), which occupies the executing thread
for as long as the stream is open. Which thread that is belongs to Spring, not to Streamlord: it
is the MVC async task executor. Boot's default creates a new platform thread per request with no
upper bound, so a thousand open streams are a thousand platform threads.

Make them virtual instead:

```
spring.threads.virtual.enabled=true
```

## Not covered here yet

None of the following has been measured, so none of it is written down as advice:

- whether `runBlocking` pins a carrier thread on the JDK you run
- graceful shutdown with open streams during a rolling deploy
- Micrometer metrics and tracing across a long-lived stream
- the browser's per-origin connection limit over HTTP/1.1
