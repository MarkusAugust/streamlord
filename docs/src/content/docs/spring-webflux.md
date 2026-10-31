---
title: "Spring WebFlux"
description: "Mapping a Flow of Datastar events onto a reactive response."
---

On the reactive side there is no servlet stream to write into. There is a `Flow` of events, and
Spring knows how to turn `ServerSentEvent` into a response. The adapter's whole job is the step
between: turning Streamlord's `DatastarEvent` into Spring's `ServerSentEvent`.

```kotlin sample=spring
@GetMapping("/counter")
fun counter(): ResponseEntity<Flow<ServerSentEvent<String>>> =
    ticks
        .map { n -> PatchElements("""<span id="counter">$n</span>""") }
        .asDatastarResponse()
```

`asDatastarResponse()` is the flow of server-sent events in a `ResponseEntity` that carries the
content type and the two headers a stream needs on its way through a proxy,
`Cache-Control: no-cache` and `X-Accel-Buffering: no`. They are on [Operations](/operations/).

Two smaller pieces sit under it: `asServerSentEvents()` on a `Flow<DatastarEvent>` gives you the
bare flow, for when you build the response yourself, and `toServerSentEvent()` converts a single
event. Return the bare flow from a controller and Spring sets the content type and no other
header, so prefer the response. Everything before these is your flow, and everything after is
Spring's.

Markup that holds a value from outside is built with [`interpolate`](/strings/#values-from-outside),
and `PatchElements` takes the `Html` it returns as it is:

```kotlin sample=spring
@GetMapping("/feed")
fun feed(): ResponseEntity<Flow<ServerSentEvent<String>>> =
    flowOf(repository.search("gate"))
        .map { hits ->
            val rows = hits.map { interpolate("<li>%s</li>", it.title) }.joinToHtml()
            PatchElements(interpolate("""<ul id="feed">%s</ul>""", rows))
        }.asDatastarResponse()
```

The frames are written by Spring's own encoder, so they are spelled Spring's way: no space after
any colon, `event:datastar-patch-signals`, and `id:` ahead of `event:`. The fields and the data
are the ones Ktor and WebMVC send, and the Datastar client reads both spellings alike.

## The guard

`asDatastarResponse()` uses `Streamlord.Default`, which has the elements guard switched off. To
run your HTML through [the guard](/strings/), pass the bean you declared. It arrives through the
controller's constructor:

```kotlin sample=spring-controller
@RestController
class CounterController(private val streamlord: Streamlord) {
    @GetMapping("/counter")
    fun guarded(): ResponseEntity<Flow<ServerSentEvent<String>>> =
        ticks
            .map { n -> PatchElements("""<span id="counter">$n</span>""") }
            .asDatastarResponse(streamlord)
}
```

Not as a parameter of the handler method. Spring tries to build a new `Streamlord` for a
parameter of that type instead of injecting your bean, fails on the constructor, and answers
every request to that handler with `500` and a `BeanInstantiationException`, as
[on WebMVC](/spring-webmvc/).

## Reading signals

There is no `HttpServletRequest` here. Take the signals the way WebFlux gives them to you, either a
`@RequestParam` named `datastar` on a `GET` or the request body on a `POST`, and hand the JSON
to the codec yourself. The protocol rule for *where* the signals live is the same as everywhere;
only the plumbing differs. A codec that cannot read them throws `SignalsDecodeException`, a `500`
unless you map it: `@Import(StreamlordExceptionHandler::class)` answers it with `400`, as on
WebMVC.

The body never passes through `readSignals` here, so its check of the `Content-Type` is yours.
Declare the mapping `consumes = ["application/json"]`, so a form on another site cannot post
signals to it, as [Security](/security/#forms-from-other-sites) explains; Spring answers anything
else with `415`. Nor does the limit of 64 levels of nesting apply. Jackson refuses a body nested
past its own limit, by default 500 levels in Jackson 3 and 1000 in Jackson 2, with `SignalsDecodeException`.
kotlinx has no limit: it reads what the stack holds, and turns the `StackOverflowError` of a body
nested deeper than that into `SignalsDecodeException`.

## Cancellation

When the client goes away, WebFlux cancels the subscription, which cancels the coroutine behind
the flow. A `flow { }` with a `finally` releases what it held, and nothing in Streamlord holds
anything past the frame it is writing.

## What this page does not cover

Content Security Policy is on [Security](/security/), including the three lines that put a nonce
on an exchange from a `WebFilter` of your own.

Backpressure strategy, schedulers and the rest of the reactive toolbox are Spring's, and the
adapter deliberately does not reach into them. If your flow is hot and fast, the usual
`buffer`, `conflate` and `sample` operators apply before `asDatastarResponse()`, unchanged.
