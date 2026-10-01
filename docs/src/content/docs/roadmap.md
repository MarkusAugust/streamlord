---
title: "Roadmap"
description: "What is planned but not shipped, in the order it will land, and what to do in your own code until each one does."
---

> *A promise is a debt, and I pay mine in iron.*
>
> Gorvek of Bonereach

None of this is in 0.6.0. It is written down so you can see what is coming, decide whether to
wait for it, and know what to do today instead. Everything that touches your code is opt-in when
it lands, the way [`guardElements`](/strings/) is, with one exception that is marked as such.

| Order | Planned | What it changes | What you do today |
|---|---|---|---|
| 1 | [Continuous authorisation](#continuous-authorisation) | who may still receive a stream | your own check in the stream |
| 2 | [Assert on events, not on strings](#assert-on-events-not-on-strings) | what a test of an endpoint looks like | compare the wire text, as [Testing](/testing/) shows |
| 3 | [The happy path, documented](#the-happy-path-documented) | what a reader sees first | [Introduction](/introduction/) and [Your first stream](/first-stream/) |
| 4 | [Untrusted values in the DSL](#untrusted-values-in-the-dsl) | what reaches an expression built by the DSL | keep request data out of expression attributes |
| 5 | [Replay in the Stream Inspector](#replay-in-the-stream-inspector) | what you can see of a stream after it ran | watch it arrive live |
| 6 | [Concurrent stream limits](#concurrent-stream-limits) | how much one reader may hold open | your framework's rate limiting |
| 7 | [Starter templates](#starter-templates) | how long it takes to get a first page running | copy the `demo` module |

The order is the order of the work, not a schedule. The last one waits for 1.0 on purpose: a
template that lags the API it demonstrates teaches the wrong thing, and the API is still moving.

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

## Assert on events, not on strings

[Testing](/testing/) already shows how to test an endpoint, and the examples on it say what is
wrong: they compare the whole response body as one string.

```
event: datastar-patch-elements
data: selector #feed
data: mode append
data: elements <li>A new head</li>
```

That is exact, and it breaks for reasons that are not bugs. Add an event id, emit two patches in
the other order, change one class in the markup, and every assertion in the suite goes red at
once. A test that cries wolf is a test people stop writing.

Planned: a small module that parses the body and lets you assert on what you meant. The selector
and mode of a patch, that a signal became 5, that a stream sent three events and no more, with
order mattering only where you say it does.

Most of it exists already. `SseParser`, `decodeDatastar` and `DatastarFrame` live in
`streamlord-analysis`, written for the Stream Inspector, which has been parsing real streams for
several releases. `mergePatch` is there too, the RFC 7386 merge the client applies to its store,
so a test can also ask what the signals would be after the stream rather than what each patch
said. What is missing is the layer above: frames back into the typed events of
`streamlord-core`, and assertions that read like the thing being checked.

### Until then

The parser is public. If you already take `streamlord-analysis` as a test dependency for the
drift check, you can feed it the body and assert on frames today:

```kotlin sample=test
@Test
fun `the feed appends one item`() =
    testApplication {
        routing {
            get("/feed") {
                call.respondDatastar {
                    patchElements("<li>A new head</li>", selector = "#feed", mode = ElementPatchMode.APPEND)
                }
            }
        }

        val frames = SseParser().feed(client.get("/feed").bodyAsText()).map { decodeDatastar(it) }

        assertEquals(1, frames.size)
        assertEquals("datastar-patch-elements", frames[0].event)
        assertEquals("#feed", frames[0].args["selector"])
        assertEquals("<li>A new head</li>", frames[0].args["elements"])
    }
```

That is the shape the module will have; what it will add is doing this for you and saying
something useful when it fails.

## The happy path, documented

This site explains the mechanism well and the point badly. The evidence is specific: a developer
who read it came back asking for a typesafe kotlinx.html layer with Datastar attributes and
completion, which is [`streamlord-html`](/html-dsl/), the flagship module, shipped since the first
release. If someone can read the documentation and not find the headline feature, the
documentation is the thing at fault.

Planned: a before and after at the top, in the README and on the first page. The same endpoint
written by hand against the protocol, and written with Streamlord, side by side. A screenshot of
the editor refusing `data-text="$count"` before the code compiles, because the thing that is hard
to believe until you see it is that a string gets checked at all. The three ways to write markup
named in the first screen rather than four pages in.

Nothing to configure and nothing to wait for. It is the cheapest item here and the one with the
most evidence behind it.

## Untrusted values in the DSL

[`interpolate`](/strings/) asks where a value landed, which it can only do because the holes are
still standing in the format string. The DSL has no holes: `dataText(expression)` takes a `String`
and the call itself is the position, so `dataText(userName)` is the original hazard with nothing
in the way. Markup written as a string or rendered by a template is covered; markup built by the
DSL is not, and it is the style the documentation leads with.

Planned: expression-valued attributes stop taking a bare `String`. They take a type that only the
expression helpers build, `signal()`, `set()`, `toggle()`, `js()` and the rest, with one explicit
constructor for an expression you wrote yourself. The guard becomes the compiler: a value from a
request cannot reach `dataText` without someone writing the word that says they meant it, which
is the same bargain `Trusted` makes on the string side.

It is the one item here that breaks an API. Every `dataText("$count * 2")` written as a raw string
has to become the explicit form. That is a day of mechanical edits with the compiler pointing at
each one, and the editors can offer the fix.

### Until then

Keep request data out of expression attributes in the DSL, and put it in element text or an
ordinary attribute, where kotlinx.html escapes it for you. When a value really has to reach an
expression, patch it into a signal from the server rather than writing it into the markup, which
is what signals are for.

## Replay in the Stream Inspector

The inspector opens a real Datastar request, exactly as the browser client would, parses the
stream, and keeps every frame it decoded. It also keeps the signal store, applying each
`datastar-patch-signals` as an RFC 7386 merge and honouring `__ifMissing`, so what it shows is
what the client would hold.

What it does not keep is the store at each step. `signals = mergePatch(signals, patch)` overwrites,
so there is one running value and no way back. When a handler emits fifteen frames in half a
second, you watch the number settle and cannot ask what it was at frame nine.

Planned: a position control over the frames already on screen. Step back, and the store panel
shows what the client would have held at that point. This needs no new capture and no protocol
work: every frame is kept with its arguments, `mergePatch` is a pure function, so the store at any
index is a fold from the start. The work is the control and the wiring, in both plugins.

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

It shares less with continuous authorisation than it looks. That check is per stream, made inside
the stream's own write path, and needs no shared state at all. This one needs a registry of which
streams an identity holds right now, and a counter that decrements on every way out: a clean end, a
disconnect, an exception, a cancelled coroutine. One missed decrement locks a reader out of their
own page for good, and nothing announces it. What the two do share is the clean shutdown path, so
that a client reacts instead of sitting idle.

### Until then

Use your framework's rate limiting, keyed by user rather than by route, and remember that what you
want to cap is how many streams are open at once, not how many were opened this minute.

## Starter templates

After 1.0, not before.

A hypermedia library is judged in the first ten minutes, and ten minutes is about what it takes to
wire Ktor or Spring Boot up from nothing. A repository with a running page, a search box that
answers over the wire, and a "Use this template" button removes that, and "show, don't tell" is
the right instinct.

Planned: one template, not two. Two repositories is two things to keep in step with every release,
and a template that has fallen a version behind teaches the wrong API with full confidence. One
repository covering both adapters, or the `demo` module documented as the thing to copy, carries
the same weight for a fraction of the upkeep.

It waits for 1.0 because the API is still moving. Three releases this week changed the surface,
and each of them would have aged a template silently.

### Until then

`demo` in this repository is a real application that runs the real library: a search answered over
SSE, a stream held open for twelve seconds, the eight patch modes, form validation, and a page
served under a Content Security Policy. It is a module in a Gradle build rather than a template,
so copying it means copying a directory and its build file, but nothing in it is a toy.

## Considered, not prioritised

Weighed and set aside for now, with the reason, so the same ground is not covered twice:

- **A runtime mode that refuses unknown signals.** It would catch the same drift that
  [`signalDrift`](/testing/) catches without a request in flight, and it fails every handler the
  moment any page gains a signal. If you want it anyway it is your codec's setting, not ours:
  `KotlinxSignalsCodec(json = Json { ignoreUnknownKeys = false })`.
- **Simulating latency or a dropped connection in the Stream Inspector.** The inspector is a
  client inside the editor, not the browser, so slowing it down says nothing about how the
  Datastar client copes; the browser's own throttling does that properly. Cutting a stream off to
  see whether the *server* notices is the useful half, and the Stop button already does it.
- **Escaping on the way out of the DSL.** kotlinx.html escapes text and attribute values already.
  What it cannot do is refuse a value in an expression position, which is
  [its own entry](#untrusted-values-in-the-dsl) rather than a second escaping layer.

## Open questions

One thing is genuinely undecided: what a concurrent stream limit should do at the limit. Refusing
the new stream is safer and breaks the innocent case, where a reader opens a fresh tab and it
quietly does not work; closing the oldest favours the tab they are looking at. Everything else
above is decided enough to build.

If you have an opinion on any of it, the
[issue tracker](https://github.com/MarkusAugust/streamlord/issues) is the place for it.
