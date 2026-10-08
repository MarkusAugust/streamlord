---
title: "Spring WebMVC"
description: "datastarStream on a servlet response, and the one bean that configures it."
---

Spring is the Shield. Spring and the servlet API are `compileOnly`, so the adapter adds no
version of either. It compiles against Framework 6.2 and is tested against 6.2 (Boot 3) and
7.0 (Boot 4) on every build.

## One bean, declared by you

There is **no auto-configuration**, on purpose. An adapter that configures itself is an adapter
you have to un-configure the day it guesses wrong.

```kotlin sample=spring
@Bean
fun streamlord(mapper: tools.jackson.databind.ObjectMapper): Streamlord =
    Streamlord(codec = JacksonSignalsCodec(mapper))
```

Pick the codec that matches your Boot generation: `JacksonSignalsCodec` from
`streamlord-json-jackson` for Boot 4, `Jackson2SignalsCodec` from `streamlord-json-jackson2` for
Boot 3. The helpers take the bean explicitly, which is what keeps the adapter free of a
component scan.

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

`datastarStream` returns a `StreamingResponseBody`, which is how WebMVC keeps a servlet thread
from being held for the life of the response. Everything inside the block writes one frame.

**Do not take it as a parameter of the handler method.** `fun search(streamlord: Streamlord)`
compiles and starts, and Spring then treats the parameter as a model attribute: it constructs a
new `Streamlord` with every default, for each request, and never looks at your bean. The codec
you configured is gone, so a typed read fails with `BuiltInSignalsCodec cannot decode into
SearchSignals`, and `guardElements` is quietly off. The constructor is the only way in.

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
`SignalsCodecException` to `400`. `@Import(StreamlordExceptionHandler::class)` does exactly that,
or write the `@ControllerAdvice` yourself. Streamlord does not register it for you, because
registering exception handlers behind your back is exactly the kind of help that turns into a
fight; see [Security](/security/). For reactive endpoints, see [Spring WebFlux](/spring-webflux/). The
one filter the adapter offers, `CspNonceFilter`, is a bean you declare like any other, and it is
on [Security](/security/).
