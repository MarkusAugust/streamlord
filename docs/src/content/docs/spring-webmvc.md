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
keep-alive comment, 30 seconds unless you set it, `null` for none;
[Operations](/operations/#heartbeats) has what it does and what it does not.
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

Call `datastarStream` before anything writes to the response. One that something has already
written to through its output stream still streams, without gzip, after a blank line that closes
whatever was left open; one written through `getWriter()`, or already committed, makes
`datastarStream` throw `IllegalStateException`, since the stream needs the output stream and a
committed response has sent its headers, the content type with them.

The stream sets `Content-Encoding` and `Connection` with its first write. A block that fails
before it writes anything leaves the response to an error answer as it was, so a `500` or a
mapped `413` does not claim to be gzipped.

A filter that caches the response, as Spring's `ShallowEtagHeaderFilter` does to compute an ETag,
would hold the events back until the stream ends; the stream writes past its
`ContentCachingResponseWrapper`, and the events leave as they are sent.

**Do not take it as a parameter of the handler method.** `fun search(streamlord: Streamlord)`
compiles and starts, and Spring then treats the parameter as a model attribute: it tries to
build a new `Streamlord` for each request instead of passing your bean, and cannot: `heartbeat`
is a `Duration`, a Kotlin value class, and the arguments Spring prepares do not fit the
constructor the JVM sees. Every request to that handler answers `500` before the handler runs,
and Tomcat logs a `BeanInstantiationException`, here with its first line shortened at each
`...`:

```text
... threw exception [Request processing failed: org.springframework.beans.BeanInstantiationException: ...] with root cause

java.lang.IllegalArgumentException: Number of provided arguments must be less than or equal to the number of constructor parameters
```

The constructor is the only way in.

## Streams that stay open

Two things decide how long a stream may live and how many can run at once: its timeout, which
one stream can set for itself, and Spring's async executor, which every async request in the
application shares.

**How long.** Spring gives an async request the container's timeout, 30 seconds on Tomcat, and
then ends it: the block is interrupted, and the response ends as if the stream had finished,
so Datastar's client does not reconnect and the page stops updating. A stream meant to stay open
gives itself a longer one:

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

`Duration.INFINITE` lifts the limit, and only with a heartbeat, which is on by default, is that
safe: without one, a stream that writes nothing is never told that its client left, closed
connection or not, and holds a thread for good. A finite `timeout`, such as
`timeout = 10.minutes`, sets this stream's limit the same way. Every other request stays on the
application's setting. To raise the limit for all of them at once:

```properties
# application.properties
spring.mvc.async.request-timeout=30m
```

`-1` there removes the limit for every async request, with the same condition: leave the
heartbeat on, on the bean and on every stream. Tomcat checks for timeouts once a
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
settings with it: Spring MVC then starts a new thread for every request, without limit, and says
nothing about it. `spring.task.execution.mode=force` keeps Boot's executor alongside yours.

Or keep the long streams off that executor altogether, so the short requests behind them never
wait. `executor` gives one stream an executor of its own:

```kotlin sample=spring
private val feeds = org.springframework.core.task.VirtualThreadTaskExecutor("feed-")

@GetMapping("/feed")
fun feed(
    request: HttpServletRequest,
    response: HttpServletResponse,
): org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody =
    response.datastarStream(request = request, executor = feeds) {
        patchElements("""<div id="feed">Open</div>""")
        awaitCancellation()
    }
```

`VirtualThreadTaskExecutor` needs Java 21. On older Java, build a `ThreadPoolTaskExecutor` with
`corePoolSize` set to the number of streams it should run at once, call `initialize()` before the
first stream and `shutdown()` when the application stops. Its queue has no limit unless you give
it one, so `maxPoolSize` alone changes nothing. Declared as a `@Bean` instead, it makes Boot drop
`applicationTaskExecutor`, as above, unless `spring.task.execution.mode=force` is set.

An executor that rejects the stream, because its queue is full or it has been shut down, throws
`TaskRejectedException` before anything is written. It reaches your `@ExceptionHandler` like any
other exception, as a 500 unless you map it; a 503 tells the client to try again.

Like `timeout`, `executor` needs a request that Spring MVC is handling, and a `suspend`
controller has to pass it: Spring's `RequestContextHolder` does not follow a controller across a
suspension. Without it there, `Connection: keep-alive` is left out on HTTP/1.1 too, which HTTP/1.1
does without.

The heartbeat does not use that executor. It runs on `Dispatchers.IO`, beside the block, so a
block that blocks its thread does not hold the comment back. An authorisation asked by the
heartbeat runs there too, without the request's thread-locals; see
[Operations](/operations/#heartbeats).

## A flow as the response

`asDatastarResponse()` from [Spring WebFlux](/spring-webflux/) works from a WebMVC controller
too:

```kotlin sample=spring-controller
@RestController
class TickerController(private val streamlord: Streamlord) {
    @GetMapping("/ticker")
    fun ticker(): org.springframework.http.ResponseEntity<kotlinx.coroutines.flow.Flow<org.springframework.http.codec.ServerSentEvent<String>>> =
        ticks
            .map { n -> PatchElements("""<span id="counter">$n</span>""") }
            .asDatastarResponse(streamlord)
}
```

It needs `kotlinx-coroutines-reactor`, which brings Reactor with it. Spring MVC writes a returned
`Flow` through Reactor, and without the two it cannot write one at all: the request fails with a
500 and `HttpMessageNotWritableException: No converter for [...] with preset Content-Type
'text/event-stream'`.

Spring then writes the flow itself, and no Streamlord stream is opened. The bean renders the
events, its guard included, and does nothing more:

- **No timeout.** The application's async timeout does not apply, so the response lasts as long
  as the flow does.
- **No heartbeat, no gzip, no authorisation.** The bean's `heartbeat` and `compress` do not reach
  it, and there is no `authorisation` to pass. A quiet flow that never ends is never written to,
  so a client that vanished can go unnoticed until the next element.
- **No thread held between elements.** The flow runs on the request's thread up to its first
  suspension, and after that on whichever thread resumes it. After a `delay`, that is
  `kotlinx.coroutines.DefaultExecutor`, the single thread the coroutines library keeps for
  timers. Keep blocking calls off it with `flowOn(Dispatchers.IO)`.

Take `datastarStream` when the stream needs a heartbeat, a timeout, gzip or an authorisation.
Take the flow when what you send is a flow you already have, and the stream should hold no thread
while it waits. The frames are spelled Spring's way, as on [Spring WebFlux](/spring-webflux/).

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

`SignalsTooLargeException` should be mapped to `413`, `JsonParseException` and
`SignalsDecodeException` to `400`, and `SignalsMediaTypeException`, a body not declared
`application/json`, to `415`. `@Import(StreamlordExceptionHandler::class)` does exactly that,
or write the `@ControllerAdvice` yourself. An advice that answers an exception thrown inside a
stream that has started ends it as if it had finished, and Datastar's client does not reconnect;
throw the exception on when `response.isCommitted`, as `StreamlordExceptionHandler` does, and the
container cuts the connection, which the client retries. Over HTTP/2 straight to Tomcat under
Spring Boot it does not: Boot's error page runs into the started response, and Tomcat ends the
stream as if it had finished. Over HTTP/1.1, which a proxy in front usually speaks to the
application, Tomcat and Jetty cut it. Streamlord does not register it for you, because registering
exception handlers behind your back is exactly the kind of help that turns into a fight. For
reactive endpoints, see [Spring WebFlux](/spring-webflux/). The one filter the adapter offers,
`CspNonceFilter`, is a bean you declare like any other, and it is on [Security](/security/).
