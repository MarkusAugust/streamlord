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
| `never` | network errors only, the same as `auto` |

That last row is not a typo. `'never'` is read in exactly one place in the client, the branch
that decides what to do about a response that is not a 200 (`fetch.ts:594`), and `auto` declines
to retry there anyway. A transport failure is handled somewhere else entirely, in the `catch`
around the request, and that path calls `retryRequest()` without consulting the setting at all
(`fetch.ts:686`). So `never` still reconnects after a dropped connection, up to `retryMaxCount`
times, with the same backoff as any other value. If you need a stream that truly gives up, count
the attempts on your own side; the option name promises more than the client delivers.

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

## Asking again while the stream runs

A request is authorised once and is over in milliseconds. A stream is authorised once and then
lives for minutes or hours, patching the whole time, while the reader logs out, an administrator
revokes access, a subscription lapses or a role changes. Nobody asks again, so the stream carries
on. How much that matters depends on what you are pushing: public dashboard numbers, little;
another team's documents after someone left that team, rather more.

Pass a `StreamAuthorisation` where you open the stream:

```kotlin sample=ktor-routing
get("/feed") {
    val session = call.request.headers["Authorization"]

    call.respondDatastar(
        authorisation = StreamAuthorisation(every = 10.seconds) { session != null },
    ) {
        ticks.collect { tick -> patchSignals("tick" to tick) }
    }
}
```

The check is your own Kotlin, compiled with the rest of your application. It never reaches the
browser and is never named in markup. Look in a database, read a cached token, ask your session
store; Streamlord fixes the contract and not the logic.

Four things it does, each for a reason:

- **It asks at the open, before your handler runs.** The cheapest place to refuse a connection is
  before it starts, and a refusal there writes nothing at all.
- **It asks again on an interval, not before every patch.** A chatty stream would otherwise cost
  one database round trip per patch. The verdict stands for `every`, five seconds by default.
- **A refusal ends the stream** rather than letting it fall silent, so the client sees a finished
  response instead of an idle server. Your handler stops where it stood; nothing after the refused
  write runs.
- **You get the last word.** `onRefused` runs on the still-open stream, so the reader can be told
  rather than dropped:

```kotlin sample=ktor-routing
get("/feed") {
    call.respondDatastar(
        authorisation =
            StreamAuthorisation(
                onRefused = { patchElements("""<p id="notice">Your session expired</p>""") },
            ) { false },
    ) {
        ticks.collect { tick -> patchSignals("tick" to tick) }
    }
}
```

`redirect("/login")` works there too, and so does anything else the stream can send.

What the client does next is the rule from [further up this page](#when-datastar-reconnects-and-when-it-does-not):
a 200 stream that ends cleanly does not reconnect under the default `retry: 'auto'`, which is what
you want. A reader who should be let back in reloads or follows your redirect; one who should not
is not hammering your endpoint in the meantime.

On Spring the parameter is the same, on `datastarStream(streamlord, request, authorisation) { }`.

## Not covered here yet

None of the following has been measured, so none of it is written down as advice:

- whether `runBlocking` pins a carrier thread on the JDK you run
- graceful shutdown with open streams during a rolling deploy
- Micrometer metrics and tracing across a long-lived stream
- the browser's per-origin connection limit over HTTP/1.1
