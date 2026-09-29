---
title: "Spring WebFlux"
description: "Mapping a Flow of Datastar events onto a reactive response."
---

On the reactive side there is no servlet stream to write into. There is a `Flow` of events, and
Spring knows how to turn `ServerSentEvent` into a response. The adapter's whole job is the step
between: turning Streamlord's `DatastarEvent` into Spring's `ServerSentEvent`.

```kotlin sample=spring
@GetMapping("/counter", produces = [org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE])
fun counter(): kotlinx.coroutines.flow.Flow<org.springframework.http.codec.ServerSentEvent<String>> =
    ticks
        .map { n -> PatchElements("""<span id="counter">$n</span>""") }
        .asServerSentEvents()
```

That is the entire API surface for WebFlux: `asServerSentEvents()` on a `Flow<DatastarEvent>`,
and `toServerSentEvent()` on a single `DatastarEvent` for the times you are not holding a flow.
Everything before them is your flow, and everything after is Spring's.

## The guard

`asServerSentEvents()` uses `Streamlord.Default`, which has the elements guard switched off. To
run your HTML through [the guard](/strings/), pass the bean you declared:

```kotlin sample=spring
@GetMapping("/counter", produces = [org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE])
fun guarded(
    streamlord: Streamlord,
): kotlinx.coroutines.flow.Flow<org.springframework.http.codec.ServerSentEvent<String>> =
    ticks
        .map { n -> PatchElements("""<span id="counter">$n</span>""") }
        .asServerSentEvents(streamlord)
```

## Reading signals

There is no `HttpServletRequest` here. Take the signals the way WebFlux gives them to you, either a
`@RequestParam` named `datastar` on a `GET` or the request body on a `POST`, and hand the JSON
to the codec yourself. The protocol rule for *where* the signals live is the same as everywhere;
only the plumbing differs.

## Cancellation

When the client goes away, WebFlux cancels the subscription, which cancels the coroutine behind
the flow. A `flow { }` with a `finally` releases what it held, and nothing in Streamlord holds
anything past the frame it is writing.

## What this page does not cover

Backpressure strategy, schedulers and the rest of the reactive toolbox are Spring's, and the
adapter deliberately does not reach into them. If your flow is hot and fast, the usual
`buffer`, `conflate` and `sample` operators apply before `asServerSentEvents()`, unchanged.
