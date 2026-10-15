---
title: "Spring WebMVC"
description: "datastarStream on a servlet response, and the one bean that configures it."
---

Spring is the Shield. Spring and the servlet API are `compileOnly`, so the adapter adds no
version of either. It compiles and is tested against Framework 7.0 (Boot 4) on every build.
Boot 3 is not supported.

## One bean, declared by you

There is **no auto-configuration**, on purpose. An adapter that configures itself is an adapter
you have to un-configure the day it guesses wrong.

```kotlin sample=spring
@Bean
fun streamlord(mapper: tools.jackson.databind.ObjectMapper): Streamlord =
    Streamlord(codec = JacksonSignalsCodec(mapper))
```

`JacksonSignalsCodec` from `streamlord-json-jackson` matches Boot 4, which ships Jackson 3. The
helpers take the bean explicitly, which is what keeps the adapter free of a component scan.

The same constructor takes `heartbeat`, an interval after which a silent stream gets a
keep-alive comment; [Operations](/operations/#heartbeats) has what it does and what it does not.
`compress = true` gzips the stream when the request passed to `datastarStream` takes it; see
[Operations](/operations/#compression).

## Streaming from a controller

The bean arrives through the controller's constructor, like any other bean:

```kotlin sample=spring-controller
@RestController
class SearchController(private val streamlord: Streamlord) {
    @PostMapping("/search")
    fun search(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody {
        val signals = request.readSignalsOr(SearchSignals(), streamlord)
        val hits = repository.search(signals.query)

        return response.datastarStream(streamlord) {
            patchElements(
                renderResults(hits),
                selector = "#results",
                mode = ElementPatchMode.INNER,
            )
            patchSignals("total" to hits.size)
        }
    }
}
```

`datastarStream` returns a `StreamingResponseBody`. Spring frees the servlet container's thread
and runs the block on a thread from its own async executor, and that thread stays with the
stream until the block returns. Everything inside the block writes one frame.

**Do not take it as a parameter of the handler method.** `fun search(streamlord: Streamlord)`
compiles and starts, and Spring then treats the parameter as a model attribute: it constructs a
new `Streamlord` with every default, for each request, and never looks at your bean. The codec
you configured is gone, so a typed read fails with `BuiltInSignalsCodec cannot decode into
SearchSignals`, and `guardElements` is quietly off. The constructor is the only way in.

## Streams that stay open

Two things decide how long a stream may live and how many can run at once: its timeout, which
one stream can set for itself, and Spring's async executor, which every async request in the
application shares.

**How long.** Spring gives an async request the container's timeout, 30 seconds on Tomcat, and
then cuts it: the block is interrupted and the client sees the stream end. A stream meant to stay
open gives itself a longer one:

```kotlin sample=spring
@GetMapping("/feed")
fun feed(
    request: HttpServletRequest,
    response: HttpServletResponse,
): org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody =
    response.datastarStream(
        request = request,
        timeout = kotlin.time.Duration.INFINITE,
        heartbeat = 15.seconds,
    ) {
        patchElements("""<div id="feed">Open</div>""")
        awaitCancellation()
    }
```

`Duration.INFINITE` lifts the limit, and only with a heartbeat is that safe: without one, a client
that vanished without closing its connection is never written to again, and its stream holds a
thread for good. A finite `timeout`, such as `timeout = 10.minutes`, sets this stream's limit
the same way. Every other request stays on the application's setting. To raise the limit for all
of them at once:

```properties
# application.properties
spring.mvc.async.request-timeout=30m
```

`-1` there removes the limit for every async request, with the same condition: a heartbeat on the
bean, and on every stream that does not pass `heartbeat = null`. Tomcat checks for timeouts once a
second, so one can land up to a second late. `timeout` needs a request that Spring MVC is
handling, and throws outside one rather than doing nothing; one that is not positive throws too.

**How many.** Each open stream holds one thread from that async executor. Under Spring Boot it is
the `applicationTaskExecutor`, which has 8 threads and an unbounded queue. The ninth stream waits
in the queue until a thread comes free, and its timeout is already counting. Give it more
threads:

```properties
spring.task.execution.pool.core-size=64
```

or, on Java 21, a virtual thread for each:

```properties
spring.threads.virtual.enabled=true
```

Declaring an `Executor` bean of your own makes Boot skip `applicationTaskExecutor`, and these
settings with it: Spring MVC then starts a new thread for every request, without limit, and logs
a warning about it. `spring.task.execution.mode=force` keeps Boot's executor alongside yours.

## No stream at all

```kotlin sample=spring
@GetMapping("/panel")
fun panel(): org.springframework.http.ResponseEntity<String> =
    datastarElements("""<div id="panel">Quiet.</div>""")
```

A plain `text/html` response, which Datastar reads as an element patch. When you want the guard
on that HTML, pass the bean: `datastarElements(html, streamlord = bean)`.

## Reading signals

```kotlin sample=spring-controller
@RestController
class PageController(private val streamlord: Streamlord) {
    @GetMapping("/page")
    fun page(request: HttpServletRequest): String {
        val signals = request.readSignals(streamlord)
        val query = signals.string("search") ?: ""
        return query
    }
}
```

The same protocol rule applies as everywhere: query parameter for `GET` and `DELETE`, body for
the rest.

## What this page does not cover

`SignalsTooLargeException` should be mapped to `413`, and `JsonParseException` and
`SignalsDecodeException` to `400`. `@Import(StreamlordExceptionHandler::class)` does exactly that,
or write the `@ControllerAdvice` yourself. Streamlord does not register it for you, because
registering exception handlers behind your back is exactly the kind of help that turns into a
fight. For reactive endpoints, see [Spring WebFlux](/spring-webflux/). The one filter the adapter
offers, `CspNonceFilter`, is a bean you declare like any other, and it is on [Security](/security/).
