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

The exchange reads them as the servlet request does on WebMVC, by the same rule for where they
live, with the same limit of size and of 64 levels of nesting, and the same check of the body's
`Content-Type`. On WebFlux each read is a `suspend` function, so the handler is one too:

```kotlin sample=spring
@PostMapping("/search")
suspend fun search(exchange: ServerWebExchange): ResponseEntity<Flow<ServerSentEvent<String>>> {
    val query = exchange.readSignals().string("query") ?: ""
    val hits = repository.search(query)
    return flowOf(PatchElements(renderResults(hits), selector = "#results", mode = ElementPatchMode.INNER))
        .asDatastarResponse()
}
```

`exchange.readSignals<SearchSignals>(streamlord)` and `exchange.readSignalsOr(SearchSignals(), streamlord)`
decode them into your class through the bean's codec, and `exchange.readSignalsJson()` gives the
text. A body that is not declared `application/json` throws `SignalsMediaTypeException`, one
over the limit `SignalsTooLargeException`, and one the codec cannot read `SignalsDecodeException`;
`@Import(StreamlordExceptionHandler::class)` answers them with `415`, `413` and `400`, as on WebMVC.
A form sent with `contentType: 'form'` holds fields rather than signals: [Signals](/signals/#a-form)
shows how to read one.

## Redirects, the address bar and removing signals

What a stream does with `redirect`, `replaceUrl`, `pushUrl` and `removeSignals`, a flow does with
the events they send, built by `ExecuteScript` and `PatchSignals`:

```kotlin sample=spring
@PostMapping("/quests")
fun save(): ResponseEntity<Flow<ServerSentEvent<String>>> =
    flowOf(
        PatchSignals.remove("draft"),
        ExecuteScript.replaceUrl("/quests/7"),
        ExecuteScript.redirect("/quests"),
    ).asDatastarResponse()
```

`ExecuteScript.pushUrl` adds a history entry where `replaceUrl` replaces one, and
`PatchElements.remove` removes elements, as `removeElements` does on a stream. Each refuses what
the stream refuses: `redirect` a `javascript:` URL, `remove` a name the client cannot hold.

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
