---
title: "Roadmap"
description: "Three pieces of security work that are planned but not shipped, in the order they will land, and what to write in your own code until each one does."
---

> *A promise is a debt, and I pay mine in iron.*
>
> Gorvek of Bonereach

None of this is in 0.4.0. It is written down so you can see what is coming, decide whether to
wait for it, and know what to write today instead. Each one will be opt-in when it lands, the way
[`guardElements`](/strings/) is, and none of them will change code that does not ask for them.

| Order | Planned | What it governs | What you write today |
|---|---|---|---|
| 1 | [Typed signal reading](#typed-signal-reading) | what the server believes | a signal type per handler |
| 2 | [Continuous authorisation](#continuous-authorisation) | who may still receive a stream | your own check in the stream |
| 3 | [Concurrent stream limits](#concurrent-stream-limits) | how much one reader may hold open | your framework's rate limiting |

The order is the order of the work, not a schedule. The last one lands after the others because
it is the least Datastar-specific thing on the list.

## Typed signal reading

Signals are client-side state. They are visible in page source, and the browser sends them back on
every request, after the reader has had every opportunity to edit them. A signal arriving at a
handler is a request body from a stranger, not an argument.

This is not a departure from Datastar practice, it is Datastar practice: the server holds the
truth. What trips people up is ergonomics. Signals arrive neatly deserialised, so they feel
trustworthy, and unlike a JSON API nobody sat down and decided what the request contains. Signals
accumulate: one for a toggle, one for a filter, and they all travel back together.

Planned: not a new way to read, but a way to see the drift. The markup walker already knows every
signal a page declares, so `streamlord-analysis` can compare that set against the signals a handler
actually reads and warn when they part company. Alongside it, a strict mode that fails on an
unknown signal instead of ignoring it, which is useful in a test and probably too brittle in
production.

### Until then

Declare a type per handler and read into it. This already narrows the input surface the way the
proposal describes, because the codec ignores unknown keys: a page may carry fifteen signals while
the handler that reads two sees two. The calls are on [Signals and codecs](/signals/).

An unread signal is harmless by construction. The danger is only ever a signal that is read and
then trusted, so the type is the place where you decide what this handler accepts.

## Continuous authorisation

An ordinary request is authorised once and is over in milliseconds. A stream is authorised once and
then lives for minutes or hours, patching the whole time, while the reader logs out, an admin
revokes access, a subscription lapses or a role changes. Nobody asks again, so the stream carries
on. How much that matters depends on what you are pushing: public dashboard numbers, little;
another team's documents after someone left that team, rather more.

Planned: not a plugin. [`CspNoncePlugin`](/security/) is a plugin because it touches every
response, and this concerns only the handlers that open a stream, so it belongs on the stream
builder. You will pass a suspending check alongside the usual arguments, Streamlord will call it
with the context it needs and act on the verdict, and the rest of the call site will not move. The refinements that make it
usable: a boolean verdict by default with an optional reason, so the client can be told the session
expired rather than merely dropped; an interval rather than a check before every patch, since a
chatty stream makes a database check expensive; the first check at open, which is the cheapest
place to refuse; and a clean close on failure, so the client re-authenticates instead of assuming
the server went quiet.

### Until then

Do the check inside the stream and end the flow when it fails. Ending it is the part that matters:
a stream that goes silent looks to the client like an idle server.

```kotlin sample=ktor-routing
get("/feed") {
    // Your own check: a session lookup, a cached token, a query. Called before each patch.
    val stillAuthorised: suspend () -> Boolean = { true }

    call.respondDatastar {
        ticks
            .takeWhile { stillAuthorised() }
            .collect { tick -> patchSignals("tick" to tick) }
    }
}
```

What the reader's browser does next depends on how the stream was opened, and the rules are on
[Operations](/operations/): the default `retry: 'auto'` does not reconnect when a 200 stream ends
cleanly, which is what you want here.

## Concurrent stream limits

Framing first, because it matters: this is a sensible default, not a security feature. It will not
stop a determined attacker, who can use more identities. It stops ordinary readers from starving
their own page, which is the common failure.

Opening an SSE stream costs the browser almost nothing. The server holds a socket, a coroutine and
whatever state the stream carries, for as long as it lasts. Ten tabs is ten held connections with
nobody doing anything wrong. Ordinary rate limiting misses this, because a limiter counts arrivals
over time and opening a stream is one request. Five streams a minute is nowhere near any sensible
threshold, and it is five connections held indefinitely. The limiter counts arrivals; the cost here
is occupancy.

Browsers allow roughly six connections per origin on HTTP/1.1, and an open stream occupies one. A
few streams plus normal traffic and the page begins stalling on its own requests, so the browser is
already enforcing a limit well below anything an SDK would set. Over HTTP/2 and HTTP/3 that largely
vanishes, since streams multiplex over one connection. So the default will be low, two or three per
reader, and documented as low on purpose, or someone on HTTP/2 will hit it and conclude it is
arbitrary.

Whether it belongs in Streamlord at all is the open part. Continuous authorisation is at least
SSE-specific, and this is general
server hygiene that Ktor and Spring both have facilities for. The narrow case for it: Streamlord
knows which responses are streams, so it can count the right thing without wiring. That is
convenience, not capability, which is why it is last.

It shares plumbing with continuous authorisation. Both need to know which streams an identity holds
right now, and both need the same clean shutdown path. Building one makes the other cheaper.

### Until then

Use your framework's rate limiting, keyed by user rather than by route, and remember that what you
want to cap is how many streams are open at once, not how many were opened this minute.

## What is not planned

Three defences stay where they are, and no release will move them:

- **Escaping belongs wherever the interpolation happens.** Hand Streamlord the holes and that is
  Streamlord, which is what [`interpolate`](/strings/) is for. Hand it a finished string and the
  provenance is already gone, so the escaping was your engine's job and stays there.
- **Raw user HTML is wrapped in `data-ignore`**, which tells Datastar to skip that element and its
  descendants. That is a decision in your markup, not a setting.
- **Authentication, authorisation, CSRF tokens and audit logging are yours.** Nothing enters your
  chain that you did not install. See [Security](/security/) for the line as it stands today.

## Open questions

Nothing on the three above is undecided enough to change their shape. If you have an opinion on
any of them, the [issue tracker](https://github.com/MarkusAugust/streamlord/issues) is the place
for it.
